import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/features/bills/data/bills_repository.dart';
import 'package:budget_owl/features/bills/domain/bill.dart';
import 'package:budget_owl/features/bills/domain/requests.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Record a payment that was not simply "what was budgeted, today" (bills-and-income.md, UI).
///
/// **Planned and actual are both kept**: this asks for the real figure and the date it went.
/// **The due date is a field only for an `IRREGULAR` bill**, whose occurrence this record brings
/// into being (ADR-0032); for every other bill it is the row that was tapped, because a date the
/// schedule does not imply is a `404`.
class RecordPaymentSheet extends ConsumerStatefulWidget {
  const RecordPaymentSheet({required this.bill, this.occurrence, super.key});

  final Bill bill;

  /// The occurrence being recorded against, or null for an irregular bill's new one.
  final BillOccurrence? occurrence;

  @override
  ConsumerState<RecordPaymentSheet> createState() => _RecordPaymentSheetState();
}

class _RecordPaymentSheetState extends ConsumerState<RecordPaymentSheet> {
  final _form = GlobalKey<FormState>();
  late final CalendarDate _today = CalendarDate.of(ref.read(clockProvider)());
  late CalendarDate _dueDate = widget.occurrence?.dueDate ?? _today;
  late CalendarDate _paidDate = widget.occurrence?.paidDate ?? _today;

  /// Defaulted to what was budgeted — the figure most people are confirming — written the way
  /// somebody about to edit it would write it.
  late final TextEditingController _amount = TextEditingController(
    text: switch (widget.occurrence) {
      final BillOccurrence o => trimAmount(o.actualAmount ?? o.expectedAmount),
      null => '',
    },
  );
  AppError? _error;
  bool _busy = false;

  bool get _choosesDate => widget.occurrence == null;

  @override
  void dispose() {
    _amount.dispose();
    super.dispose();
  }

  /// [last] is the latest date offered. A due date may be ahead (a bill paid early); a paid date
  /// stops at the phone's own today, which the server always accepts and nothing later than today
  /// anywhere on Earth (bills-and-income.md, The paid date).
  Future<CalendarDate?> _pick(CalendarDate current, {DateTime? last}) async {
    final lastDate = last ?? DateTime(2100);
    final initial = current.forPicker;
    final picked = await showDatePicker(
      context: context,
      initialDate: initial.isAfter(lastDate) ? lastDate : initial,
      firstDate: DateTime(1970),
      lastDate: lastDate,
    );
    return picked == null ? null : CalendarDate.of(picked);
  }

  Future<void> _save() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(billsWriterProvider)
          .record(
            widget.bill.id,
            widget.occurrence?.dueDate ?? _dueDate,
            paidBody(actualAmount: _amount.text, paidDate: _paidDate),
          );
      if (mounted) Navigator.of(context).pop(l.bills_markedPaid);
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
    final occurrence = widget.occurrence;
    final prefill = occurrence?.actualAmount ?? occurrence?.expectedAmount;
    // Whether there is a budgeted figure filled in: the hint must not promise one it has not got.
    final hint = prefill != null && (signOf(prefill) ?? 0) != 0
        ? l.bills_actualAmountHint
        : l.bills_actualAmountHintBlank;
    return SheetFrame(
      title: _choosesDate
          ? l.bills_recordTitle(widget.bill.name)
          : l.bills_paidTitle(widget.bill.name),
      busy: _busy,
      formKey: _form,
      error: _error,
      children: [
        if (_choosesDate) ...[
          InkWell(
            onTap: () async {
              final picked = await _pick(_dueDate);
              if (picked != null) setState(() => _dueDate = picked);
            },
            child: InputDecorator(
              decoration: InputDecoration(
                labelText: l.bills_dueDate,
                helperText: l.bills_dueDateHint,
                helperMaxLines: 3,
                suffixIcon: const Icon(Icons.calendar_today_outlined),
              ),
              child: Text(context.date(_dueDate)),
            ),
          ),
          fieldGap,
        ],
        TextFormField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(
            labelText: l.bills_actualAmount,
            helperText: hint,
            helperMaxLines: 3,
          ),
          validator: (value) => amountProblem(context, value),
        ),
        fieldGap,
        InkWell(
          onTap: () async {
            final picked = await _pick(_paidDate, last: _today.forPicker);
            if (picked != null) setState(() => _paidDate = picked);
          },
          child: InputDecorator(
            decoration: InputDecoration(
              labelText: l.bills_paidDate,
              suffixIcon: const Icon(Icons.calendar_today_outlined),
            ),
            child: Text(context.date(_paidDate)),
          ),
        ),
        fieldGap,
        FilledButton(
          onPressed: _busy || !online ? null : _save,
          child: Text(_busy ? l.common_saving : l.bills_recordIt),
        ),
        const OfflineNote(),
      ],
    );
  }
}
