import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/features/bills/data/bills_repository.dart';
import 'package:budget_owl/features/bills/domain/bill.dart';
import 'package:budget_owl/features/bills/domain/requests.dart';
import 'package:budget_owl/features/bills/presentation/schedule_fields.dart';
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Set a bill up, or change your mind about one (bills-and-income.md, UI).
///
/// - **Amounts are text with a decimal keyboard**, sent as typed — never a number field (ADR-0006).
/// - **Changing the schedule's kind clears the parameters that stopped applying.**
/// - **A tolerance appears only when the amount varies**, and the two are sent together.
/// - **An edit sends only what changed**; an untouched sheet sends nothing.
class BillSheet extends ConsumerStatefulWidget {
  const BillSheet({required this.categories, required this.currency, this.bill, super.key});

  final Bill? bill;
  final List<Category> categories;
  final String currency;

  @override
  ConsumerState<BillSheet> createState() => _BillSheetState();
}

class _BillSheetState extends ConsumerState<BillSheet> {
  final _form = GlobalKey<FormState>();
  late final TextEditingController _name = TextEditingController(text: widget.bill?.name ?? '');
  late final TextEditingController _amount = TextEditingController(
    text: widget.bill == null ? '' : trimAmount(widget.bill!.expectedAmount),
  );
  late final TextEditingController _tolerance = TextEditingController(
    text: widget.bill?.tolerance == null ? '' : trimAmount(widget.bill!.tolerance!),
  );
  late ScheduleForm _schedule = widget.bill == null
      ? const ScheduleForm()
      : ScheduleForm.of(widget.bill!.schedule);
  late String? _categoryId = widget.bill?.categoryId;
  late bool _varies = widget.bill?.amountVaries ?? false;
  AppError? _error;
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    _amount.dispose();
    _tolerance.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final form = BillForm(
      name: _name.text,
      expectedAmount: _amount.text,
      schedule: _schedule,
      categoryId: _categoryId,
      amountVaries: _varies,
      tolerance: _varies ? _tolerance.text : '',
    );
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final writer = ref.read(billsWriterProvider);
      final bill = widget.bill;
      if (bill == null) {
        await writer.createBill(form);
        if (mounted) Navigator.of(context).pop(l.bills_created);
      } else {
        final sent = await writer.updateBill(bill, form);
        if (mounted) Navigator.of(context).pop(sent ? l.bills_updated : null);
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
    final choosable = pickableCategories(widget.categories);
    final categoryId = _categoryId;
    return SheetFrame(
      title: widget.bill == null ? l.bills_addTitle : l.bills_editTitle,
      busy: _busy,
      formKey: _form,
      error: _error,
      children: [
        TextFormField(
          controller: _name,
          maxLength: 80,
          decoration: InputDecoration(labelText: l.bills_name, helperText: l.bills_nameHint),
          validator: (value) => (value ?? '').trim().isEmpty ? l.validation_required : null,
        ),
        fieldGap,
        TextFormField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(
            labelText: l.bills_expectedAmount,
            helperText: l.bills_expectedAmountHint(widget.currency),
          ),
          validator: (value) => amountProblem(context, value),
        ),
        fieldGap,
        ScheduleFields(
          heading: l.bills_schedule,
          value: _schedule,
          onChanged: (next) => setState(() => _schedule = next),
        ),
        fieldGap,
        DropdownButtonFormField<String?>(
          initialValue: categoryId,
          isExpanded: true,
          decoration: InputDecoration(labelText: l.bills_category),
          items: [
            // First, and named: a bill with no category is ordinary, not a missing choice.
            DropdownMenuItem<String?>(child: Text(l.bills_noCategory)),
            for (final c in choosable)
              DropdownMenuItem<String?>(
                value: c.id,
                child: Text(
                  c.parentName == null
                      ? c.name
                      : l.transactions_categoryTrail(c.parentName!, c.name),
                ),
              ),
            if (categoryId != null && choosable.every((c) => c.id != categoryId))
              DropdownMenuItem<String?>(
                value: categoryId,
                child: Text(widget.bill?.categoryName ?? ''),
              ),
          ],
          onChanged: (id) => setState(() => _categoryId = id),
        ),
        fieldGap,
        CheckboxListTile(
          value: _varies,
          contentPadding: EdgeInsetsDirectional.zero,
          controlAffinity: ListTileControlAffinity.leading,
          title: Text(l.bills_amountVaries),
          subtitle: Text(l.bills_amountVariesHint),
          onChanged: (on) => setState(() {
            _varies = on ?? false;
            if (!_varies) _tolerance.clear();
          }),
        ),
        if (_varies) ...[
          fieldGap,
          TextFormField(
            controller: _tolerance,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: l.bills_tolerance,
              helperText: l.bills_toleranceHint(widget.currency),
              helperMaxLines: 4,
            ),
            validator: (value) => amountProblem(context, value),
          ),
        ],
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
