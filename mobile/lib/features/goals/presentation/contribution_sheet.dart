import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/features/goals/data/goals_repository.dart';
import 'package:budget_owl/features/goals/domain/goal.dart';
import 'package:budget_owl/features/goals/domain/requests.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/year_month.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// *Set money aside*, or *Take money out* — one sheet, two directions (goals.md, detail).
///
/// - **Setting aside opens with this month's still-to-come**: one tap records the plan's amount.
/// - **Taking out is its own sheet**, not a minus sign typed into the first: the field is unsigned.
/// - **Recording writes no transaction and moves no account** (ADR-0035), and the sheet says so.
class ContributionSheet extends ConsumerStatefulWidget {
  const ContributionSheet({required this.goal, required this.direction, super.key});

  final Goal goal;
  final ContributionDirection direction;

  @override
  ConsumerState<ContributionSheet> createState() => _ContributionSheetState();
}

class _ContributionSheetState extends ConsumerState<ContributionSheet> {
  final _form = GlobalKey<FormState>();

  /// What is still to come this month, when setting aside and there is any.
  late final String? _stillToCome =
      widget.direction == ContributionDirection.into &&
          isPositiveWire(widget.goal.thisPeriod.stillToCome)
      ? widget.goal.thisPeriod.stillToCome
      : null;
  late final _amount = TextEditingController(
    text: _stillToCome == null ? '' : trimAmount(_stillToCome),
  );
  final _note = TextEditingController();
  late CalendarDate _date = CalendarDate.of(ref.read(clockProvider)());
  AppError? _error;
  bool _busy = false;

  bool get _into => widget.direction == ContributionDirection.into;

  @override
  void dispose() {
    _amount.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _date.forPicker,
      firstDate: DateTime(1970),
      lastDate: DateTime(2100),
    );
    if (picked != null) setState(() => _date = CalendarDate.of(picked));
  }

  Future<void> _save() async {
    if (_busy || !(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(goalsWriterProvider)
          .contribute(
            widget.goal,
            contributionBody(
              amount: _amount.text,
              date: _date,
              direction: widget.direction,
              note: _note.text,
            ),
          );
      if (mounted) {
        Navigator.of(context)
            .pop(_into ? l.goals_contribute_recordedIn : l.goals_contribute_recordedOut);
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
    final goal = widget.goal;
    final planned = _stillToCome;
    return SheetFrame(
      title: _into ? l.goals_contribute_inTitle(goal.name) : l.goals_contribute_outTitle(goal.name),
      busy: _busy,
      formKey: _form,
      error: _error,
      children: [
        if (planned != null) ...[
          Sentence(
            l.goals_contribute_prefilled(
              monthText(goal.thisPeriod.period, context.localeName),
              context.money(goal.money(planned)),
            ),
          ),
          fieldGap,
        ],
        TextFormField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(labelText: l.goals_contribute_amount),
          validator: (value) => amountProblem(context, value),
        ),
        fieldGap,
        InkWell(
          onTap: _pickDate,
          child: InputDecorator(
            decoration: InputDecoration(
              labelText: l.goals_contribute_date,
              suffixIcon: const Icon(Icons.calendar_today_outlined),
            ),
            child: Text(context.date(_date)),
          ),
        ),
        fieldGap,
        TextFormField(
          controller: _note,
          maxLength: 500,
          decoration: InputDecoration(labelText: l.goals_contribute_note),
        ),
        CardNote(l.goals_contribute_noLedger),
        fieldGap,
        FilledButton(
          onPressed: _busy || !online ? null : _save,
          child: Text(
            _busy
                ? l.common_saving
                : (_into ? l.goals_contribute_inSave : l.goals_contribute_outSave),
          ),
        ),
        const OfflineNote(),
      ],
    );
  }
}
