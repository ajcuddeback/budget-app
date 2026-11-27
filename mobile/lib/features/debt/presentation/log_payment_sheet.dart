import 'dart:async';

import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/debt/data/debt_repository.dart';
import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/features/debt/domain/requests.dart';
import 'package:budget_owl/features/debt/presentation/debt_formats.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// How long typing settles before the split is asked for: "445" is one question, not four.
const Duration previewSettles = Duration(milliseconds: 350);

/// Log a payment by hand — the slice-10 path (debt-plan.md, Payment actions).
///
/// - **Prefilled with what is scheduled** — the plan's allocation — with that plus fifty one tap
///   away. Neither submits: the figure sent is the one the household says it paid.
/// - **The split is the server's, shown before anything is recorded**: a `POST` preview, through
///   the same code as logging. The phone computes nothing, not even as a fallback. **A late answer
///   is dropped**, **a half-typed amount asks nothing**, and **a failed preview never blocks
///   recording**.
/// - **"Paid from" is not a transaction**, and not where the debt is held. Nothing is written to it.
class LogPaymentSheet extends ConsumerStatefulWidget {
  const LogPaymentSheet({required this.debt, required this.accounts, super.key});

  final Debt debt;
  final List<Account> accounts;

  @override
  ConsumerState<LogPaymentSheet> createState() => _LogPaymentSheetState();
}

class _LogPaymentSheetState extends ConsumerState<LogPaymentSheet> {
  final _form = GlobalKey<FormState>();
  late final String _scheduled = trimAmount(widget.debt.payment ?? widget.debt.minimumPayment);
  late final String? _more = switch (addForQuery(_scheduled, '50')) {
    final String sum => trimAmount(sum),
    null => null,
  };
  late final _amount = TextEditingController(text: _scheduled);
  final _note = TextEditingController();
  late CalendarDate _paidDate = CalendarDate.of(ref.read(clockProvider)());
  String? _paidFrom;
  Timer? _settle;

  /// Which preview is the current one: answers can arrive in any order.
  int _request = 0;
  String _asked = '';
  PaymentPreview? _preview;
  bool _previewUnavailable = false;
  AppError? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    // The sheet opens with a figure in it, so it opens with a question about that figure.
    scheduleMicrotask(() => unawaited(_ask(_scheduled)));
  }

  @override
  void dispose() {
    _settle?.cancel();
    // A preview belongs to this sheet; an answer arriving after it closed is dropped.
    _request++;
    _amount.dispose();
    _note.dispose();
    super.dispose();
  }

  void _typed(String value) {
    _settle?.cancel();
    _settle = Timer(previewSettles, () => unawaited(_ask(value)));
  }

  /// One tap: what is scheduled, or that plus fifty. Asked at once — nobody is typing.
  void _use(String amount) {
    _settle?.cancel();
    setState(() => _amount.text = amount);
    unawaited(_ask(amount));
  }

  Future<void> _ask(String typed) async {
    if (!mounted) return;
    final figure = isTypedAmount(typed) ? typed.trim() : '';
    // The date is part of the question: interest is charged once per debt per month (ADR-0043),
    // so a back-dated payment is asked about in its own month.
    final paidDate = _paidDate;
    final key = '$figure|${paidDate.iso}';
    if (key == _asked) return;
    _asked = key;
    final request = ++_request;
    if (figure.isEmpty) {
      setState(() {
        _preview = null;
        _previewUnavailable = false;
      });
      return;
    }
    try {
      final answer = await ref.read(debtWriterProvider).preview(widget.debt, figure, paidDate);
      if (!mounted || request != _request) return;
      setState(() {
        _preview = answer;
        _previewUnavailable = false;
      });
    } on AppError {
      if (!mounted || request != _request) return;
      setState(() {
        _preview = null;
        _previewUnavailable = true;
      });
    }
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _paidDate.forPicker,
      firstDate: DateTime(1970),
      lastDate: DateTime(2100),
    );
    if (picked != null) {
      setState(() => _paidDate = CalendarDate.of(picked));
      // A different date can be a different month, and so a different answer.
      unawaited(_ask(_amount.text));
    }
  }

  Future<void> _save() async {
    if (_busy || !(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final logged = await ref
          .read(debtWriterProvider)
          .logPayment(
            widget.debt,
            PaymentForm(
              paidDate: _paidDate,
              amount: _amount.text,
              paidFromAccountId: _paidFrom,
              note: _note.text,
            ),
          );
      // The split the server recorded, under the rate in force on the day — reported, not predicted.
      if (mounted) {
        Navigator.of(context).pop(
          l.debt_payment_recorded(
            context.money(logged.money(logged.interest)),
            context.money(logged.money(logged.principal)),
            context.money(logged.money(logged.balanceAfter)),
          ),
        );
      }
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final online = canWriteNow(ref);
    final debt = widget.debt;
    final open = widget.accounts.where((a) => !a.archived).toList();
    final preview = _preview;
    return SheetFrame(
      title: l.debt_payment_title(debt.name),
      busy: _busy,
      formKey: _form,
      error: _error,
      children: [
        Sentence(l.debt_payment_lede),
        fieldGap,
        TextFormField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autocorrect: false,
          enableSuggestions: false,
          onChanged: _typed,
          decoration: InputDecoration(
            labelText: l.debt_payment_amount,
            helperText: l.debt_payment_amountHint,
          ),
          validator: (value) => amountProblem(context, value),
        ),
        const SizedBox(height: SpaceTokens.s2),
        Wrap(
          spacing: SpaceTokens.s2,
          runSpacing: SpaceTokens.s1,
          children: [
            OutlinedButton(
              onPressed: () => _use(_scheduled),
              child: Text(l.debt_payment_useScheduled(context.money(debt.money(_scheduled)))),
            ),
            if (_more != null)
              OutlinedButton(
                onPressed: () => _use(_more),
                child: Text(l.debt_payment_useMore(context.money(debt.money(_more)))),
              ),
          ],
        ),
        const SizedBox(height: SpaceTokens.s2),
        // Beside the amount that changed it, announced as it changes.
        Semantics(
          liveRegion: true,
          child: preview == null
              ? const SizedBox.shrink()
              : Sentence(
                  l.debt_payment_preview(
                    context.money(preview.money(preview.interest)),
                    context.money(preview.money(preview.principal)),
                    context.money(preview.money(preview.balanceAfter)),
                  ),
                  style: Theme.of(context).textTheme.titleSmall,
                ),
        ),
        // Why the interest is what it is, in the server's words: a month's first payment can leave
        // the balance higher than it found it, and that must not read as a mistake (ADR-0043).
        if (preview != null && preview.interestMonth != null)
          switch (preview.monthInterest) {
            'CARRIED' => CardNote(
              l.debt_payment_carriesMonthInterest(
                formatsOf(context).month(preview.interestMonth),
                context.money(preview.money(preview.interest)),
              ),
            ),
            'ALREADY_CARRIED' => CardNote(
              l.debt_payment_monthInterestAlreadyCharged(
                formatsOf(context).month(preview.interestMonth),
              ),
            ),
            _ => const SizedBox.shrink(),
          },
        if (_previewUnavailable)
          // A convenience that failed: it never stops somebody recording a payment.
          CardNote(l.debt_payment_previewUnavailable)
        else if (preview == null)
          CardNote(l.debt_payment_splitNote),
        fieldGap,
        InkWell(
          onTap: _pickDate,
          child: InputDecorator(
            decoration: InputDecoration(
              labelText: l.debt_payment_paidDate,
              suffixIcon: const Icon(Icons.calendar_today_outlined),
            ),
            child: Text(context.date(_paidDate)),
          ),
        ),
        fieldGap,
        DropdownButtonFormField<String?>(
          initialValue: _paidFrom,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: l.debt_payment_paidFrom,
            helperText: l.debt_payment_paidFromHint,
            helperMaxLines: 5,
          ),
          items: [
            DropdownMenuItem<String?>(child: Text(l.debt_payment_noAccount)),
            for (final a in open) DropdownMenuItem<String?>(value: a.id, child: Text(a.name)),
          ],
          onChanged: (id) => setState(() => _paidFrom = id),
        ),
        fieldGap,
        TextFormField(
          controller: _note,
          maxLength: 500,
          decoration: InputDecoration(labelText: l.debt_payment_note),
        ),
        fieldGap,
        FilledButton(
          onPressed: _busy || !online ? null : _save,
          child: Text(_busy ? l.common_saving : l.debt_payment_record),
        ),
        const OfflineNote(),
      ],
    );
  }
}
