import 'dart:async';

import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/bills/data/bills_repository.dart';
import 'package:budget_owl/features/bills/domain/bill.dart';
import 'package:budget_owl/features/debt/data/debt_repository.dart';
import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/features/debt/domain/detail_view.dart';
import 'package:budget_owl/features/debt/domain/plan_view.dart';
import 'package:budget_owl/features/debt/presentation/confirm_estimate_sheet.dart';
import 'package:budget_owl/features/debt/presentation/debt_formats.dart';
import 'package:budget_owl/features/debt/presentation/debt_sheet.dart';
import 'package:budget_owl/features/debt/presentation/log_payment_sheet.dart';
import 'package:budget_owl/features/transactions/presentation/transactions_screen.dart'
    show canWriteNowFrom;
import 'package:budget_owl/shared/said.dart';
import 'package:budget_owl/shared/snapshots.dart';
import 'package:budget_owl/shared/widgets/months_line_chart.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

/// One debt: what its terms are, and what has actually been paid against it (debt-plan.md,
/// `/debt-plan/{id}`). The route takes the **id**, never a slug: a slug would leak the name.
///
/// One region order: **terms → balance → chart → history**, the web's at both viewports, with the
/// mobile artboard's stepper between debts. Three labels on the terms, not two; the balance never
/// solved; the at-minimum line named as the comparison; every payment row saying how it got here,
/// in the server's order; and a `VIEWER` with every figure and no control.
class DebtDetailScreen extends ConsumerStatefulWidget {
  const DebtDetailScreen({required this.id, super.key});

  final String id;

  @override
  ConsumerState<DebtDetailScreen> createState() => _DebtDetailScreenState();
}

typedef _Detail = ({DebtDetail detail, DebtPaymentPage payments});

class _DebtDetailScreenState extends ConsumerState<DebtDetailScreen> {
  String? _notice;
  AppError? _actionError;

  bool _offline() {
    if (canWriteNowFrom(ref)) return false;
    setState(() {
      _actionError = const AppError.offline();
      _notice = null;
    });
    return true;
  }

  List<Account> get _accounts => ref.read(accountsProvider).data ?? const [];

  Future<void> _edit(Debt debt) async {
    if (_offline()) return;
    final bills = ref.read(billsProvider(_activeBills(ref))).data?.content ?? const <Bill>[];
    _said(
      await openSheet(
        context,
        DebtSheet(debt: debt, accounts: _accounts, bills: bills, currency: debt.currency),
      ),
    );
  }

  Future<void> _log(Debt debt) async {
    if (_offline()) return;
    _said(await openSheet(context, LogPaymentSheet(debt: debt, accounts: _accounts)));
  }

  Future<void> _confirm(Debt debt) async {
    if (_offline()) return;
    setState(() => _notice = null);
    final outcome = await Navigator.of(context, rootNavigator: true).push<ConfirmOutcome>(
      MaterialPageRoute(fullscreenDialog: true, builder: (_) => ConfirmEstimateSheet(debt: debt)),
    );
    if (!mounted) return;
    switch (outcome) {
      case Confirmed(:final notice):
        _said(notice);
      // One sheet hands over to the other without leaving the page.
      case LogInstead():
        await _log(debt);
      case null:
        break;
    }
  }

  void _said(String? notice) {
    if (notice != null && mounted) {
      setState(() {
        _notice = notice;
        _actionError = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final canWrite = ref.watch(signedInProvider)?.membership?.role.canWrite ?? false;
    final detail = ref.watch(debtDetailProvider(widget.id));
    final payments = ref.watch(debtPaymentsProvider(widget.id));
    // The siblings, for the stepper; the accounts, for "paid from"; the bills, for the edit sheet.
    final siblings = ref.watch(debtsProvider).data;
    final accounts = ref.watch(accountsProvider).data ?? const <Account>[];
    ref.watch(billsProvider(_activeBills(ref)));
    final page = combineSnapshots<_Detail>([
      detail,
      payments,
    ], () => (detail: detail.data!, payments: payments.data!));
    final error = _actionError;
    final debt = detail.data?.debt;

    return Screen(
      title: debt?.name ?? l.debt_title,
      leading: IconButton(
        tooltip: l.debt_detail_back,
        icon: const Icon(Icons.arrow_back),
        onPressed: () => context.go('/more/debt-plan'),
      ),
      onRefresh: () => Future.wait([
        ref.read(debtDetailProvider(widget.id).notifier).refresh(),
        ref.read(debtPaymentsProvider(widget.id).notifier).refresh(),
      ]),
      body: SnapshotView<_Detail>(
        snapshot: page,
        onRetry: () => unawaited(
          Future.wait([
            ref.read(debtDetailProvider(widget.id).notifier).refresh(),
            ref.read(debtPaymentsProvider(widget.id).notifier).refresh(),
          ]),
        ),
        builder: (context, data, stale) => _DetailBody(
          detail: data.detail,
          payments: data.payments,
          siblings: siblings,
          accounts: accounts,
          stale: stale,
          canWrite: canWrite,
          notice: _notice,
          errorText: error == null
              ? null
              : writeErrorText(context, ref.watch(reachabilityProvider), error),
          onLog: () => _log(data.detail.debt),
          onEdit: () => _edit(data.detail.debt),
          onConfirm: () => _confirm(data.detail.debt),
        ),
      ),
    );
  }
}

BillsQuery _activeBills(WidgetRef ref) =>
    BillsQuery(currentPeriod(ref.read(clockProvider)()), includeInactive: false);

class _DetailBody extends StatelessWidget {
  const _DetailBody({
    required this.detail,
    required this.payments,
    required this.siblings,
    required this.accounts,
    required this.stale,
    required this.canWrite,
    required this.notice,
    required this.errorText,
    required this.onLog,
    required this.onEdit,
    required this.onConfirm,
  });

  final DebtDetail detail;
  final DebtPaymentPage payments;
  final DebtList? siblings;
  final List<Account> accounts;
  final Widget? stale;
  final bool canWrite;
  final String? notice;
  final String? errorText;
  final VoidCallback onLog;
  final VoidCallback onEdit;
  final VoidCallback onConfirm;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final f = formatsOf(context);
    final debt = detail.debt;
    final text = Theme.of(context).textTheme;
    final c = context.colors;
    final failure = errorText;

    // The household's debts in rung order, for the stepper. A debt outside the ladder has no
    // position to step through.
    final ladder = [...?siblings?.debts.where((d) => d.rung != null)]
      ..sort((a, b) => a.rung!.compareTo(b.rung!));
    final at = ladder.indexWhere((d) => d.id == debt.id);
    final previous = at > 0 ? ladder[at - 1] : null;
    final next = at != -1 && at < ladder.length - 1 ? ladder[at + 1] : null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Lede(context.said(namingOf(debt, f))),
        if (previous != null || next != null)
          Gutter(
            child: Semantics(
              container: true,
              label: l.debt_detail_switcher,
              child: Wrap(
                spacing: SpaceTokens.s2,
                runSpacing: SpaceTokens.s1,
                children: [
                  if (previous != null)
                    OutlinedButton(
                      onPressed: () =>
                          context.go('/more/debt-plan/${Uri.encodeComponent(previous.id)}'),
                      child: Text(l.debt_detail_previous(previous.name)),
                    ),
                  if (next != null)
                    OutlinedButton(
                      onPressed: () =>
                          context.go('/more/debt-plan/${Uri.encodeComponent(next.id)}'),
                      child: Text(l.debt_detail_next(next.name)),
                    ),
                ],
              ),
            ),
          ),
        if (canWrite)
          Gutter(
            child: Wrap(
              spacing: SpaceTokens.s2,
              runSpacing: SpaceTokens.s1,
              children: [
                FilledButton(onPressed: onLog, child: Text(l.debt_detail_logPayment)),
                OutlinedButton(onPressed: onEdit, child: Text(l.debt_editTerms)),
                if (debt.estimated)
                  OutlinedButton(onPressed: onConfirm, child: Text(l.debt_detail_confirmBalance)),
              ],
            ),
          ),
        if (stale != null) Gutter(child: stale!),
        if (notice != null) Gutter(child: NoticeBanner(text: notice!, success: true)),
        if (failure != null)
          Gutter(
            child: NoticeBanner(icon: Icons.error_outline, text: failure),
          ),
        _TermsCard(debt: debt, f: f),
        // 2. Balance now.
        PlanCard(
          title: l.debt_detail_balanceHeading,
          children: [
            AmountText(debt.money(debt.balance), style: text.headlineMedium),
            Text(
              context.message(balanceLabelKey(debt)),
              style: text.bodyMedium?.copyWith(
                color: debt.estimated ? c.accentText : c.muted,
                fontStyle: debt.estimated ? FontStyle.italic : null,
              ),
            ),
            const SizedBox(height: SpaceTokens.s2),
            switch (progressOf(debt, f)) {
              final Said progress => Sentence(context.said(progress)),
              null => CardNote(l.debt_noProgress),
            },
            if (debt.estimated && canWrite) ...[
              const SizedBox(height: SpaceTokens.s3),
              Align(
                alignment: AlignmentDirectional.centerStart,
                child: OutlinedButton(
                  onPressed: onConfirm,
                  child: Text(l.debt_detail_confirmBalance),
                ),
              ),
            ],
          ],
        ),
        _OverTimeCard(detail: detail, f: f),
        _HistoryCard(
          debt: debt,
          payments: payments,
          accounts: accounts,
          canWrite: canWrite,
          f: f,
          onLog: onLog,
        ),
      ],
    );
  }
}

/// 1. Loan terms — the solver panel, with its own count and its promise. Unmarked cells are the
/// household's word; `Solved` is the app's arithmetic; *from your plan* is the ladder's allocation.
class _TermsCard extends StatelessWidget {
  const _TermsCard({required this.debt, required this.f});

  final Debt debt;
  final Formats f;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final cells = termsOf(debt, f);
    return PlanCard(
      title: l.debt_terms_heading,
      children: [
        CardNote('${context.said(termCountOf(cells, f))} ${l.debt_terms_promise}'),
        const SizedBox(height: SpaceTokens.s2),
        // The condensed grid of the mobile artboard: two cells a row, one at large text.
        LayoutBuilder(
          builder: (context, constraints) {
            final columns = MediaQuery.textScalerOf(context).scale(10) > 15 ? 1 : 2;
            final width = (constraints.maxWidth - SpaceTokens.s2 * (columns - 1)) / columns;
            return Wrap(
              spacing: SpaceTokens.s2,
              runSpacing: SpaceTokens.s2,
              children: [
                for (final cell in cells)
                  SizedBox(
                    width: width,
                    child: Container(
                      padding: const EdgeInsetsDirectional.all(SpaceTokens.s2),
                      decoration: BoxDecoration(
                        color: c.surface,
                        borderRadius: BorderRadius.circular(RadiusTokens.sm),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            context.message(cell.labelKey),
                            style: text.labelSmall?.copyWith(color: c.muted),
                          ),
                          if (cell.missingKey != null)
                            Text(
                              context.message(cell.missingKey!),
                              style: text.bodyMedium?.copyWith(color: c.muted),
                            )
                          else
                            Text(
                              cell.value,
                              textDirection: context.amountDirection,
                              style: text.titleSmall,
                            ),
                          if (cell.source == TermSource.solved)
                            Padding(
                              padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
                              child: Tag(l.debt_source_SOLVED, strong: true),
                            )
                          else if (cell.source == TermSource.fromPlan)
                            Padding(
                              padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
                              child: Tag(l.debt_source_FROM_PLAN),
                            ),
                        ],
                      ),
                    ),
                  ),
              ],
            );
          },
        ),
        CardNote(context.said(balanceLineOf(debt, f))),
        CardNote(l.debt_terms_balanceNeverSolved),
      ],
    );
  }
}

/// 3. Balance over time: what is being paid, against **the comparison** at the minimum.
class _OverTimeCard extends StatelessWidget {
  const _OverTimeCard({required this.detail, required this.f});

  final DebtDetail detail;
  final Formats f;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final debt = detail.debt;
    ChartSeries line(List<SeriesPoint> points, String name, String id, {required bool compared}) {
      final first = points.isEmpty ? null : points.first;
      final last = points.isEmpty ? null : points.last;
      return ChartSeries(
        id: id,
        name: name,
        compared: compared,
        points: [for (final p in points) ChartPoint(p.month, p.balance)],
        said: first == null || last == null
            ? l.debt_chart_saidNothing(
                name,
                context.message(projectionReasonKey(debt.projectionReason)),
              )
            : l.debt_chart_said(
                name,
                f.money(first.balance, debt.currency),
                f.month(first.month),
                f.money(last.balance, debt.currency),
                f.month(last.month),
              ),
      );
    }

    final pace = line(detail.pace, l.debt_chart_pace, 'pace', compared: false);
    // Paying the minimum is the plan here: one line, one story.
    final lines = detail.atMinimumIsTheSameLine
        ? [pace]
        : [pace, line(detail.atMinimum, l.debt_chart_atMinimum, 'at-minimum', compared: true)];
    final drawn = lines.where((s) => s.points.length >= 2).length;
    final assumes = l.debt_chart_detailAssumes(
      f.money(debt.payment ?? debt.minimumPayment, debt.currency),
    );
    final buying = buyingOf(detail, f);
    return PlanCard(
      title: l.debt_detail_overTime,
      children: [
        MonthsLineChart(
          label: l.debt_detail_overTime,
          series: lines,
          marker: debt.payoffMonth == null
              ? null
              : ChartMarker(
                  debt.payoffMonth!,
                  l.debt_chart_clearsMarker(f.month(debt.payoffMonth)),
                ),
          assumption: detail.seriesMonthStride > 1
              ? '$assumes ${l.debt_chart_stride(f.count(detail.seriesMonthStride))}'
              : assumes,
          // A debt whose payment never covers its interest has no line: said, not an empty box.
          unavailable: drawn == 0
              ? l.debt_chart_noLine(context.message(projectionReasonKey(debt.projectionReason)))
              : '',
        ),
        if (buying != null) ...[
          const SizedBox(height: SpaceTokens.s2),
          Sentence(context.said(buying), style: Theme.of(context).textTheme.titleSmall),
        ],
        // Kinder than reality — said only where that line was drawn.
        if (drawn > 1) CardNote(l.debt_chart_atMinimumNote),
      ],
    );
  }
}

/// 4. Payment history, in the server's order, each row saying how it got here.
class _HistoryCard extends StatelessWidget {
  const _HistoryCard({
    required this.debt,
    required this.payments,
    required this.accounts,
    required this.canWrite,
    required this.f,
    required this.onLog,
  });

  final Debt debt;
  final DebtPaymentPage payments;
  final List<Account> accounts;
  final bool canWrite;
  final Formats f;
  final VoidCallback onLog;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final muted = text.bodySmall?.copyWith(color: c.muted);
    final names = {for (final a in accounts) a.id: a.name};
    final rows = payments.content;
    return PlanCard(
      title: l.debt_history_heading,
      action: canWrite ? OutlinedButton(onPressed: onLog, child: Text(l.debt_history_add)) : null,
      children: [
        if (rows.isEmpty)
          CardNote(l.debt_history_empty)
        else ...[
          CardNote(context.said(historyCountOf(rows, f))),
          for (final p in rows)
            Container(
              padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
              decoration: BoxDecoration(
                border: Border(bottom: BorderSide(color: c.divider)),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(context.date(p.paidDate), style: text.titleSmall),
                        Text(context.message(provenanceKey(p.provenance)), style: muted),
                        Sentence(
                          l.debt_history_split(
                            context.money(p.money(p.interest)),
                            context.money(p.money(p.principal)),
                          ),
                          style: muted,
                        ),
                        Sentence(
                          l.debt_history_after(context.money(p.money(p.balanceAfter))),
                          style: muted,
                        ),
                        if (p.paidFromAccountId != null && names[p.paidFromAccountId] != null)
                          Text(l.debt_history_paidFrom(names[p.paidFromAccountId]!), style: muted),
                        if (p.note != null) Text(p.note!, style: muted),
                      ],
                    ),
                  ),
                  const SizedBox(width: SpaceTokens.s2),
                  AmountText(p.money(p.amount)),
                ],
              ),
            ),
          if (payments.totalElements > rows.length)
            CardNote(l.debt_history_showAll(f.count(rows.length))),
        ],
        // The compounding rule in plain words, where somebody reading the splits meets it.
        CardNote(l.debt_history_compounding),
      ],
    );
  }
}
