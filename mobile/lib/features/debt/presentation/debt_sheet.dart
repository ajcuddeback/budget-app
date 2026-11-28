import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/bills/domain/bill.dart';
import 'package:budget_owl/features/debt/data/debt_repository.dart';
import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/features/debt/domain/requests.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/validation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Add a debt, or change its terms (debt-plan.md, "Add a debt / Edit terms").
///
/// - **Every amount and the rate are text with a decimal keyboard**, sent as typed (ADR-0006).
/// - **The balance comes with the date it was true**: the pair is the anchor. On an edit it is
///   shown and **not editable**: the terms `PATCH` never carries it — it moves only through a
///   confirmed statement, which keeps its date and provenance.
/// - **The rate can be handed back to the solver**, on an edit only: an explicit `null` when it was
///   entered, nothing at all when it is already solved.
/// - **A new debt must name its rate**, and an account-linked debt takes the account's currency.
class DebtSheet extends ConsumerStatefulWidget {
  const DebtSheet({
    required this.accounts,
    required this.bills,
    required this.currency,
    this.debt,
    super.key,
  });

  final Debt? debt;
  final List<Account> accounts;

  /// Bills that could carry this debt's minimum: this month's active ones.
  final List<Bill> bills;

  /// The household's own currency, for the amount hints.
  final String currency;

  @override
  ConsumerState<DebtSheet> createState() => _DebtSheetState();
}

class _DebtSheetState extends ConsumerState<DebtSheet> {
  final _form = GlobalKey<FormState>();
  late final DebtForm _initial = widget.debt == null
      ? DebtForm(
          name: '',
          kind: DebtKind.creditCard,
          balance: '',
          balanceDate: CalendarDate.of(ref.read(clockProvider)()),
          minimumPayment: '',
        )
      : DebtForm.of(widget.debt!);
  late final _name = TextEditingController(text: _initial.name);
  late final _servicer = TextEditingController(text: _initial.servicer);
  late final _balance = TextEditingController(text: _initial.balance);
  late final _apr = TextEditingController(text: _initial.apr);
  late final _minimum = TextEditingController(text: _initial.minimumPayment);
  late final _original = TextEditingController(text: _initial.originalBalance);
  late final _currency = TextEditingController();
  late DebtKind _kind = _initial.kind;
  late CalendarDate _balanceDate = _initial.balanceDate;
  late bool _aprSolved = _initial.aprSolved;
  late String? _accountId = _initial.accountId;
  late String? _billId = _initial.billId;
  AppError? _error;
  bool _busy = false;

  bool get _editing => widget.debt != null;

  @override
  void dispose() {
    for (final c in [_name, _servicer, _balance, _apr, _minimum, _original, _currency]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _balanceDate.forPicker,
      firstDate: DateTime(1970),
      lastDate: DateTime(2100),
    );
    if (picked != null) setState(() => _balanceDate = CalendarDate.of(picked));
  }

  Future<void> _save() async {
    // Trimmed before it is checked, so a name of nothing but spaces is caught here.
    _name.text = _name.text.trim();
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final form = DebtForm(
      name: _name.text,
      kind: _kind,
      servicer: _servicer.text,
      currency: _accountId == null ? _currency.text : '',
      balance: _balance.text,
      balanceDate: _balanceDate,
      apr: _aprSolved ? '' : _apr.text,
      aprSolved: _aprSolved,
      minimumPayment: _minimum.text,
      originalBalance: _original.text,
      accountId: _accountId,
      billId: _billId,
    );
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final writer = ref.read(debtWriterProvider);
      final debt = widget.debt;
      if (debt == null) {
        await writer.create(form);
        if (mounted) Navigator.of(context).pop(l.debt_created);
      } else {
        final sent = await writer.update(debt, form);
        // Naming the release is the interaction: hand one back and the screen says it is solved.
        final said = sent == null
            ? null
            : sent.containsKey('apr') && sent['apr'] == null
            ? l.debt_rateReleased
            : l.debt_updated;
        if (mounted) Navigator.of(context).pop(said);
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
    final open = widget.accounts.where((a) => !a.archived).toList();
    final accountId = _accountId;
    final billId = _billId;
    String? rate(String? typed) {
      if (_aprSolved) return null;
      final text = (typed ?? '').trim();
      if (text.isEmpty) return l.validation_required;
      return isTypedRate(text) ? null : l.debt_field_aprHint;
    }

    return SheetFrame(
      title: _editing ? l.debt_editTitle : l.debt_addTitle,
      busy: _busy,
      formKey: _form,
      error: _error,
      children: [
        TextFormField(
          controller: _name,
          maxLength: 60,
          decoration: InputDecoration(
            labelText: l.debt_field_name,
            helperText: l.debt_field_nameHint,
          ),
          validator: (value) => (value ?? '').trim().isEmpty ? l.validation_required : null,
        ),
        fieldGap,
        DropdownButtonFormField<DebtKind>(
          initialValue: _kind,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: l.debt_field_kind,
            helperText: l.debt_field_kindHint,
          ),
          items: [
            for (final kind in DebtKind.values)
              DropdownMenuItem(value: kind, child: Text(context.message(kind.messageKey))),
          ],
          onChanged: (kind) => setState(() => _kind = kind ?? _kind),
        ),
        fieldGap,
        TextFormField(
          controller: _servicer,
          maxLength: 80,
          decoration: InputDecoration(
            labelText: l.debt_field_servicer,
            helperText: l.debt_field_servicerHint,
          ),
        ),
        fieldGap,
        if (_editing)
          InputDecorator(
            decoration: InputDecoration(
              labelText: l.debt_field_balance,
              helperText: l.debt_terms_balanceNeverSolved,
              helperMaxLines: 4,
            ),
            child: Text(
              '${context.money(widget.debt!.money(widget.debt!.balance))} · '
              '${context.date(widget.debt!.balanceAsOf)}',
            ),
          )
        else ...[
          TextFormField(
            controller: _balance,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: l.debt_field_balance,
              helperText: l.debt_field_balanceHint(widget.currency),
            ),
            validator: (value) => amountProblem(context, value),
          ),
          fieldGap,
          InkWell(
            onTap: _pickDate,
            child: InputDecorator(
              decoration: InputDecoration(
                labelText: l.debt_field_balanceDate,
                helperText: l.debt_field_balanceDateHint,
                helperMaxLines: 4,
                suffixIcon: const Icon(Icons.calendar_today_outlined),
              ),
              child: Text(context.date(_balanceDate)),
            ),
          ),
        ],
        fieldGap,
        if (_editing)
          CheckboxListTile(
            value: _aprSolved,
            contentPadding: EdgeInsetsDirectional.zero,
            controlAffinity: ListTileControlAffinity.leading,
            title: Text(l.debt_field_aprSolved),
            subtitle: Text(
              widget.debt!.aprSource == TermSource.solved
                  ? l.debt_field_aprAlreadySolved
                  : l.debt_field_aprSolvedHint,
            ),
            onChanged: (on) => setState(() {
              _aprSolved = on ?? false;
              if (_aprSolved) _apr.clear();
            }),
          ),
        if (!_aprSolved) ...[
          TextFormField(
            controller: _apr,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: l.debt_field_apr,
              helperText: l.debt_field_aprHint,
              helperMaxLines: 3,
            ),
            validator: rate,
          ),
          fieldGap,
        ],
        TextFormField(
          controller: _minimum,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(
            labelText: l.debt_field_minimum,
            helperText: l.debt_field_minimumHint,
            helperMaxLines: 3,
          ),
          validator: (value) => amountProblem(context, value),
        ),
        fieldGap,
        TextFormField(
          controller: _original,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(
            labelText: l.debt_field_originalBalance,
            helperText: l.debt_field_originalBalanceHint,
            helperMaxLines: 3,
          ),
          validator: (value) => amountProblem(context, value, required: false),
        ),
        fieldGap,
        DropdownButtonFormField<String?>(
          initialValue: accountId,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: l.debt_field_account,
            helperText: l.debt_field_accountHint,
            helperMaxLines: 2,
          ),
          items: [
            // First, and named: a debt held nowhere this household tracks is ordinary.
            DropdownMenuItem<String?>(child: Text(l.debt_field_noAccount)),
            for (final a in open) DropdownMenuItem<String?>(value: a.id, child: Text(a.name)),
            if (accountId != null && open.every((a) => a.id != accountId))
              DropdownMenuItem<String?>(value: accountId, child: const Text('')),
          ],
          onChanged: (id) => setState(() {
            _accountId = id;
            // Naming an account hands it the currency: the account owns that fact.
            if (id != null) _currency.clear();
          }),
        ),
        if (!_editing && accountId == null) ...[
          fieldGap,
          TextFormField(
            controller: _currency,
            maxLength: 3,
            textCapitalization: TextCapitalization.characters,
            autocorrect: false,
            decoration: InputDecoration(
              labelText: l.debt_field_currency,
              hintText: l.debt_field_currencyDefault(widget.currency),
              helperText: l.debt_field_currencyHint,
            ),
            validator: (value) =>
                (value ?? '').trim().isEmpty ? null : Rules.currency(l, value ?? ''),
          ),
        ],
        fieldGap,
        DropdownButtonFormField<String?>(
          initialValue: billId,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: l.debt_field_bill,
            helperText: l.debt_field_billHint,
            helperMaxLines: 4,
          ),
          items: [
            DropdownMenuItem<String?>(child: Text(l.debt_field_noBill)),
            for (final b in widget.bills)
              DropdownMenuItem<String?>(value: b.id, child: Text(b.name)),
            if (billId != null && widget.bills.every((b) => b.id != billId))
              DropdownMenuItem<String?>(value: billId, child: const Text('')),
          ],
          onChanged: (id) => setState(() => _billId = id),
        ),
        fieldGap,
        FilledButton(
          onPressed: _busy || !online ? null : _save,
          child: Text(_busy ? l.common_saving : l.common_save),
        ),
        const OfflineNote(),
      ],
    );
  }
}
