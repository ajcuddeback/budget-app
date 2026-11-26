import 'dart:async';

import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/goals/data/goals_repository.dart';
import 'package:budget_owl/features/goals/domain/goal.dart';
import 'package:budget_owl/features/goals/domain/requests.dart';
import 'package:budget_owl/features/goals/presentation/goal_previews.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/validation.dart';
import 'package:budget_owl/shared/year_month.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Add a goal, or edit one (goals.md, "Add a goal / Edit / Change the plan").
///
/// - **The server's preview, as the amounts settle**: a late answer is dropped, a half-typed amount
///   asks nothing, and a failed preview never blocks saving. The phone computes no projection.
/// - **"Already saved"** is the starting amount, asked for by name: money put by before tracking
///   began, never a contribution dated today (which would come off this month's left-over figure).
/// - **A linked account owns the currency**: the currency field is offered only when adding and no
///   account is chosen, because a goal's currency is fixed at creation.
class GoalSheet extends ConsumerStatefulWidget {
  const GoalSheet({
    required this.accounts,
    required this.baseCurrency,
    this.goal,
    this.planShape = PlanShape.oneAmount,
    super.key,
  });

  final Goal? goal;
  final List<Account> accounts;
  final String baseCurrency;

  /// The stored plan's shape, for an edit's preview sentence: an edit keeps the plan's steps.
  final PlanShape planShape;

  @override
  ConsumerState<GoalSheet> createState() => _GoalSheetState();
}

class _GoalSheetState extends ConsumerState<GoalSheet> {
  final _form = GlobalKey<FormState>();
  late final _name = TextEditingController(text: widget.goal?.name ?? '');
  late final _target = TextEditingController(
    text: widget.goal == null ? '' : trimAmount(widget.goal!.targetAmount),
  );
  late final _start = TextEditingController(
    text: widget.goal == null ? '' : trimAmount(widget.goal!.startingAmount),
  );
  final _monthly = TextEditingController();
  final _currency = TextEditingController();
  late String? _month = widget.goal?.targetMonth;
  late String? _accountId = widget.goal?.accountId;
  final _preview = PreviewAsker();
  AppError? _error;
  bool _busy = false;

  bool get _adding => widget.goal == null;

  @override
  void initState() {
    super.initState();
    for (final c in [_name, _target, _start, _monthly, _currency]) {
      c.addListener(_changed);
    }
    // An edit asks once on opening, with nothing changed.
    if (!_adding) scheduleMicrotask(_ask);
  }

  @override
  void dispose() {
    _preview.dispose();
    for (final c in [_name, _target, _start, _monthly, _currency]) {
      c.dispose();
    }
    super.dispose();
  }

  GoalForm get _value => GoalForm(
    name: _name.text,
    targetAmount: _target.text,
    targetMonth: _month,
    startingAmount: _start.text,
    monthlyContribution: _adding ? _monthly.text : '',
    currency: _adding && _accountId == null ? _currency.text : '',
    accountId: _accountId,
  );

  void _changed() => _preview.settle(_ask);

  void _ask() {
    if (!mounted) return;
    final goal = widget.goal;
    final form = _value;
    final writer = ref.read(goalsWriterProvider);
    if (goal == null) {
      final body = draftPreviewBody(form);
      unawaited(
        _preview.ask(
          this,
          body == null ? null : () => writer.previewDraft(body, targetMonth: form.targetMonth),
          PlanShape.oneAmount,
        ),
      );
    } else {
      final body = editPreviewBody(goal, form);
      unawaited(
        _preview.ask(
          this,
          body == null ? null : () => writer.preview(goal, body, targetMonth: form.targetMonth),
          widget.planShape,
        ),
      );
    }
  }

  Future<void> _pickMonth() async {
    final today = ref.read(clockProvider)();
    final picked = await pickMonth(
      context,
      initial: _month,
      from: YearMonth(today.year, today.month),
    );
    if (picked == null || !mounted) return;
    setState(() => _month = picked.isEmpty ? null : picked);
    _changed();
  }

  Future<void> _save() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final form = _value;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final writer = ref.read(goalsWriterProvider);
      final goal = widget.goal;
      if (goal == null) {
        await writer.create(form);
        if (mounted) Navigator.of(context).pop(l.goals_created(form.name.trim()));
      } else {
        final sent = await writer.update(goal, form);
        if (mounted) Navigator.of(context).pop(sent ? l.goals_updated : null);
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
    // Editing can only link an account in the goal's own currency.
    final choosable = widget.accounts
        .where(
          (a) =>
              (!a.archived || a.id == goal?.accountId) &&
              (goal == null || a.currency == goal.currency),
        )
        .toList();
    final month = _month;
    return SheetFrame(
      title: _adding ? l.goals_dialog_addTitle : l.goals_dialog_editTitle,
      busy: _busy,
      formKey: _form,
      error: _error,
      children: [
        TextFormField(
          controller: _name,
          maxLength: 80,
          decoration: InputDecoration(labelText: l.goals_field_name),
          validator: (value) => (value ?? '').trim().isEmpty ? l.validation_required : null,
        ),
        fieldGap,
        TextFormField(
          controller: _target,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(labelText: l.goals_field_target),
          validator: (value) => amountProblem(context, value),
        ),
        fieldGap,
        InkWell(
          onTap: _pickMonth,
          child: InputDecorator(
            decoration: InputDecoration(
              labelText: l.goals_field_month,
              helperText: l.goals_field_monthHint,
              helperMaxLines: 3,
              suffixIcon: const Icon(Icons.calendar_month_outlined),
            ),
            isEmpty: month == null,
            child: Text(month == null ? '' : monthText(month, context.localeName)),
          ),
        ),
        fieldGap,
        TextFormField(
          controller: _start,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          autocorrect: false,
          enableSuggestions: false,
          decoration: InputDecoration(
            labelText: l.goals_field_alreadySaved,
            helperText: l.goals_field_alreadySavedHint,
            helperMaxLines: 4,
          ),
          validator: (value) => amountProblem(context, value, required: false),
        ),
        if (_adding) ...[
          fieldGap,
          TextFormField(
            controller: _monthly,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: l.goals_field_monthly,
              helperText: l.goals_field_monthlyHint,
              helperMaxLines: 3,
            ),
            validator: (value) => amountProblem(context, value, required: false),
          ),
        ],
        fieldGap,
        DropdownButtonFormField<String?>(
          initialValue: _accountId,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: l.goals_field_account,
            helperText: l.goals_field_accountHint,
            helperMaxLines: 3,
          ),
          items: [
            DropdownMenuItem<String?>(child: Text(l.goals_field_noAccount)),
            for (final a in choosable) DropdownMenuItem<String?>(value: a.id, child: Text(a.name)),
          ],
          onChanged: (id) {
            setState(() {
              _accountId = id;
              if (id != null) _currency.clear();
            });
            _changed();
          },
        ),
        if (_adding && _accountId == null) ...[
          fieldGap,
          TextFormField(
            controller: _currency,
            maxLength: 3,
            textCapitalization: TextCapitalization.characters,
            autocorrect: false,
            decoration: InputDecoration(
              labelText: l.goals_field_currency,
              hintText: l.goals_field_currencyDefault(widget.baseCurrency),
              helperText: l.goals_field_currencyHint,
              helperMaxLines: 2,
            ),
            validator: (value) =>
                (value ?? '').trim().isEmpty ? null : Rules.currency(l, value ?? ''),
          ),
        ],
        fieldGap,
        PreviewSentences(asker: _preview),
        CardNote(l.goals_noInterest),
        fieldGap,
        FilledButton(
          onPressed: _busy || !online ? null : _save,
          child: Text(_busy ? l.common_saving : (_adding ? l.goals_add : l.common_save)),
        ),
        const OfflineNote(),
        const SizedBox(height: SpaceTokens.s4),
      ],
    );
  }
}
