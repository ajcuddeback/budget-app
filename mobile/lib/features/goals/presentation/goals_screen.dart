import 'dart:async';

import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/debt/presentation/debt_formats.dart';
import 'package:budget_owl/features/goals/data/goals_repository.dart';
import 'package:budget_owl/features/goals/domain/goal.dart';
import 'package:budget_owl/features/goals/domain/requests.dart';
import 'package:budget_owl/features/goals/presentation/goal_sheet.dart';
import 'package:budget_owl/features/transactions/presentation/transactions_screen.dart'
    show canWriteNowFrom;
import 'package:budget_owl/shared/said.dart';
import 'package:budget_owl/shared/widgets/goal_progress.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

/// Goals — what the household is saving for, and when each gets there (goals.md, `/goals`).
///
/// - **Grouped by currency only when there is more than one**, and **never totalled**: a sum of
///   savings goals is a number with nothing to act on.
/// - **Each row is the goal row** plus its projection in words; every reason has a sentence. A list
///   row cannot see the plan's steps, so its sentence names the plan, not one amount.
/// - **The empty state says what a goal is for**, never a $0 of $0 bar.
/// - **Archived goals sit behind a toggle**, muted, and are said to be out of this month's plan.
/// - **A `VIEWER` sees every figure and no control.**
class GoalsScreen extends ConsumerStatefulWidget {
  const GoalsScreen({super.key});

  @override
  ConsumerState<GoalsScreen> createState() => _GoalsScreenState();
}

class _GoalsScreenState extends ConsumerState<GoalsScreen> {
  bool _showArchived = false;
  String? _notice;
  AppError? _actionError;

  Future<void> _add() async {
    if (!canWriteNowFrom(ref)) {
      setState(() => _actionError = const AppError.offline());
      return;
    }
    final base = ref.read(signedInProvider)?.membership?.baseCurrency ?? '';
    setState(() {
      _notice = null;
      _actionError = null;
    });
    final said = await openSheet(
      context,
      GoalSheet(accounts: ref.read(accountsProvider).data ?? const [], baseCurrency: base),
    );
    if (said != null && mounted) setState(() => _notice = said);
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final canWrite = ref.watch(signedInProvider)?.membership?.role.canWrite ?? false;
    final goals = ref.watch(goalsProvider);
    ref.watch(accountsProvider);
    final error = _actionError;
    return Screen(
      title: l.goals_title,
      onRefresh: () => ref.read(goalsProvider.notifier).refresh(),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.goals_lede),
          if (_notice != null) Gutter(child: NoticeBanner(text: _notice!, success: true)),
          if (error != null)
            Gutter(
              child: NoticeBanner(
                icon: Icons.error_outline,
                text: writeErrorText(context, ref.watch(reachabilityProvider), error),
              ),
            ),
          SnapshotView<GoalPage>(
            snapshot: goals,
            onRetry: () => unawaited(ref.read(goalsProvider.notifier).refresh()),
            builder: (context, page, stale) {
              final active = page.content.where((g) => !g.archived).toList();
              final archived = page.content.where((g) => g.archived).toList();
              final groups = <String, List<Goal>>{};
              for (final g in active) {
                groups.putIfAbsent(g.currency, () => []).add(g);
              }
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (stale != null) Gutter(child: stale),
                  if (active.isEmpty)
                    PlanCard(
                      title: l.goals_empty_heading,
                      children: [
                        Sentence(l.goals_empty_body),
                        const SizedBox(height: SpaceTokens.s3),
                        if (canWrite)
                          Align(
                            alignment: AlignmentDirectional.centerStart,
                            child: FilledButton(onPressed: _add, child: Text(l.goals_add)),
                          )
                        else
                          CardNote(l.goals_emptyReadOnly),
                      ],
                    )
                  else ...[
                    if (canWrite)
                      Gutter(
                        child: Align(
                          alignment: AlignmentDirectional.centerStart,
                          child: FilledButton(onPressed: _add, child: Text(l.goals_add)),
                        ),
                      ),
                    for (final MapEntry(key: currency, value: rows) in groups.entries)
                      PlanCard(
                        // One group gets no heading: a currency over an all-one-currency list is noise.
                        title: groups.length > 1
                            ? l.goals_currencyGroup(currency)
                            : l.goals_listLabel,
                        children: [for (final goal in rows) _GoalRow(goal: goal, projected: true)],
                      ),
                    Gutter(child: CardNote(l.goals_countsTowardsLeft)),
                  ],
                  if (archived.isNotEmpty) ...[
                    Gutter(
                      child: Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: TextButton(
                          onPressed: () => setState(() => _showArchived = !_showArchived),
                          child: Text(
                            _showArchived ? l.goals_archived_hide : l.goals_archived_show,
                          ),
                        ),
                      ),
                    ),
                    if (_showArchived)
                      PlanCard(
                        title: l.goals_archived_heading,
                        children: [
                          CardNote(l.goals_archived_body),
                          for (final goal in archived) _GoalRow(goal: goal, projected: false),
                        ],
                      ),
                  ],
                ],
              );
            },
          ),
        ],
      ),
    );
  }
}

/// One goal: the goal row, its projection in words, and Open.
class _GoalRow extends StatelessWidget {
  const _GoalRow({required this.goal, required this.projected});

  final Goal goal;

  /// Archived rows carry no projection: they are out of the plan.
  final bool projected;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final f = formatsOf(context);
    final said = projected
        ? projectionSaid(
            goal.facts,
            money: f.money,
            month: f.month,
            count: f.count,
            plan: PlanShape.mayChange,
          )
        : const <Said>[];
    return Opacity(
      opacity: goal.archived ? 0.75 : 1,
      child: Container(
        padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s3),
        decoration: BoxDecoration(
          border: Border(bottom: BorderSide(color: context.colors.divider)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            GoalProgress(
              name: goal.name,
              targetMonth: goal.targetMonth,
              saved: goal.saved,
              target: goal.targetAmount,
              currency: goal.currency,
            ),
            for (final s in said) CardNote(context.said(s)),
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: TextButton(
                onPressed: () => context.go('/more/goals/${Uri.encodeComponent(goal.id)}'),
                // The web's "Open →" — but the bundled Figtree has no U+2192 (it drew as a box),
                // so the phone says "Open ›", and names the goal to a screen reader.
                child: Semantics(
                  label: l.goals_openNamed(goal.name),
                  excludeSemantics: true,
                  child: Text(l.goals_openTrail),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
