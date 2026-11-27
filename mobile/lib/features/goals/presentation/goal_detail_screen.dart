import 'dart:async';

import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/debt/domain/plan_view.dart' show Formats;
import 'package:budget_owl/features/debt/presentation/debt_formats.dart';
import 'package:budget_owl/features/goals/data/goals_repository.dart';
import 'package:budget_owl/features/goals/domain/goal.dart';
import 'package:budget_owl/features/goals/domain/requests.dart';
import 'package:budget_owl/features/goals/presentation/contribution_sheet.dart';
import 'package:budget_owl/features/goals/presentation/goal_sheet.dart';
import 'package:budget_owl/features/goals/presentation/plan_sheet.dart';
import 'package:budget_owl/features/transactions/presentation/transactions_screen.dart'
    show canWriteNowFrom;
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/said.dart';
import 'package:budget_owl/shared/snapshots.dart';
import 'package:budget_owl/shared/widgets/goal_progress.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/year_month.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

/// One goal: how far along, the plan, and the history (goals.md, `/goals/{id}`). The route takes
/// the **id**: a goal's name is the most disclosing field this feature has, and a slug would put it
/// in history and logs.
///
/// - **The linked account's balance is the account's**, said as such beside the goal's own figure.
/// - **The starting amount is the history's first line**, labelled, and not a contribution row.
/// - **A withdrawal says so in words**, not only with a minus sign.
/// - **A `VIEWER` sees every figure and region and no control.**
class GoalDetailScreen extends ConsumerStatefulWidget {
  const GoalDetailScreen({required this.id, super.key});

  final String id;

  @override
  ConsumerState<GoalDetailScreen> createState() => _GoalDetailScreenState();
}

typedef _Page = ({Goal goal, PlanStepPage plan, ContributionPage history});

class _GoalDetailScreenState extends ConsumerState<GoalDetailScreen> {
  String? _notice;
  AppError? _actionError;
  bool _busy = false;

  /// Deleted: the page is leaving and reads nothing more.
  bool _gone = false;

  /// *Show more*'s later pages, kept only while the first page they follow is the one on screen:
  /// any re-read (a write, a pull to refresh) starts the history from its first page again, as the
  /// web's does.
  ContributionPage? _moreAfter;
  List<Contribution> _more = const [];
  int? _moreTotal;
  bool _loadingMore = false;

  /// A failed *Show more* — a read, so its message is the read's, never a write refusal.
  AppError? _moreError;

  Future<void> _showMore(ContributionPage first) async {
    if (_loadingMore) return;
    final shown = first.content.length + (identical(_moreAfter, first) ? _more.length : 0);
    setState(() {
      _loadingMore = true;
      _moreError = null;
    });
    try {
      final page = await ref.read(contributionsPageReader)(
        widget.id,
        shown ~/ contributionPageSize,
      );
      if (!mounted) return;
      setState(() {
        _more = [if (identical(_moreAfter, first)) ..._more, ...page.content];
        _moreAfter = first;
        _moreTotal = page.totalElements;
      });
    } on AppError catch (error) {
      if (mounted) setState(() => _moreError = error);
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  bool _offline() {
    if (canWriteNowFrom(ref)) return false;
    setState(() {
      _actionError = const AppError.offline();
      _notice = null;
    });
    return true;
  }

  void _said(String? notice) {
    if (notice != null && mounted) {
      setState(() {
        _notice = notice;
        _actionError = null;
      });
    }
  }

  Future<void> _sheet(Widget sheet) async {
    if (_offline()) return;
    setState(() {
      _notice = null;
      _actionError = null;
    });
    _said(await openSheet(context, sheet));
  }

  /// One write behind a confirmation, its failure kept on the page.
  Future<void> _run(Future<void> Function() write, String notice) async {
    if (_offline()) return;
    setState(() {
      _busy = true;
      _actionError = null;
      _notice = null;
    });
    try {
      await write();
      _said(notice);
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) setState(() => _actionError = error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _archive(Goal goal) async {
    final l = context.l10n;
    if (_offline()) return;
    final confirmed = await confirmAction(
      context,
      heading: l.goals_archive_heading(goal.name),
      message: l.goals_archive_body,
      action: l.goals_archive_confirm,
    );
    if (confirmed) {
      await _run(() => ref.read(goalsWriterProvider).archive(goal), l.goals_archive_done);
    }
  }

  Future<void> _delete(Goal goal) async {
    final l = context.l10n;
    if (_offline()) return;
    final confirmed = await confirmAction(
      context,
      heading: l.goals_delete_heading(goal.name),
      message: l.goals_delete_body,
      action: l.goals_delete_confirm,
      destructive: true,
    );
    if (!confirmed || !mounted) return;
    setState(() {
      _busy = true;
      _actionError = null;
    });
    try {
      await ref
          .read(goalsWriterProvider)
          .delete(
            goal,
            // The goal is gone: this page stops reading it before anything re-reads, so nothing
            // asks after a goal that no longer exists while the page animates away.
            leave: () async {
              if (!mounted) return;
              setState(() => _gone = true);
              context.go('/more/goals');
              await WidgetsBinding.instance.endOfFrame;
            },
          );
    } on AppError catch (error) {
      if (mounted) setState(() => _actionError = error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _remove(Goal goal, Contribution contribution) async {
    final l = context.l10n;
    if (_offline()) return;
    final confirmed = await confirmAction(
      context,
      heading: l.goals_removeContribution_heading(goal.name),
      message: l.goals_removeContribution_body,
      action: l.goals_removeContribution_confirm,
      destructive: true,
    );
    if (confirmed) {
      await _run(
        () => ref.read(goalsWriterProvider).removeContribution(goal, contribution),
        l.goals_removeContribution_done,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_gone) return const SizedBox.shrink();
    final l = context.l10n;
    final canWrite = ref.watch(signedInProvider)?.membership?.role.canWrite ?? false;
    final base = ref.watch(signedInProvider)?.membership?.baseCurrency ?? '';
    final goal = ref.watch(goalProvider(widget.id));
    final plan = ref.watch(goalPlanProvider(widget.id));
    final history = ref.watch(contributionsProvider(widget.id));
    final accounts = ref.watch(accountsProvider).data ?? const <Account>[];
    final page = combineSnapshots<_Page>([
      goal,
      plan,
      history,
    ], () => (goal: goal.data!, plan: plan.data!, history: history.data!));
    final error = _actionError;
    Future<void> refresh() => Future.wait([
      ref.read(goalProvider(widget.id).notifier).refresh(),
      ref.read(goalPlanProvider(widget.id).notifier).refresh(),
      ref.read(contributionsProvider(widget.id).notifier).refresh(),
    ]);

    return Screen(
      title: goal.data?.name ?? l.goals_title,
      leading: IconButton(
        tooltip: l.goals_backToAll,
        icon: const Icon(Icons.arrow_back),
        onPressed: () => context.go('/more/goals'),
      ),
      onRefresh: refresh,
      body: SnapshotView<_Page>(
        snapshot: page,
        onRetry: () => unawaited(refresh()),
        builder: (context, data, stale) {
          final g = data.goal;
          final f = formatsOf(context);
          final locale = context.localeName;
          Account? kept;
          for (final a in accounts) {
            if (a.id == g.accountId) kept = a;
          }
          // The plan's steps after this month: what is in force, and anything scheduled after it.
          final future = data.plan.content
              .where((s) => YearMonth.parse(s.fromPeriod) > YearMonth.parse(g.thisPeriod.period))
              .toList();
          final shape = future.isEmpty ? PlanShape.oneAmount : PlanShape.mayChange;
          final said = projectionSaid(
            g.facts,
            money: f.money,
            month: f.month,
            count: f.count,
            plan: shape,
          );
          final requiredReason = g.projection.requiredReason;
          final first = data.history;
          final paged = identical(_moreAfter, first);
          final rows = [...first.content, if (paged) ..._more];
          final total = paged ? _moreTotal ?? first.totalElements : first.totalElements;
          final text = Theme.of(context).textTheme;
          final c = context.colors;
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 1. The header: the month, where it is kept, its state, and the actions.
              Lede(
                [
                  if (g.targetMonth != null)
                    l.goals_detail_by(monthText(g.targetMonth, locale))
                  else
                    l.goals_detail_noMonth,
                  if (kept != null) l.goals_detail_keptIn(kept.name),
                ].join(' · '),
              ),
              if (g.archived || g.reached || g.overdue)
                Gutter(
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: g.archived
                        ? Tag(l.goals_archivedTag)
                        : g.reached
                        ? Tag(l.goals_reachedTag, strong: true)
                        : Tag(l.goals_overdueTag, strong: true),
                  ),
                ),
              if (canWrite)
                Gutter(
                  child: Wrap(
                    spacing: SpaceTokens.s2,
                    runSpacing: SpaceTokens.s1,
                    children: [
                      if (!g.archived) ...[
                        FilledButton(
                          onPressed: () => _sheet(
                            ContributionSheet(goal: g, direction: ContributionDirection.into),
                          ),
                          child: Text(l.goals_setAside),
                        ),
                        OutlinedButton(
                          onPressed: () => _sheet(
                            ContributionSheet(goal: g, direction: ContributionDirection.out),
                          ),
                          child: Text(l.goals_takeOut),
                        ),
                      ],
                      OutlinedButton(
                        onPressed: () => _sheet(
                          GoalSheet(
                            goal: g,
                            accounts: accounts,
                            baseCurrency: base,
                            planShape: shape,
                          ),
                        ),
                        child: Text(l.common_edit),
                      ),
                    ],
                  ),
                ),
              if (stale != null) Gutter(child: stale),
              if (_notice != null) Gutter(child: NoticeBanner(text: _notice!, success: true)),
              if (error != null)
                Gutter(
                  child: NoticeBanner(
                    icon: Icons.error_outline,
                    text: writeErrorText(context, ref.watch(reachabilityProvider), error),
                  ),
                ),
              // 2. Progress: the goal row, larger, and the account's balance as the account's.
              PlanCard(
                title: l.goals_detail_progress,
                children: [
                  GoalProgress(
                    name: g.name,
                    targetMonth: g.targetMonth,
                    saved: g.saved,
                    target: g.targetAmount,
                    currency: g.currency,
                    large: true,
                  ),
                  const SizedBox(height: SpaceTokens.s2),
                  Sentence(context.said(_remainingSaid(g, f)), style: text.titleSmall),
                  if (kept != null)
                    CardNote(l.goals_detail_accountBalance(kept.name, context.money(kept.balance))),
                ],
              ),
              // 3. The plan: in force, anything scheduled, and what it would take.
              PlanCard(
                title: l.goals_detail_plan,
                action: canWrite && !g.archived
                    ? OutlinedButton(
                        onPressed: () => _sheet(PlanSheet(goal: g)),
                        child: Text(l.goals_plan_change),
                      )
                    : null,
                children: [
                  Sentence(
                    l.goals_detail_monthly(
                      context.money(g.money(g.monthlyContribution)),
                      monthText(g.thisPeriod.period, locale),
                    ),
                    style: text.titleSmall,
                  ),
                  for (final step in future)
                    CardNote(
                      l.goals_detail_step(
                        context.money(Money.fromWire(step.monthlyAmount, step.currency)),
                        monthText(step.fromPeriod, locale),
                      ),
                    ),
                  const SizedBox(height: SpaceTokens.s2),
                  for (final s in said)
                    Padding(
                      padding: const EdgeInsetsDirectional.only(bottom: SpaceTokens.s1),
                      child: Sentence(context.said(s)),
                    ),
                  if (requiredReason != null && !g.reached)
                    CardNote(
                      context.said(
                        Said(requiredReasonKey(requiredReason), {
                          'target': monthText(g.targetMonth, locale),
                        }),
                      ),
                    ),
                  CardNote(
                    l.goals_detail_thisMonth(
                      monthText(g.thisPeriod.period, locale),
                      context.money(g.money(g.thisPeriod.planned)),
                      context.money(g.money(g.thisPeriod.recorded)),
                    ),
                  ),
                  CardNote(l.goals_countsTowardsLeft),
                  CardNote(l.goals_noInterest),
                ],
              ),
              // 4. History, in the server's order; the starting amount is its first line.
              PlanCard(
                title: l.goals_detail_history,
                children: [
                  if (signOf(g.startingAmount) != 0)
                    Sentence(
                      l.goals_detail_alreadySaved(context.money(g.money(g.startingAmount))),
                      style: text.titleSmall,
                    ),
                  if (rows.isEmpty)
                    CardNote(l.goals_detail_noHistory)
                  else
                    for (final row in rows)
                      Container(
                        padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
                        decoration: BoxDecoration(
                          border: Border(bottom: BorderSide(color: c.divider)),
                        ),
                        child: Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(context.date(row.date), style: text.titleSmall),
                                  if (row.note != null)
                                    Text(
                                      row.note!,
                                      style: text.bodySmall?.copyWith(color: c.muted),
                                    ),
                                ],
                              ),
                            ),
                            Column(
                              crossAxisAlignment: CrossAxisAlignment.end,
                              children: [
                                AmountText(row.money(), negativeIsBad: true),
                                if (row.withdrawal) Tag(l.goals_detail_withdrawal),
                              ],
                            ),
                            if (canWrite)
                              IconButton(
                                tooltip: '${l.goals_detail_remove} ${context.date(row.date)}',
                                icon: Icon(Icons.delete_outline, color: c.accentText),
                                onPressed: _busy ? null : () => _remove(g, row),
                              ),
                          ],
                        ),
                      ),
                  if (total > rows.length)
                    Padding(
                      padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
                      child: Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: OutlinedButton(
                          onPressed: _loadingMore ? null : () => _showMore(first),
                          child: Text(l.goals_detail_showMore),
                        ),
                      ),
                    ),
                  if (_moreError != null)
                    Padding(
                      padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
                      child: NoticeBanner(
                        icon: Icons.error_outline,
                        text: context.errorMessage(_moreError!),
                      ),
                    ),
                ],
              ),
              if (canWrite)
                Gutter(
                  child: Wrap(
                    spacing: SpaceTokens.s2,
                    runSpacing: SpaceTokens.s1,
                    children: [
                      if (g.archived)
                        OutlinedButton(
                          onPressed: _busy
                              ? null
                              : () => _run(
                                  () => ref.read(goalsWriterProvider).unarchive(g),
                                  l.goals_unarchived,
                                ),
                          child: Text(l.goals_unarchive),
                        )
                      else
                        OutlinedButton(
                          onPressed: _busy ? null : () => _archive(g),
                          child: Text(l.goals_archive_action),
                        ),
                      OutlinedButton(
                        style: OutlinedButton.styleFrom(
                          foregroundColor: c.accentText,
                          side: BorderSide(color: c.accentText),
                        ),
                        onPressed: _busy ? null : () => _delete(g),
                        child: Text(l.goals_delete_action),
                      ),
                    ],
                  ),
                ),
            ],
          );
        },
      ),
    );
  }

  /// "$2,610.00 to go." — or how far over a reached goal is, by dropping the sign from the
  /// server's own `remaining`; no arithmetic.
  Said _remainingSaid(Goal g, Formats f) {
    final sign = signOf(g.remaining) ?? 0;
    if (sign > 0) return Said('goals.detail.toGo', {'amount': f.money(g.remaining, g.currency)});
    if (sign < 0) {
      return Said('goals.detail.over', {'amount': f.money(unsigned(g.remaining), g.currency)});
    }
    return const Said('goals.detail.exactly');
  }
}
