import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/debt/data/debt_repository.dart';
import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/features/debt/domain/requests.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart' show NumberFormat;
import 'package:material_ui/material_ui.dart';

/// What the confirm sheet hands back: a notice after a confirmation, or a request to log a
/// payment instead.
sealed class ConfirmOutcome {
  const ConfirmOutcome();
}

final class Confirmed extends ConfirmOutcome {
  const Confirmed(this.notice);
  final String notice;
}

final class LogInstead extends ConfirmOutcome {
  const LogInstead();
}

/// Confirm the estimate, or correct it (debt-plan.md, "The three ways a balance moves").
///
/// `ESTIMATED` is the one place the app puts a figure it extrapolated in the headline position, so
/// this sheet exists to get a real one. It says **what the estimate assumed** — the months, and that
/// interest was added in between. Confirming writes an anchor at today's date. **"Stop estimating"
/// re-anchors on the last figure the household itself confirmed**, through the same endpoint, so it
/// is still a confirmation with provenance. Nothing here computes a balance.
class ConfirmEstimateSheet extends ConsumerStatefulWidget {
  const ConfirmEstimateSheet({required this.debt, super.key});

  final Debt debt;

  @override
  ConsumerState<ConfirmEstimateSheet> createState() => _ConfirmEstimateSheetState();
}

class _ConfirmEstimateSheetState extends ConsumerState<ConfirmEstimateSheet> {
  final _form = GlobalKey<FormState>();
  late final CalendarDate _today = CalendarDate.of(ref.read(clockProvider)());
  late final _balance = TextEditingController(text: trimAmount(widget.debt.balance));
  late CalendarDate _balanceDate = _today;
  bool _correcting = false;
  AppError? _error;
  bool _busy = false;

  @override
  void dispose() {
    _balance.dispose();
    super.dispose();
  }

  Future<void> _send(String balance, CalendarDate date) async {
    if (_busy) return;
    final l = context.l10n;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(debtWriterProvider)
          .confirmBalance(widget.debt, confirmBalanceBody(balance: balance, balanceDate: date));
      if (mounted) Navigator.of(context).pop(Confirmed(l.debt_balanceConfirmed));
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _submit() async {
    if (_correcting && !(_form.currentState?.validate() ?? false)) return;
    await _send(_correcting ? _balance.text : trimAmount(widget.debt.balance), _balanceDate);
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

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final online = canWriteNow(ref);
    final debt = widget.debt;
    final stale = debt.balanceStale;
    final text = Theme.of(context).textTheme;
    final months = NumberFormat.decimalPattern(context.localeName).format(debt.estimatedMonths);
    final anchor = context.money(debt.money(debt.anchorBalance));
    final ready = !_busy && online;
    return SheetFrame(
      title: stale ? l.debt_estimate_staleTitle : l.debt_estimate_title,
      busy: _busy,
      formKey: _form,
      error: _error,
      children: [
        // Labelled: a figure alone at the top of a sheet is a number nobody can place.
        AmountText(debt.money(debt.balance), style: text.headlineMedium),
        Text(
          stale ? l.debt_estimate_figureStale : l.debt_estimate_figureEstimate,
          style: text.bodyMedium?.copyWith(color: context.colors.muted),
        ),
        CardNote(
          stale
              ? l.debt_estimate_staleBody(months, anchor)
              : l.debt_estimate_assumed(months, context.money(debt.money(debt.minimumPayment))),
        ),
        CardNote(l.debt_estimate_lastConfirmed(anchor, context.date(debt.anchorDate))),
        if (_correcting) ...[
          fieldGap,
          TextFormField(
            controller: _balance,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: l.debt_estimate_balance,
              helperText: l.debt_estimate_balanceHint,
            ),
            validator: (value) => amountProblem(context, value),
          ),
          fieldGap,
          InkWell(
            onTap: _pickDate,
            child: InputDecorator(
              decoration: InputDecoration(
                labelText: l.debt_estimate_balanceDate,
                suffixIcon: const Icon(Icons.calendar_today_outlined),
              ),
              child: Text(context.date(_balanceDate)),
            ),
          ),
        ],
        // A statement that disagrees is recorded as a correction, never by adjusting history.
        CardNote(l.debt_estimate_correctionNote),
        fieldGap,
        if (_correcting)
          FilledButton(
            onPressed: ready ? _submit : null,
            child: Text(_busy ? l.common_saving : l.debt_estimate_save),
          )
        else ...[
          FilledButton(
            onPressed: ready ? _submit : null,
            child: Text(
              _busy
                  ? l.common_saving
                  : l.debt_estimate_confirmAmount(context.money(debt.money(debt.balance))),
            ),
          ),
          const SizedBox(height: SpaceTokens.s2),
          OutlinedButton(
            onPressed: _busy ? null : () => setState(() => _correcting = true),
            child: Text(l.debt_estimate_correct),
          ),
        ],
        const OfflineNote(),
        const SizedBox(height: SpaceTokens.s3),
        // The two quieter answers beneath the main pair.
        TextButton(
          onPressed: ready ? () => _send(debt.anchorBalance, _today) : null,
          child: Text(l.debt_estimate_stopEstimating, textAlign: TextAlign.center),
        ),
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(const LogInstead()),
          child: Text(l.debt_estimate_logInstead),
        ),
      ],
    );
  }
}
