import 'dart:async';

import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/bills/data/bills_repository.dart';
import 'package:budget_owl/features/bills/domain/bill.dart';
import 'package:budget_owl/features/debt/data/debt_repository.dart';
import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/features/debt/domain/plan_view.dart';
import 'package:budget_owl/features/debt/domain/requests.dart';
import 'package:budget_owl/features/debt/presentation/debt_formats.dart';
import 'package:budget_owl/features/debt/presentation/debt_sheet.dart';
import 'package:budget_owl/features/transactions/presentation/transactions_screen.dart'
    show canWriteNowFrom;
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/said.dart';
import 'package:budget_owl/shared/snapshots.dart';
import 'package:budget_owl/shared/widgets/months_line_chart.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

/// Debt plan — in what order do I attack these, and when am I free? (debt-plan.md, `/debt-plan`)
///
/// The phone shows the plan's order as **the ladder** (the mobile artboard), and the rest as the
/// web does: the monthly commitment, the order of attack, the chart, **both** strategies side by
/// side with the what-if, and the counted-once footer. The rules that would otherwise render as a
/// lie: both strategies always drawn; a negative extra in words; a rate that is not derivable never
/// 0%; a stale balance asks for a statement; the empty state shows no chart, no $0 and no debt-free
/// date; and a `VIEWER` gets no control at all.
class DebtPlanScreen extends ConsumerStatefulWidget {
  const DebtPlanScreen({super.key});

  @override
  ConsumerState<DebtPlanScreen> createState() => _DebtPlanScreenState();
}

typedef _Page = ({Plan plan, DebtList debts});

class _DebtPlanScreenState extends ConsumerState<DebtPlanScreen> {
  final _amountForm = GlobalKey<FormState>();
  final _amount = TextEditingController();
  bool _editingAmount = false;
  bool _saving = false;
  String? _notice;
  AppError? _actionError;

  /// What was just moved and where it landed, for the live region.
  String _announcement = '';

  @override
  void dispose() {
    _amount.dispose();
    super.dispose();
  }

  bool _offline() {
    if (canWriteNowFrom(ref)) return false;
    setState(() {
      _actionError = const AppError.offline();
      _notice = null;
    });
    return true;
  }

  /// One plan write: a strategy, the order or the amount. A `409` is said and the page re-reads,
  /// so two members arranging one ladder collide rather than one silently winning.
  Future<bool> _savePlan(Map<String, Object?> body, {String? notice, String? announce}) async {
    if (_offline()) return false;
    setState(() {
      _saving = true;
      _actionError = null;
      _notice = null;
      _announcement = '';
    });
    try {
      await ref.read(debtWriterProvider).updatePlan(body);
      if (mounted) {
        setState(() {
          _notice = notice;
          _announcement = announce ?? '';
        });
      }
      return true;
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) setState(() => _actionError = error);
      return false;
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _choose(Plan plan, DebtList debts, Strategy strategy) async {
    if (plan.strategy == strategy.wire || _saving) return;
    final l = context.l10n;
    // Your own order starts from the order on screen; otherwise every debt arrives unplaced and the
    // first move is made against an order nobody saw.
    final body = strategy == Strategy.custom
        ? customOrderBody(plan.version, rankedOrder(plan, debts, formatsOf(context)))
        : strategyBody(plan.version, strategy);
    await _savePlan(body, notice: l.debt_strategySaved);
  }

  Future<void> _move(Plan plan, DebtList debts, String currency, LadderRow row, int by) async {
    final f = formatsOf(context);
    final order = movedOrder(plan, debts, currency, row.debt.id, by, f);
    if (order == null || _saving) return;
    final l = context.l10n;
    final rung = order.indexOf(row.debt.id) + 1;
    final of = ladderOf(plan.currencies.firstWhere((g) => g.currency == currency), debts, f).length;
    await _savePlan(
      customOrderBody(plan.version, order),
      announce: l.debt_movedTo(row.debt.name, f.count(rung), f.count(of)),
    );
  }

  Future<void> _saveAmount(Plan plan) async {
    if (!(_amountForm.currentState?.validate() ?? false)) return;
    final typed = _amount.text.trim();
    if (sameAmount(typed, plan.monthlyAmount)) {
      setState(() => _editingAmount = false);
      return;
    }
    final l = context.l10n;
    final saved = await _savePlan(
      monthlyAmountBody(plan.version, typed),
      notice: l.debt_amountSaved,
    );
    if (saved && mounted) setState(() => _editingAmount = false);
  }

  Future<void> _openSheet({required String currency, Debt? debt}) async {
    if (_offline()) return;
    final bills = ref.read(billsProvider(_activeBills(ref))).data?.content ?? const <Bill>[];
    final accounts = ref.read(accountsProvider).data ?? const <Account>[];
    setState(() {
      _notice = null;
      _actionError = null;
    });
    final said = await openSheet(
      context,
      DebtSheet(debt: debt, accounts: accounts, bills: bills, currency: currency),
    );
    if (said != null && mounted) setState(() => _notice = said);
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final canWrite = ref.watch(signedInProvider)?.membership?.role.canWrite ?? false;
    final planSnapshot = ref.watch(debtPlanProvider);
    final debtsSnapshot = ref.watch(debtsProvider);
    // The two lists the add and edit sheet offers as links, read with the page so the sheet opens
    // with them. A failure here costs a link, never the plan.
    ref
      ..watch(billsProvider(_activeBills(ref)))
      ..watch(accountsProvider);
    final period = currentPeriod(ref.watch(clockProvider)());
    final summary = ref.watch(periodSummaryProvider(period)).data;
    final page = combineSnapshots<_Page>([
      planSnapshot,
      debtsSnapshot,
    ], () => (plan: planSnapshot.data!, debts: debtsSnapshot.data!));
    final plan = planSnapshot.data;
    final whatIf = plan == null ? null : whatIfAmount(plan);
    // Its own read, failure-tolerant: a sentence must never hold the plan up.
    final alternative = whatIf == null ? null : ref.watch(whatIfProvider(whatIf)).data;
    final error = _actionError;

    return Screen(
      title: l.debt_title,
      onRefresh: () => Future.wait([
        ref.read(debtPlanProvider.notifier).refresh(),
        ref.read(debtsProvider.notifier).refresh(),
      ]),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.debt_lede),
          // Goals and the plan are coupled by nothing but this sentence; the comparison is the
          // server's (goals.md, decision 3).
          if (summary != null && summary.debtPlanExtraExceedsLeft && summary.debtPlanExtra != null)
            Gutter(
              child: Sentence(
                l.debt_extraExceedsLeft(
                  context.money(summary.money(summary.debtPlanExtra!)),
                  context.money(summary.money(summary.left)),
                ),
                style: Theme.of(context).textTheme.bodySmall?.copyWith(color: context.colors.muted),
              ),
            ),
          // Always in the tree: a live region must exist before its text changes.
          Semantics(liveRegion: true, label: _announcement, child: const SizedBox.shrink()),
          if (_notice != null) Gutter(child: NoticeBanner(text: _notice!, success: true)),
          if (error != null)
            Gutter(
              child: NoticeBanner(
                icon: Icons.error_outline,
                text: writeErrorText(context, ref.watch(reachabilityProvider), error),
              ),
            ),
          SnapshotView<_Page>(
            snapshot: page,
            onRetry: () => unawaited(
              Future.wait([
                ref.read(debtPlanProvider.notifier).refresh(),
                ref.read(debtsProvider.notifier).refresh(),
              ]),
            ),
            builder: (context, data, stale) => _PlanBody(
              plan: data.plan,
              debts: data.debts,
              alternative: alternative,
              stale: stale,
              canWrite: canWrite,
              saving: _saving,
              editingAmount: _editingAmount,
              amountForm: _amountForm,
              amount: _amount,
              onEditAmount: () => setState(() {
                _amount.text = trimAmount(data.plan.monthlyAmount);
                _notice = null;
                _actionError = null;
                _editingAmount = true;
              }),
              onCancelAmount: () => setState(() => _editingAmount = false),
              onSaveAmount: () => _saveAmount(data.plan),
              onChoose: (s) => _choose(data.plan, data.debts, s),
              onMove: (currency, row, by) => _move(data.plan, data.debts, currency, row, by),
              onAdd: () => _openSheet(currency: data.debts.currency),
              onEdit: (debt) => _openSheet(debt: debt, currency: debt.currency),
            ),
          ),
        ],
      ),
    );
  }
}

/// This month's bills, active only — what the debt sheet offers as the bill that pays a minimum.
BillsQuery _activeBills(WidgetRef ref) =>
    BillsQuery(currentPeriod(ref.read(clockProvider)()), includeInactive: false);

class _PlanBody extends StatelessWidget {
  const _PlanBody({
    required this.plan,
    required this.debts,
    required this.alternative,
    required this.stale,
    required this.canWrite,
    required this.saving,
    required this.editingAmount,
    required this.amountForm,
    required this.amount,
    required this.onEditAmount,
    required this.onCancelAmount,
    required this.onSaveAmount,
    required this.onChoose,
    required this.onMove,
    required this.onAdd,
    required this.onEdit,
  });

  final Plan plan;
  final DebtList debts;
  final Plan? alternative;
  final Widget? stale;
  final bool canWrite;
  final bool saving;
  final bool editingAmount;
  final GlobalKey<FormState> amountForm;
  final TextEditingController amount;
  final VoidCallback onEditAmount;
  final VoidCallback onCancelAmount;
  final VoidCallback onSaveAmount;
  final void Function(Strategy strategy) onChoose;
  final void Function(String currency, LadderRow row, int by) onMove;
  final VoidCallback onAdd;
  final void Function(Debt debt) onEdit;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final f = formatsOf(context);
    final group = plan.planGroup;
    final custom = plan.strategy == Strategy.custom.wire;
    final arranging = canWrite && custom;
    final unranked = unrankedOf(debts, f);

    // The empty state: no $0, no chart of nothing, and no debt-free date of today — a household
    // with no debt told it is debt-free this month reads as a bug.
    if (debts.debts.isEmpty) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (stale != null) Gutter(child: stale!),
          PlanCard(
            title: l.debt_empty_heading,
            children: [
              Sentence(l.debt_empty_body),
              CardNote(l.debt_empty_counted),
              const SizedBox(height: SpaceTokens.s3),
              if (canWrite)
                Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: FilledButton(onPressed: onAdd, child: Text(l.debt_add)),
                )
              else
                CardNote(l.debt_emptyReadOnly),
            ],
          ),
        ],
      );
    }

    final strategyName = context.message(strategyKey(plan.strategy));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (stale != null) Gutter(child: stale!),
        // 1. The monthly commitment and how much of it is strategic — editable beside it, because
        // every date on the page derives from it.
        PlanCard(
          title: l.debt_monthlyAmount,
          children: [
            if (group != null) Sentence(context.said(headerOf(plan, group, f))),
            if (canWrite && !editingAmount)
              Padding(
                padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
                child: Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: OutlinedButton(
                    onPressed: onEditAmount,
                    child: Text(l.debt_changeMonthlyAmount),
                  ),
                ),
              ),
            if (canWrite && editingAmount)
              Form(
                key: amountForm,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const SizedBox(height: SpaceTokens.s2),
                    TextFormField(
                      controller: amount,
                      keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      autocorrect: false,
                      enableSuggestions: false,
                      decoration: InputDecoration(
                        labelText: l.debt_monthlyAmount,
                        helperText: l.debt_monthlyAmountHint,
                        helperMaxLines: 3,
                      ),
                      validator: (value) => amountProblem(context, value),
                    ),
                    const SizedBox(height: SpaceTokens.s2),
                    Wrap(
                      spacing: SpaceTokens.s2,
                      children: [
                        FilledButton(
                          onPressed: saving ? null : onSaveAmount,
                          child: Text(saving ? l.common_saving : l.common_save),
                        ),
                        OutlinedButton(
                          onPressed: saving ? null : onCancelAmount,
                          child: Text(l.common_cancel),
                        ),
                      ],
                    ),
                    const OfflineNote(),
                  ],
                ),
              ),
          ],
        ),
        // 3. The order of attack. Choosing one is a write: a VIEWER is told and offered nothing.
        PlanCard(
          title: l.debt_strategyHeading,
          children: [
            if (canWrite)
              Wrap(
                spacing: SpaceTokens.s2,
                runSpacing: SpaceTokens.s2,
                children: [
                  for (final s in Strategy.values)
                    _StrategyButton(
                      label: context.message(s.messageKey),
                      selected: plan.strategy == s.wire,
                      onPressed: saving ? null : () => onChoose(s),
                    ),
                ],
              )
            else
              Sentence(l.debt_strategyIs(strategyName)),
            CardNote(l.debt_strategyNote),
          ],
        ),
        if (plan.mixedCurrency)
          Gutter(
            child: NoticeBanner(text: l.debt_mixedCurrency, icon: Icons.info_outline),
          ),
        for (final g in plan.currencies) ...[
          _ChartCard(plan: plan, group: g, f: f),
          _CompareCard(
            plan: plan,
            group: g,
            alternative: g.planCurrency ? alternative : null,
            f: f,
            offerAvalanche: canWrite && plan.strategy != Strategy.avalanche.wire,
            saving: saving,
            onUseAvalanche: () => onChoose(Strategy.avalanche),
          ),
          PlanCard(
            title: l.debt_listHeading,
            action: canWrite ? OutlinedButton(onPressed: onAdd, child: Text(l.debt_add)) : null,
            children: [
              CardNote(
                [
                  if (custom) l.debt_orderCustom else l.debt_order(strategyName),
                  l.debt_openAny,
                  if (arranging) l.debt_arrangeHint,
                ].join(' '),
              ),
              for (final row in ladderOf(g, debts, f))
                _RungCard(
                  row: row,
                  canWrite: canWrite,
                  arranging: arranging,
                  saving: saving,
                  onEdit: () => onEdit(row.debt),
                  onMove: (by) => onMove(g.currency, row, by),
                ),
            ],
          ),
        ],
        if (unranked.isNotEmpty)
          PlanCard(
            title: l.debt_settledHeading,
            children: [
              for (final row in unranked)
                _RungCard(
                  row: row,
                  canWrite: false,
                  arranging: false,
                  saving: saving,
                  onEdit: () {},
                  onMove: (_) {},
                ),
            ],
          ),
        // 7. The counted-once rule, in the household's own language.
        Gutter(
          child: Sentence(
            l.debt_footer(context.money(Money.fromWire(debts.debtMinimums, debts.currency))),
            style: Theme.of(context).textTheme.bodySmall?.copyWith(color: context.colors.muted),
          ),
        ),
      ],
    );
  }
}

/// A strategy button: pressed when it is the one in force. Not a tab: pressing one **saves** a
/// choice and changes every date on the page.
class _StrategyButton extends StatelessWidget {
  const _StrategyButton({required this.label, required this.selected, required this.onPressed});

  final String label;
  final bool selected;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => Semantics(
    selected: selected,
    button: true,
    child: selected
        ? FilledButton(onPressed: onPressed, child: Text(label))
        : OutlinedButton(onPressed: onPressed, child: Text(label)),
  );
}

/// 4. Balance to zero: one line per strategy, labelled with the assumption, and the headline.
class _ChartCard extends StatelessWidget {
  const _ChartCard({required this.plan, required this.group, required this.f});

  final Plan plan;
  final CurrencyGroup group;
  final Formats f;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final lines = _linesOf(context);
    final stride = group.selected.seriesMonthStride > group.avalanche.seriesMonthStride
        ? group.selected.seriesMonthStride
        : group.avalanche.seriesMonthStride;
    final assumes = l.debt_chart_assumes(f.money(group.monthlyAmount, group.currency));
    final drawn = lines.any((line) => line.points.length >= 2);
    return PlanCard(
      title: l.debt_chartHeading,
      children: [
        if (plan.mixedCurrency) CardNote(l.debt_inCurrency(group.currency)),
        if (group.belowMinimums) ...[
          Sentence(
            l.debt_belowMinimums(
              f.money(group.monthlyAmount, group.currency),
              f.money(group.minimums, group.currency),
            ),
          ),
          CardNote(l.debt_belowMinimumsNote),
        ] else ...[
          MonthsLineChart(
            label: l.debt_chartHeading,
            series: lines,
            marker: group.selected.debtFreeMonth == null
                ? null
                : ChartMarker(
                    group.selected.debtFreeMonth!,
                    l.debt_chart_marker(f.month(group.selected.debtFreeMonth)),
                  ),
            assumption: stride > 1 ? '$assumes ${l.debt_chart_stride(f.count(stride))}' : assumes,
            unavailable: drawn
                ? ''
                : l.debt_chart_noLine(context.message(projectionReasonKey(group.selected.reason))),
            coincidentLabel: lines.length > 1
                ? l.debt_chart_coincide(lines.map((s) => s.name).join(' · '))
                : '',
          ),
          const SizedBox(height: SpaceTokens.s2),
          Sentence(
            context.said(headlineOf(group, f)),
            style: Theme.of(context).textTheme.titleSmall,
          ),
        ],
      ],
    );
  }

  /// Both named strategies always — and the household's own order as a third line when it has one,
  /// because a `CUSTOM` plan charting only the two it did not pick would be somebody else's plan.
  List<ChartSeries> _linesOf(BuildContext context) {
    ChartSeries line(StrategyProjection p, String strategy, {required bool compared}) {
      final name = context.message(strategyKey(strategy));
      final first = p.series.isEmpty ? null : p.series.first;
      final last = p.series.isEmpty ? null : p.series.last;
      return ChartSeries(
        id: '${group.currency}-$strategy',
        name: name,
        compared: compared,
        points: [for (final point in p.series) ChartPoint(point.month, point.balance)],
        said: first == null || last == null
            ? context.l10n.debt_chart_saidNothing(
                name,
                context.message(projectionReasonKey(p.reason)),
              )
            : context.l10n.debt_chart_said(
                name,
                f.money(first.balance, group.currency),
                f.month(first.month),
                f.money(last.balance, group.currency),
                f.month(last.month),
              ),
      );
    }

    if (plan.strategy == Strategy.custom.wire) {
      return [
        line(group.selected, Strategy.custom.wire, compared: false),
        line(group.avalanche, Strategy.avalanche.wire, compared: true),
        line(group.snowball, Strategy.snowball.wire, compared: true),
      ];
    }
    final snowball = plan.strategy == Strategy.snowball.wire;
    return [
      line(
        snowball ? group.snowball : group.avalanche,
        snowball ? Strategy.snowball.wire : Strategy.avalanche.wire,
        compared: false,
      ),
      line(
        snowball ? group.avalanche : group.snowball,
        snowball ? Strategy.avalanche.wire : Strategy.snowball.wire,
        compared: true,
      ),
    ];
  }
}

/// 5. Avalanche or snowball, side by side — neither pre-blessed beyond showing which is in use —
/// then the sentence that compares them, the what-if, and *Use avalanche*.
class _CompareCard extends StatelessWidget {
  const _CompareCard({
    required this.plan,
    required this.group,
    required this.alternative,
    required this.f,
    required this.offerAvalanche,
    required this.saving,
    required this.onUseAvalanche,
  });

  final Plan plan;
  final CurrencyGroup group;
  final Plan? alternative;
  final Formats f;
  final bool offerAvalanche;
  final bool saving;
  final VoidCallback onUseAvalanche;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final own = selectedSaid(plan, group, f);
    final comparison = comparisonOf(group, f);
    final whatIf = whatIfSaid(plan, alternative, f);
    final cards = cardsOf(plan, group, f);
    return PlanCard(
      title: l.debt_compareHeading,
      children: [
        if (own != null) Sentence(context.said(own)),
        const SizedBox(height: SpaceTokens.s2),
        // Side by side everywhere, as the canvas settles; stacked only when text is large.
        LayoutBuilder(
          builder: (context, constraints) {
            final side = MediaQuery.textScalerOf(context).scale(10) <= 15;
            final children = [
              for (final card in cards) _StrategyCardView(card: card, group: group, f: f),
            ];
            return side
                ? IntrinsicHeight(
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Expanded(child: children[0]),
                        const SizedBox(width: SpaceTokens.s2),
                        Expanded(child: children[1]),
                      ],
                    ),
                  )
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      children[0],
                      const SizedBox(height: SpaceTokens.s2),
                      children[1],
                    ],
                  );
          },
        ),
        if (comparison != null) ...[
          const SizedBox(height: SpaceTokens.s3),
          Sentence(context.said(comparison)),
        ],
        if (whatIf != null) CardNote(context.said(whatIf)),
        if (offerAvalanche) ...[
          const SizedBox(height: SpaceTokens.s3),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: OutlinedButton(
              onPressed: saving ? null : onUseAvalanche,
              child: Text(l.debt_useAvalanche),
            ),
          ),
        ],
      ],
    );
  }
}

class _StrategyCardView extends StatelessWidget {
  const _StrategyCardView({required this.card, required this.group, required this.f});

  final StrategyCard card;
  final CurrencyGroup group;
  final Formats f;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    return Container(
      padding: const EdgeInsetsDirectional.all(SpaceTokens.s3),
      decoration: BoxDecoration(
        color: c.surface,
        borderRadius: BorderRadius.circular(RadiusTokens.sm),
        border: Border.all(
          color: card.selected ? c.accentText : c.divider,
          width: card.selected ? 2 : 1,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: SpaceTokens.s2,
            runSpacing: SpaceTokens.s1,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(context.message(card.strategy.messageKey), style: text.titleSmall),
              if (card.selected) Tag(l.debt_selected, strong: true),
            ],
          ),
          CardNote(context.message(card.rationaleKey)),
          const SizedBox(height: SpaceTokens.s2),
          Sentence(context.said(card.debtFree), style: text.bodyMedium),
          if (card.firstWin != null) CardNote(context.said(card.firstWin!)),
          CardNote(
            card.totalInterest == null
                ? l.debt_card_noInterest
                : l.debt_card_totalInterest(f.money(card.totalInterest!, group.currency)),
          ),
        ],
      ),
    );
  }
}

/// One rung of the ladder: its place, its name, its balance and where that figure came from, its
/// rate, what it is paying in the allocation rule's words, when it clears, and its progress.
class _RungCard extends StatelessWidget {
  const _RungCard({
    required this.row,
    required this.canWrite,
    required this.arranging,
    required this.saving,
    required this.onEdit,
    required this.onMove,
  });

  final LadderRow row;
  final bool canWrite;
  final bool arranging;
  final bool saving;
  final VoidCallback onEdit;
  final void Function(int by) onMove;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final debt = row.debt;
    final f = formatsOf(context);
    final muted = text.bodySmall?.copyWith(color: c.muted);
    return Container(
      margin: const EdgeInsetsDirectional.only(top: SpaceTokens.s3),
      padding: const EdgeInsetsDirectional.all(SpaceTokens.s3),
      decoration: BoxDecoration(
        color: c.surface,
        borderRadius: BorderRadius.circular(RadiusTokens.sm),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (debt.rung != null)
            Text(
              l.debt_rung(f.count(debt.rung!)).toUpperCase(),
              style: text.labelSmall?.copyWith(color: c.accentText),
            ),
          Text(debt.name, style: text.titleMedium),
          Text(context.said(row.naming), style: muted),
          const SizedBox(height: SpaceTokens.s2),
          AmountText(debt.money(debt.balance), style: text.titleLarge),
          Text(
            context.said(row.balanceNote),
            style: muted?.copyWith(
              fontStyle: debt.estimated ? FontStyle.italic : null,
              color: debt.estimated ? c.accentText : null,
            ),
          ),
          const SizedBox(height: SpaceTokens.s1),
          Wrap(
            spacing: SpaceTokens.s2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(context.said(row.rate), style: text.bodyMedium),
              if (row.rateSolved) Tag(l.debt_source_SOLVED),
            ],
          ),
          Sentence(context.said(row.paying), style: text.bodyMedium),
          Sentence(context.said(row.clears), style: muted),
          if (row.progress != null) Sentence(context.said(row.progress!), style: muted),
          const SizedBox(height: SpaceTokens.s2),
          Wrap(
            spacing: SpaceTokens.s2,
            runSpacing: SpaceTokens.s1,
            children: [
              TextButton(
                onPressed: () => context.go('/more/debt-plan/${Uri.encodeComponent(debt.id)}'),
                child: Semantics(
                  label: '${l.debt_open} ${debt.name}',
                  excludeSemantics: true,
                  child: Text(l.debt_open),
                ),
              ),
              if (canWrite) TextButton(onPressed: onEdit, child: Text(l.debt_editTerms)),
              // Buttons, not drag and drop: reachable without a gesture, and named for the debt.
              if (arranging && row.canMoveUp)
                OutlinedButton(
                  onPressed: saving ? null : () => onMove(-1),
                  child: Semantics(
                    label: l.debt_moveUpNamed(debt.name),
                    excludeSemantics: true,
                    child: Text(l.debt_moveUp),
                  ),
                ),
              if (arranging && row.canMoveDown)
                OutlinedButton(
                  onPressed: saving ? null : () => onMove(1),
                  child: Semantics(
                    label: l.debt_moveDownNamed(debt.name),
                    excludeSemantics: true,
                    child: Text(l.debt_moveDown),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }
}
