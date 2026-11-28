import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/bills/data/bills_repository.dart';
import 'package:budget_owl/features/bills/domain/bill.dart';
import 'package:budget_owl/features/bills/domain/requests.dart';
import 'package:budget_owl/features/bills/presentation/schedule_fields.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Describe money arriving on a schedule (bills-and-income.md, "Income is a plan too").
///
/// **The amount is what arrives each time, not the month's total** — a fortnightly wage lands two
/// or three times and the server multiplies. There is no "source of truth" control: it says where a
/// figure came from, and a phone asserting its typed number was derived from history would lie.
class IncomeSheet extends ConsumerStatefulWidget {
  const IncomeSheet({required this.accounts, required this.currency, this.source, super.key});

  final IncomeSource? source;
  final List<Account> accounts;
  final String currency;

  @override
  ConsumerState<IncomeSheet> createState() => _IncomeSheetState();
}

class _IncomeSheetState extends ConsumerState<IncomeSheet> {
  final _form = GlobalKey<FormState>();
  late final TextEditingController _name = TextEditingController(text: widget.source?.name ?? '');
  late final TextEditingController _amount = TextEditingController(
    text: widget.source == null ? '' : trimAmount(widget.source!.amount),
  );
  late ScheduleForm _schedule = widget.source == null
      ? const ScheduleForm()
      : ScheduleForm.of(widget.source!.schedule);
  late String? _accountId = widget.source?.accountId;
  AppError? _error;
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final form = IncomeForm(
      name: _name.text,
      amount: _amount.text,
      schedule: _schedule,
      accountId: _accountId,
    );
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final writer = ref.read(billsWriterProvider);
      final source = widget.source;
      if (source == null) {
        await writer.createIncome(form);
        if (mounted) Navigator.of(context).pop(l.bills_income_created);
      } else {
        final sent = await writer.updateIncome(source, form);
        if (mounted) Navigator.of(context).pop(sent ? l.bills_income_updated : null);
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
    return SheetFrame(
      title: widget.source == null ? l.bills_income_addTitle : l.bills_income_editTitle,
      busy: _busy,
      formKey: _form,
      error: _error,
      children: [
        TextFormField(
          controller: _name,
          maxLength: 80,
          decoration: InputDecoration(
            labelText: l.bills_income_name,
            helperText: l.bills_income_nameHint,
          ),
          validator: (value) => (value ?? '').trim().isEmpty ? l.validation_required : null,
        ),
        fieldGap,
        TextFormField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(
            labelText: l.bills_income_amount,
            helperText: l.bills_income_amountHint(widget.currency),
            helperMaxLines: 3,
          ),
          validator: (value) => amountProblem(context, value),
        ),
        fieldGap,
        ScheduleFields(
          heading: l.bills_income_schedule,
          value: _schedule,
          onChanged: (next) => setState(() => _schedule = next),
        ),
        fieldGap,
        DropdownButtonFormField<String?>(
          initialValue: accountId,
          isExpanded: true,
          decoration: InputDecoration(labelText: l.bills_income_account),
          items: [
            DropdownMenuItem<String?>(child: Text(l.bills_income_noAccount)),
            for (final a in open) DropdownMenuItem<String?>(value: a.id, child: Text(a.name)),
            // A source landing in an account since archived keeps it, and is not changed by an
            // edit that does not touch it.
            if (accountId != null && open.every((a) => a.id != accountId))
              DropdownMenuItem<String?>(
                value: accountId,
                child: Text(widget.source?.accountName ?? ''),
              ),
          ],
          onChanged: (id) => setState(() => _accountId = id),
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
