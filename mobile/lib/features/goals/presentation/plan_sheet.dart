import 'dart:async';

import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/features/goals/data/goals_repository.dart';
import 'package:budget_owl/features/goals/domain/goal.dart';
import 'package:budget_owl/features/goals/domain/requests.dart';
import 'package:budget_owl/features/goals/presentation/goal_previews.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/year_month.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Change the monthly plan — *"from September, $165"* (goals.md, decision 4).
///
/// **From this month or next, never a past one**: raising the plan must not rewrite a month's
/// left-over figure that has already been read. "This month" is the server's month, as the goal
/// reports it, never the phone's guess. The preview is the server's, and only for a plan from this
/// month — the plan the preview projects; for next month the sheet says this month is unchanged.
class PlanSheet extends ConsumerStatefulWidget {
  const PlanSheet({required this.goal, super.key});

  final Goal goal;

  @override
  ConsumerState<PlanSheet> createState() => _PlanSheetState();
}

class _PlanSheetState extends ConsumerState<PlanSheet> {
  final _form = GlobalKey<FormState>();
  late final _amount = TextEditingController(text: trimAmount(widget.goal.monthlyContribution));
  final _preview = PreviewAsker();
  bool _nextMonth = false;
  AppError? _error;
  bool _busy = false;

  String get _thisMonth => widget.goal.thisPeriod.period;

  String get _followingMonth => nextMonth(_thisMonth);

  @override
  void initState() {
    super.initState();
    _amount.addListener(() => _preview.settle(_ask));
    scheduleMicrotask(_ask);
  }

  @override
  void dispose() {
    _preview.dispose();
    _amount.dispose();
    super.dispose();
  }

  /// A half-typed amount, or a plan from next month, asks nothing.
  void _ask() {
    if (!mounted) return;
    final amount = _amount.text.trim();
    final writer = ref.read(goalsWriterProvider);
    unawaited(
      _preview.ask(
        this,
        !_nextMonth && isTypedAmount(amount)
            ? () => writer.preview(widget.goal, {
                'monthlyAmount': amount,
              }, targetMonth: widget.goal.targetMonth)
            : null,
        // From this month the new amount replaces every later step: one amount throughout.
        PlanShape.oneAmount,
      ),
    );
  }

  Future<void> _save() async {
    if (_busy || !(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final from = _nextMonth ? _followingMonth : _thisMonth;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(goalsWriterProvider).setPlan(widget.goal, from, _amount.text);
      if (mounted) {
        Navigator.of(context).pop(l.goals_plan_saved(monthText(from, context.localeName)));
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
    final locale = context.localeName;
    return SheetFrame(
      title: l.goals_plan_title(widget.goal.name),
      busy: _busy,
      formKey: _form,
      error: _error,
      children: [
        TextFormField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(
            labelText: l.goals_plan_amount,
            helperText: l.goals_plan_amountHint,
          ),
          validator: (value) => amountProblem(context, value),
        ),
        fieldGap,
        Text(l.goals_plan_from, style: Theme.of(context).textTheme.labelLarge),
        RadioGroup<bool>(
          groupValue: _nextMonth,
          onChanged: (next) {
            setState(() => _nextMonth = next ?? false);
            _ask();
          },
          child: Column(
            children: [
              RadioListTile<bool>(
                value: false,
                contentPadding: EdgeInsetsDirectional.zero,
                title: Text(l.goals_plan_fromThis(monthText(_thisMonth, locale))),
              ),
              RadioListTile<bool>(
                value: true,
                contentPadding: EdgeInsetsDirectional.zero,
                title: Text(l.goals_plan_fromNext(monthText(_followingMonth, locale))),
              ),
            ],
          ),
        ),
        fieldGap,
        PreviewSentences(
          asker: _preview,
          replaced: _nextMonth
              ? l.goals_plan_thisMonthUnchanged(monthText(_thisMonth, locale))
              : null,
        ),
        fieldGap,
        FilledButton(
          onPressed: _busy || !online ? null : _save,
          child: Text(_busy ? l.common_saving : l.goals_plan_save),
        ),
        const OfflineNote(),
      ],
    );
  }
}
