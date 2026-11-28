import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
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
import 'package:budget_owl/features/bills/domain/requests.dart';
import 'package:budget_owl/features/bills/presentation/bill_sheet.dart';
import 'package:budget_owl/features/bills/presentation/income_sheet.dart';
import 'package:budget_owl/features/bills/presentation/record_payment_sheet.dart';
import 'package:budget_owl/features/bills/presentation/schedule_fields.dart';
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/transactions/presentation/transactions_screen.dart'
    show canWriteNowFrom;
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/snapshots.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/year_month.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart' as intl show Bidi, NumberFormat;
import 'package:material_ui/material_ui.dart';

/// Bills & income — the predictable half of a household's money (bills-and-income.md).
///
/// Blocks in the web's order under a month picker every block follows: **what's left**, income,
/// bills, owed and envelopes. The rules that are the feature's, not UI taste:
///
/// - **The page leads with what's left, from the summary**, never added up here, so the headline
///   cannot disagree with the blocks. It is rendered negative when negative, never clamped.
/// - **Bills count whether or not they are paid**, and the card says so.
/// - **A bill has 0..n occurrences this month**, each its own row addressed by its due date. **Mark
///   paid is the only control in the open**, on a `DUE` row; the corrections are behind *Correct
///   this*. *Forget* is offered only on a recorded occurrence, with a confirmation.
/// - **Owed is a balance, not part of the subtraction**; the fourth term is the minimums not
///   already a bill row.
/// - **A `VIEWER` sees every figure and no write control.**
/// - **Offline, nothing is sent**: a write control explains instead (mobile-app.md decision 3).
class BillsScreen extends ConsumerStatefulWidget {
  const BillsScreen({super.key});

  @override
  ConsumerState<BillsScreen> createState() => _BillsScreenState();
}

/// The three reads the page is made of.
typedef _Month = ({BillsPage bills, IncomeList income, PeriodSummary summary});

class _BillsScreenState extends ConsumerState<BillsScreen> {
  late YearMonth _period = currentPeriod(ref.read(clockProvider)());
  String? _notice;
  AppError? _actionError;
  String? _actionSubject;

  /// The row a request is in flight for, so only its own controls go quiet.
  String? _busy;

  BillsQuery get _query => BillsQuery(_period);

  void _shift(int step) => setState(() {
    _period = _period.shift(step);
    _notice = null;
    _actionError = null;
  });

  void _said(String? notice) {
    if (notice != null && mounted) {
      setState(() {
        _notice = notice;
        _actionError = null;
      });
    }
  }

  /// The offline refusal, before anything is sent: the control stays readable and explains.
  bool _offline() {
    if (canWriteNowFrom(ref)) return false;
    setState(() {
      _actionError = const AppError.offline();
      _actionSubject = null;
      _notice = null;
    });
    return true;
  }

  /// One row-level write, its failure kept beside the bill it was about. A refused write never
  /// empties the screen: the page re-reads on success, and a `409` re-reads too.
  Future<void> _run(String id, String subject, Future<void> Function() write, String said) async {
    if (_offline()) return;
    setState(() {
      _busy = id;
      _actionError = null;
      _actionSubject = null;
      _notice = null;
    });
    try {
      await write();
      _said(said);
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) {
        setState(() {
          _actionError = error;
          _actionSubject = subject;
        });
      }
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  Future<void> _openSheet(Widget sheet) async {
    if (_offline()) return;
    setState(() {
      _notice = null;
      _actionError = null;
    });
    _said(await openSheet(context, sheet));
  }

  Future<void> _refresh() => Future.wait([
    ref.read(billsProvider(_query).notifier).refresh(),
    ref.read(incomeProvider(_period).notifier).refresh(),
    ref.read(periodSummaryProvider(_period).notifier).refresh(),
  ]);

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final canWrite = ref.watch(signedInProvider)?.membership?.role.canWrite ?? false;
    final bills = ref.watch(billsProvider(_query));
    final income = ref.watch(incomeProvider(_period));
    final summary = ref.watch(periodSummaryProvider(_period));
    final categories = ref.watch(categoriesProvider).data ?? const <Category>[];
    final accounts = ref.watch(accountsProvider).data ?? const <Account>[];
    final month = combineSnapshots<_Month>([
      bills,
      income,
      summary,
    ], () => (bills: bills.data!, income: income.data!, summary: summary.data!));
    final error = _actionError;

    return Screen(
      title: l.bills_title,
      onRefresh: _refresh,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.bills_lede),
          _PeriodBar(period: _period, onShift: _shift),
          if (_notice != null) Gutter(child: NoticeBanner(text: _notice!, success: true)),
          if (error != null)
            Gutter(
              child: NoticeBanner(
                icon: Icons.error_outline,
                text: _subjected(
                  _actionSubject,
                  writeErrorText(context, ref.watch(reachabilityProvider), error),
                ),
              ),
            ),
          SnapshotView<_Month>(
            snapshot: month,
            onRetry: _refresh,
            builder: (context, data, stale) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (stale != null) Gutter(child: stale),
                _LeftCard(summary: data.summary),
                _IncomeCard(
                  income: data.income,
                  canWrite: canWrite,
                  busy: _busy,
                  onAdd: () =>
                      _openSheet(IncomeSheet(accounts: accounts, currency: data.summary.currency)),
                  onActions: (source) => _incomeActions(source, accounts, data.summary.currency),
                  onRestore: (source) => _run(
                    source.id,
                    source.name,
                    () => ref.read(billsWriterProvider).setIncomeActive(source, active: true),
                    l.bills_income_restored,
                  ),
                ),
                _BillsCard(
                  page: data.bills,
                  canWrite: canWrite,
                  busy: _busy,
                  onAdd: () => _openSheet(
                    BillSheet(categories: categories, currency: data.summary.currency),
                  ),
                  onBillActions: (bill) => _billActions(bill, categories),
                  onMarkPaid: _markPaid,
                  onCorrect: _correct,
                  onUnmap: (bill, mapping) => _run(
                    mapping.ruleId,
                    bill.name,
                    () => ref.read(billsWriterProvider).unmap(mapping),
                    l.checkIn_mapping_removed,
                  ),
                  onRestore: (bill) => _run(
                    bill.id,
                    bill.name,
                    () => ref.read(billsWriterProvider).setBillActive(bill, active: true),
                    l.bills_restored,
                  ),
                  onDelete: _deleteBill,
                ),
                _OwedCard(summary: data.summary),
                _EnvelopesCard(summary: data.summary),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// One-tap Mark paid, from the row or from *Correct this* on a skipped one: what was budgeted, on
  /// the phone's own date, read from the clock when it is tapped rather than when the screen opened.
  Future<void> _markPaid(Bill bill, BillOccurrence occurrence) => _run(
    _rowId(occurrence),
    bill.name,
    () => ref
        .read(billsWriterProvider)
        .record(
          bill.id,
          occurrence.dueDate,
          markPaidBody(CalendarDate.of(ref.read(clockProvider)())),
        ),
    context.l10n.bills_markedPaid,
  );

  /// The corrections behind *Correct this*: rarer than Mark paid, and one tap away rather than
  /// competing with it on a weekly bill's five rows.
  Future<void> _correct(Bill bill, BillOccurrence occurrence) async {
    final l = context.l10n;
    final choice = await showModalBottomSheet<_Correction>(
      useRootNavigator: true,
      context: context,
      isScrollControlled: true,
      builder: (_) => _CorrectionSheet(bill: bill, occurrence: occurrence),
    );
    if (choice == null || !mounted) return;
    final writer = ref.read(billsWriterProvider);
    switch (choice) {
      case _Correction.markPaid:
        await _markPaid(bill, occurrence);
      case _Correction.otherAmount:
        await _openSheet(RecordPaymentSheet(bill: bill, occurrence: occurrence));
      case _Correction.skip:
        await _run(
          _rowId(occurrence),
          bill.name,
          () => writer.record(bill.id, occurrence.dueDate, statusBody(OccurrenceStatus.skipped)),
          l.bills_skipped,
        );
      case _Correction.markDue:
        await _run(
          _rowId(occurrence),
          bill.name,
          () => writer.record(bill.id, occurrence.dueDate, statusBody(OccurrenceStatus.due)),
          l.bills_markedDue,
        );
      case _Correction.forget:
        if (_offline()) return;
        final confirmed = await confirmAction(
          context,
          heading: l.bills_forgetHeading,
          message: l.bills_forgetMessage,
          action: l.bills_forget,
          destructive: true,
        );
        if (confirmed) {
          await _run(
            _rowId(occurrence),
            bill.name,
            () => writer.forget(bill.id, occurrence.dueDate),
            l.bills_forgotten,
          );
        }
    }
  }

  Future<void> _billActions(Bill bill, List<Category> categories) async {
    final l = context.l10n;
    final choice = await showModalBottomSheet<_BillAction>(
      useRootNavigator: true,
      context: context,
      isScrollControlled: true,
      builder: (_) => _BillActionsSheet(bill: bill),
    );
    if (choice == null || !mounted) return;
    switch (choice) {
      case _BillAction.edit:
        await _openSheet(BillSheet(bill: bill, categories: categories, currency: bill.currency));
      case _BillAction.recordPayment:
        await _openSheet(RecordPaymentSheet(bill: bill));
      case _BillAction.retire:
        if (_offline()) return;
        final confirmed = await confirmAction(
          context,
          heading: l.bills_retireHeading(bill.name),
          message: l.bills_retireMessage,
          action: l.bills_retire,
        );
        if (confirmed) {
          await _run(
            bill.id,
            bill.name,
            () => ref.read(billsWriterProvider).setBillActive(bill, active: false),
            l.bills_retired,
          );
        }
      case _BillAction.delete:
        await _deleteBill(bill);
    }
  }

  Future<void> _deleteBill(Bill bill) async {
    final l = context.l10n;
    if (_offline()) return;
    final confirmed = await confirmAction(
      context,
      heading: l.bills_deleteHeading(bill.name),
      // Says what goes and what stays: the transactions are the thing somebody is afraid of.
      message: l.bills_deleteMessage,
      action: l.common_delete,
      destructive: true,
    );
    if (confirmed) {
      await _run(
        bill.id,
        bill.name,
        () => ref.read(billsWriterProvider).deleteBill(bill),
        l.bills_deleted,
      );
    }
  }

  Future<void> _incomeActions(IncomeSource source, List<Account> accounts, String currency) async {
    final l = context.l10n;
    final choice = await showModalBottomSheet<_BillAction>(
      useRootNavigator: true,
      context: context,
      isScrollControlled: true,
      builder: (_) => _IncomeActionsSheet(source: source),
    );
    if (choice == null || !mounted) return;
    final writer = ref.read(billsWriterProvider);
    switch (choice) {
      case _BillAction.edit:
        await _openSheet(IncomeSheet(source: source, accounts: accounts, currency: currency));
      case _BillAction.retire:
        if (_offline()) return;
        final confirmed = await confirmAction(
          context,
          heading: l.bills_income_retireHeading(source.name),
          message: l.bills_income_retireMessage,
          action: l.bills_retire,
        );
        if (confirmed) {
          await _run(
            source.id,
            source.name,
            () => writer.setIncomeActive(source, active: false),
            l.bills_income_retired,
          );
        }
      case _BillAction.delete:
        if (_offline()) return;
        final confirmed = await confirmAction(
          context,
          heading: l.bills_income_deleteHeading(source.name),
          message: l.bills_income_deleteMessage,
          action: l.common_delete,
          destructive: true,
        );
        if (confirmed) {
          await _run(
            source.id,
            source.name,
            () => writer.deleteIncome(source),
            l.bills_income_deleted,
          );
        }
      case _BillAction.recordPayment:
        return;
    }
  }
}

/// "Electricity — That needs a connection": which row a failure was about, as the web says it.
String _subjected(String? subject, String message) =>
    subject == null ? message : '$subject — $message';

/// An occurrence has no id: its bill and its due date are what identify it.
String _rowId(BillOccurrence o) => '${o.billId}:${o.dueDate.iso}';

/// The month picker: previous, the month by name, next. Every block follows it at once.
class _PeriodBar extends StatelessWidget {
  const _PeriodBar({required this.period, required this.onShift});

  final YearMonth period;
  final void Function(int step) onShift;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return Semantics(
      container: true,
      label: l.bills_periodLabel,
      child: Padding(
        padding: const EdgeInsetsDirectional.symmetric(horizontal: SpaceTokens.s2),
        child: Row(
          children: [
            IconButton(
              tooltip: l.bills_previousMonth,
              icon: const Icon(Icons.chevron_left),
              onPressed: () => onShift(-1),
            ),
            Expanded(
              child: Semantics(
                liveRegion: true,
                header: true,
                child: Text(
                  period.long(context.localeName),
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
            ),
            IconButton(
              tooltip: l.bills_nextMonth,
              icon: const Icon(Icons.chevron_right),
              onPressed: () => onShift(1),
            ),
          ],
        ),
      ),
    );
  }
}

/// 1. What's left — the one figure the page is for, its five terms, and what it means.
class _LeftCard extends StatelessWidget {
  const _LeftCard({required this.summary});

  final PeriodSummary summary;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final s = summary;
    final short = isNegativeWire(s.left);
    String money(String wire) => context.money(s.money(wire));
    return PlanCard(
      title: l.bills_leftHeading,
      children: [
        // Negative when the month does not add up, and never clamped; coloured with the accent
        // text, because the second accent reads as success.
        Wrap(
          crossAxisAlignment: WrapCrossAlignment.end,
          spacing: SpaceTokens.s2,
          children: [
            AmountText(s.money(s.left), negativeIsBad: true, style: text.headlineMedium),
            Padding(
              padding: const EdgeInsetsDirectional.only(bottom: SpaceTokens.s1),
              child: Text(
                short ? l.bills_short : l.bills_left,
                style: text.bodyMedium?.copyWith(color: context.colors.muted),
              ),
            ),
          ],
        ),
        const SizedBox(height: SpaceTokens.s2),
        // The five terms beside the figure, so a plan counted twice is visible, not hidden.
        FigureLine(label: l.bills_termIncome, figure: AmountText(s.money(s.income))),
        FigureLine(label: l.bills_termBills, figure: AmountText(s.money(s.bills))),
        FigureLine(label: l.bills_termEnvelopes, figure: AmountText(s.money(s.envelopesAllocated))),
        FigureLine(
          label: l.bills_termDebtMinimums,
          figure: AmountText(s.money(s.debtMinimumsNotInBills)),
        ),
        FigureLine(label: l.bills_termGoals, figure: AmountText(s.money(s.goalsSetAside))),
        CardNote(l.bills_goalsNote),
        if (s.debtPlanExtraExceedsLeft && s.debtPlanExtra != null)
          CardNote(l.bills_debtExtraExceedsLeft(money(s.debtPlanExtra!), money(s.left))),
        const SizedBox(height: SpaceTokens.s2),
        // A sentence, not a fraction: "$266.00 / $1,959.10" reads as a ratio.
        Sentence(l.bills_stillToPayOf(money(s.billsOutstanding), money(s.bills))),
        CardNote(l.bills_billsCountEitherWay),
        if (short) CardNote(l.bills_shortNote),
      ],
    );
  }
}

/// 2. Income: each source, what arrives each time, the month's figure and how many arrivals.
class _IncomeCard extends StatelessWidget {
  const _IncomeCard({
    required this.income,
    required this.canWrite,
    required this.busy,
    required this.onAdd,
    required this.onActions,
    required this.onRestore,
  });

  final IncomeList income;
  final bool canWrite;
  final String? busy;
  final VoidCallback onAdd;
  final void Function(IncomeSource source) onActions;
  final void Function(IncomeSource source) onRestore;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final locale = context.localeName;
    final numbers = intl.NumberFormat.decimalPattern(locale);
    final active = income.active;
    final retired = income.retired;
    return PlanCard(
      title: l.bills_income_heading,
      action: canWrite && active.isNotEmpty
          ? OutlinedButton(onPressed: onAdd, child: Text(l.bills_income_add))
          : null,
      children: [
        if (active.isEmpty) ...[
          Sentence(l.bills_income_emptyBody),
          const SizedBox(height: SpaceTokens.s2),
          if (canWrite)
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: FilledButton(onPressed: onAdd, child: Text(l.bills_income_addFirst)),
            )
          else
            CardNote(l.bills_emptyReadOnly),
        ] else ...[
          FigureLine(
            label: l.bills_income_total,
            strong: true,
            figure: AmountText(Money.fromWire(income.total, income.currency)),
          ),
          for (final source in active)
            _Row(
              title: source.name,
              busy: busy == source.id,
              lines: [
                scheduleSaid(l, source.schedule, locale),
                // A fortnightly wage lands two or three times: that is the month the household has.
                if (source.dates.length == 1)
                  l.bills_income_arrivalsOne
                else
                  l.bills_income_arrivals(numbers.format(source.dates.length)),
                ?source.accountName,
                context.message(source.truthKey),
              ],
              figure: AmountText(Money.fromWire(source.expectedForPeriod, source.currency)),
              note:
                  '${l.bills_income_eachLabel} '
                  '${context.money(Money.fromWire(source.amount, source.currency))}',
              onActions: canWrite ? () => onActions(source) : null,
              actionsLabel: l.transactions_actionsFor(source.name),
            ),
        ],
        if (retired.isNotEmpty) ...[
          _Subheading(l.bills_income_retiredHeading),
          for (final source in retired)
            _Row(
              title: source.name,
              muted: true,
              busy: busy == source.id,
              lines: [scheduleSaid(l, source.schedule, locale)],
              figure: AmountText(Money.fromWire(source.amount, source.currency)),
              trailing: canWrite
                  ? [
                      OutlinedButton(
                        onPressed: busy == source.id ? null : () => onRestore(source),
                        child: Text(l.bills_restore),
                      ),
                    ]
                  : const [],
            ),
        ],
      ],
    );
  }
}

/// 3. Bills, each with one row per occurrence this month.
class _BillsCard extends StatelessWidget {
  const _BillsCard({
    required this.page,
    required this.canWrite,
    required this.busy,
    required this.onAdd,
    required this.onBillActions,
    required this.onMarkPaid,
    required this.onCorrect,
    required this.onUnmap,
    required this.onRestore,
    required this.onDelete,
  });

  final BillsPage page;
  final bool canWrite;
  final String? busy;
  final VoidCallback onAdd;
  final void Function(Bill bill) onBillActions;
  final void Function(Bill bill, BillOccurrence occurrence) onMarkPaid;
  final void Function(Bill bill, BillOccurrence occurrence) onCorrect;
  final void Function(Bill bill, BillMapping mapping) onUnmap;
  final void Function(Bill bill) onRestore;
  final void Function(Bill bill) onDelete;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final locale = context.localeName;
    final active = page.active;
    final retired = page.retired;
    Money money(String wire) => Money.fromWire(wire, page.currency);
    return PlanCard(
      title: l.bills_heading,
      action: canWrite && active.isNotEmpty
          ? OutlinedButton(onPressed: onAdd, child: Text(l.bills_add))
          : null,
      children: [
        if (active.isEmpty) ...[
          Sentence(l.bills_emptyBody),
          const SizedBox(height: SpaceTokens.s2),
          if (canWrite)
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: FilledButton(onPressed: onAdd, child: Text(l.bills_addFirst)),
            )
          else
            CardNote(l.bills_emptyReadOnly),
        ] else ...[
          FigureLine(label: l.bills_totalLabel, figure: AmountText(money(page.total))),
          FigureLine(label: l.bills_outstandingLabel, figure: AmountText(money(page.outstanding))),
          FigureLine(
            label: l.bills_overdueLabel,
            figure: AmountText(
              money(page.overdue),
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                color: (signOf(page.overdue) ?? 0) > 0 ? context.colors.accentText : null,
              ),
            ),
          ),
          for (final bill in active)
            _BillBlock(
              bill: bill,
              canWrite: canWrite,
              busy: busy,
              locale: locale,
              onActions: () => onBillActions(bill),
              onMarkPaid: (o) => onMarkPaid(bill, o),
              onCorrect: (o) => onCorrect(bill, o),
              onUnmap: (m) => onUnmap(bill, m),
            ),
        ],
        if (retired.isNotEmpty) ...[
          _Subheading(l.bills_retiredHeading),
          CardNote(l.bills_retiredNote),
          for (final bill in retired)
            _Row(
              title: bill.name,
              muted: true,
              busy: busy == bill.id,
              lines: [scheduleSaid(l, bill.schedule, locale)],
              figure: AmountText(bill.money(bill.expectedAmount)),
              trailing: canWrite
                  ? [
                      OutlinedButton(
                        onPressed: busy == bill.id ? null : () => onRestore(bill),
                        child: Text(l.bills_restore),
                      ),
                      OutlinedButton(
                        style: OutlinedButton.styleFrom(foregroundColor: context.colors.accentText),
                        onPressed: busy == bill.id ? null : () => onDelete(bill),
                        child: Text(l.common_delete),
                      ),
                    ]
                  : const [],
            ),
        ],
      ],
    );
  }
}

/// One bill: its name, schedule, category, tolerance and mappings, then a row per occurrence.
class _BillBlock extends StatelessWidget {
  const _BillBlock({
    required this.bill,
    required this.canWrite,
    required this.busy,
    required this.locale,
    required this.onActions,
    required this.onMarkPaid,
    required this.onCorrect,
    required this.onUnmap,
  });

  final Bill bill;
  final bool canWrite;
  final String? busy;
  final String locale;
  final VoidCallback onActions;
  final void Function(BillOccurrence occurrence) onMarkPaid;
  final void Function(BillOccurrence occurrence) onCorrect;
  final void Function(BillMapping mapping) onUnmap;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final c = context.colors;
    return Container(
      margin: const EdgeInsetsDirectional.only(top: SpaceTokens.s3),
      padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s3),
      decoration: BoxDecoration(
        border: Border(top: BorderSide(color: c.divider)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _Row(
            title: bill.name,
            busy: busy == bill.id,
            lines: [
              scheduleSaid(l, bill.schedule, locale),
              if (bill.categoryName != null && bill.categoryParentName == null)
                bill.categoryName!
              else if (bill.categoryName != null)
                l.transactions_categoryTrail(bill.categoryParentName!, bill.categoryName!),
              if (bill.amountVaries && bill.tolerance != null)
                '${l.bills_variesBy} ${context.money(bill.money(bill.tolerance!))}',
            ],
            figure: AmountText(bill.money(bill.expectedAmount)),
            note: l.bills_expectedLabel,
            onActions: canWrite ? onActions : null,
            actionsLabel: l.transactions_actionsFor(bill.name),
          ),
          for (final mapping in bill.mappings)
            _MappingLine(
              bill: bill,
              mapping: mapping,
              canWrite: canWrite,
              busy: busy != null,
              onUnmap: () => onUnmap(mapping),
            ),
          if (bill.occurrences.isEmpty)
            // Still listed, and said plainly: nothing is missing.
            Padding(
              padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
              child: Text(
                bill.schedule.kind == ScheduleKind.irregular
                    ? l.bills_noneIrregular
                    : l.bills_noneThisMonth,
                style: text.bodySmall?.copyWith(color: c.muted),
              ),
            )
          else
            for (final o in bill.occurrences)
              _OccurrenceRow(
                bill: bill,
                occurrence: o,
                canWrite: canWrite,
                busy: busy == _rowId(o),
                onMarkPaid: () => onMarkPaid(o),
                onCorrect: () => onCorrect(o),
              ),
        ],
      ),
    );
  }
}

/// "Mapped · COMCAST XFINITY · Everyday Checking •4417 · exact match", with Unmap.
class _MappingLine extends StatelessWidget {
  const _MappingLine({
    required this.bill,
    required this.mapping,
    required this.canWrite,
    required this.busy,
    required this.onUnmap,
  });

  final Bill bill;
  final BillMapping mapping;
  final bool canWrite;
  final bool busy;
  final VoidCallback onUnmap;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final account = mapping.accountLastFour == null
        ? mapping.accountName
        : l.checkIn_account(mapping.accountName, mapping.accountLastFour!);
    final match = bill.amountVaries && bill.tolerance != null
        ? l.checkIn_mapping_varies(context.money(bill.money(bill.tolerance!)))
        : l.checkIn_mapping_exact;
    return Padding(
      padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
      child: Wrap(
        spacing: SpaceTokens.s2,
        runSpacing: SpaceTokens.s1,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Tag(l.checkIn_mapping_label),
          // From a bank file: its own direction, whatever the layout's.
          Text(
            mapping.payeeName,
            textDirection: intl.Bidi.detectRtlDirectionality(mapping.payeeName)
                ? TextDirection.rtl
                : TextDirection.ltr,
          ),
          Text('· $account · $match', style: Theme.of(context).textTheme.bodySmall),
          if (canWrite)
            TextButton(
              onPressed: busy ? null : onUnmap,
              child: Semantics(
                label: '${l.checkIn_mapping_unmap} ${mapping.payeeName}',
                excludeSemantics: true,
                child: Text(l.checkIn_mapping_unmap),
              ),
            ),
        ],
      ),
    );
  }
}

/// One occurrence: its date, its status and how it was recorded, its figures, and — on a `DUE`
/// row — the one control in the open, Mark paid. The corrections are behind *Correct this*.
class _OccurrenceRow extends StatelessWidget {
  const _OccurrenceRow({
    required this.bill,
    required this.occurrence,
    required this.canWrite,
    required this.busy,
    required this.onMarkPaid,
    required this.onCorrect,
  });

  final Bill bill;
  final BillOccurrence occurrence;
  final bool canWrite;
  final bool busy;
  final VoidCallback onMarkPaid;
  final VoidCallback onCorrect;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final c = context.colors;
    final o = occurrence;
    final skipped = o.status == OccurrenceStatus.skipped;
    return Container(
      margin: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
      padding: const EdgeInsetsDirectional.all(SpaceTokens.s3),
      decoration: BoxDecoration(
        color: c.surface,
        borderRadius: BorderRadius.circular(RadiusTokens.sm),
        border: BorderDirectional(
          start: BorderSide(color: o.overdue ? c.accentText : c.divider, width: 4),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            alignment: WrapAlignment.spaceBetween,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: SpaceTokens.s2,
            runSpacing: SpaceTokens.s1,
            children: [
              Text(context.date(o.dueDate), style: text.titleSmall),
              if (o.showsBothFigures)
                // Both figures, because the difference is the useful number.
                Wrap(
                  spacing: SpaceTokens.s3,
                  children: [
                    _Pair(label: l.bills_expectedLabel, figure: bill.money(o.expectedAmount)),
                    _Pair(label: l.bills_paidLabel, figure: bill.money(o.actualAmount!)),
                  ],
                )
              else
                // One figure; struck through on a skipped row — still worth seeing, no longer part
                // of what the month costs.
                AmountText(bill.money(o.figure), struck: skipped),
            ],
          ),
          const SizedBox(height: SpaceTokens.s1),
          Wrap(
            spacing: SpaceTokens.s2,
            runSpacing: SpaceTokens.s1,
            children: [
              Tag(context.message(o.status.messageKey)),
              if (o.overdue) Tag(l.bills_overdue, strong: true),
              if (o.surprising) Tag(l.bills_surprising, strong: true),
              // A figure somebody asserted is never shown as one the app inferred.
              if (o.recorded) Tag(context.message(o.sourceKey)),
              if (skipped)
                Text(l.bills_notCounted, style: text.bodySmall?.copyWith(color: c.muted)),
            ],
          ),
          if (o.paidDate != null)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
              child: Text(
                '${l.bills_paidOnLabel} ${context.date(o.paidDate!)}',
                style: text.bodySmall?.copyWith(color: c.muted),
              ),
            ),
          if (canWrite)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
              child: Wrap(
                spacing: SpaceTokens.s2,
                runSpacing: SpaceTokens.s1,
                children: [
                  // DUE only: on a skipped row a prominent Mark paid contradicted the tag beside it.
                  if (o.status == OccurrenceStatus.due)
                    FilledButton(
                      onPressed: busy ? null : onMarkPaid,
                      child: Text(l.bills_markPaid),
                    ),
                  TextButton(onPressed: busy ? null : onCorrect, child: Text(l.bills_moreActions)),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _Pair extends StatelessWidget {
  const _Pair({required this.label, required this.figure});

  final String label;
  final Money figure;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.end,
    mainAxisSize: MainAxisSize.min,
    children: [
      Text(
        label,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(color: context.colors.muted),
      ),
      AmountText(figure),
    ],
  );
}

/// 4. Owed — a balance, and the one figure here that is **not** part of the subtraction.
class _OwedCard extends StatelessWidget {
  const _OwedCard({required this.summary});

  final PeriodSummary summary;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final s = summary;
    final count = intl.NumberFormat.decimalPattern(context.localeName).format(s.debtCount);
    return PlanCard(
      title: l.bills_owed_heading,
      children: [
        if (s.debtCount == 0)
          CardNote(l.bills_owed_none)
        else ...[
          FigureLine(label: l.bills_owed_total, strong: true, figure: AmountText(s.money(s.owed))),
          FigureLine(label: l.bills_owed_minimums, figure: AmountText(s.money(s.debtMinimums))),
          CardNote(s.debtCount == 1 ? l.bills_owed_countOne : l.bills_owed_count(count)),
          // Not period-scoped: today's balances, whatever month is shown — said out loud.
          CardNote(l.bills_owed_asOfToday),
          CardNote(l.bills_owed_body),
          CardNote(l.bills_owed_notInBills(context.money(s.money(s.debtMinimumsNotInBills)))),
          CardNote(l.bills_owed_mixedCurrency(s.currency)),
        ],
        const SizedBox(height: SpaceTokens.s3),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: OutlinedButton(
            onPressed: () => context.go('/more/debt-plan'),
            child: Text(l.bills_owed_open),
          ),
        ),
      ],
    );
  }
}

/// 5. Envelopes — the other half of the subtraction, which has a screen of its own.
class _EnvelopesCard extends StatelessWidget {
  const _EnvelopesCard({required this.summary});

  final PeriodSummary summary;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return PlanCard(
      title: l.bills_envelopes_heading,
      children: [
        Sentence(l.bills_envelopes_body),
        const SizedBox(height: SpaceTokens.s2),
        FigureLine(
          label: l.bills_envelopes_allocated,
          strong: true,
          figure: AmountText(summary.money(summary.envelopesAllocated)),
        ),
        const SizedBox(height: SpaceTokens.s3),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: OutlinedButton(
            onPressed: () => context.go('/more/envelopes'),
            child: Text(l.bills_envelopes_open),
          ),
        ),
      ],
    );
  }
}

class _Subheading extends StatelessWidget {
  const _Subheading(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s4, bottom: SpaceTokens.s1),
    child: Semantics(
      header: true,
      child: Text(text, style: Theme.of(context).textTheme.titleSmall),
    ),
  );
}

/// A row: a title and its sub-lines at the start, a figure and its note at the end, and the
/// row's actions. Past large text the figure moves under the title instead of squeezing it.
class _Row extends StatelessWidget {
  const _Row({
    required this.title,
    required this.lines,
    required this.figure,
    this.note,
    this.onActions,
    this.actionsLabel,
    this.trailing = const [],
    this.muted = false,
    this.busy = false,
  });

  final String title;
  final List<String> lines;
  final Widget figure;
  final String? note;
  final VoidCallback? onActions;
  final String? actionsLabel;
  final List<Widget> trailing;
  final bool muted;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final c = context.colors;
    final stacked = MediaQuery.textScalerOf(context).scale(10) > 15;
    final naming = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: text.titleSmall?.copyWith(color: muted ? c.muted : null)),
        for (final line in lines) Text(line, style: text.bodySmall?.copyWith(color: c.muted)),
      ],
    );
    final figures = Column(
      crossAxisAlignment: stacked ? CrossAxisAlignment.start : CrossAxisAlignment.end,
      mainAxisSize: MainAxisSize.min,
      children: [
        figure,
        if (note != null) Text(note!, style: text.labelSmall?.copyWith(color: c.muted)),
      ],
    );
    final actions = onActions == null
        ? null
        : IconButton(
            tooltip: actionsLabel,
            icon: const Icon(Icons.more_vert),
            onPressed: busy ? null : onActions,
          );
    return Opacity(
      opacity: busy ? 0.6 : 1,
      child: Padding(
        padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (stacked) ...[
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(child: naming),
                  ?actions,
                ],
              ),
              figures,
            ] else
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(child: naming),
                  const SizedBox(width: SpaceTokens.s2),
                  figures,
                  ?actions,
                ],
              ),
            if (trailing.isNotEmpty)
              Padding(
                padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
                child: Wrap(spacing: SpaceTokens.s2, children: trailing),
              ),
          ],
        ),
      ),
    );
  }
}

enum _BillAction { edit, recordPayment, retire, delete }

/// A bill's own actions: Edit, Record a payment (irregular only), Retire, Delete.
class _BillActionsSheet extends ConsumerWidget {
  const _BillActionsSheet({required this.bill});

  final Bill bill;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    return _ActionsSheet<_BillAction>(
      heading: l.transactions_actionsFor(bill.name),
      actions: [
        (_BillAction.edit, Icons.edit_outlined, l.common_edit, false),
        // Irregular bills project nothing: recording against a date brings the occurrence into being.
        if (bill.schedule.kind == ScheduleKind.irregular)
          (_BillAction.recordPayment, Icons.add_task, l.bills_recordPayment, false),
        (_BillAction.retire, Icons.archive_outlined, l.bills_retire, false),
        (_BillAction.delete, Icons.delete_outline, l.common_delete, true),
      ],
    );
  }
}

class _IncomeActionsSheet extends StatelessWidget {
  const _IncomeActionsSheet({required this.source});

  final IncomeSource source;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return _ActionsSheet<_BillAction>(
      heading: l.transactions_actionsFor(source.name),
      actions: [
        (_BillAction.edit, Icons.edit_outlined, l.common_edit, false),
        (_BillAction.retire, Icons.archive_outlined, l.bills_retire, false),
        (_BillAction.delete, Icons.delete_outline, l.common_delete, true),
      ],
    );
  }
}

enum _Correction { markPaid, otherAmount, skip, markDue, forget }

/// *Correct this*: Mark paid (on a skipped row), Paid a different amount…, Skip, Mark as due, and
/// Forget — only on a recorded occurrence, since there is nothing to forget about a projection.
class _CorrectionSheet extends StatelessWidget {
  const _CorrectionSheet({required this.bill, required this.occurrence});

  final Bill bill;
  final BillOccurrence occurrence;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final o = occurrence;
    return _ActionsSheet<_Correction>(
      heading: '${bill.name} · ${context.date(o.dueDate)}',
      actions: [
        if (o.status == OccurrenceStatus.skipped)
          (_Correction.markPaid, Icons.check, l.bills_markPaid, false),
        (_Correction.otherAmount, Icons.edit_note, l.bills_otherAmount, false),
        if (o.status != OccurrenceStatus.skipped)
          (_Correction.skip, Icons.redo, l.bills_skip, false),
        if (o.status != OccurrenceStatus.due)
          (_Correction.markDue, Icons.undo, l.bills_markDue, false),
        if (o.recorded) (_Correction.forget, Icons.delete_outline, l.bills_forget, true),
      ],
    );
  }
}

/// A list of actions in a bottom sheet, offline-aware: offline each one is shown disabled with the
/// note, so nothing is sent.
class _ActionsSheet<T> extends ConsumerWidget {
  const _ActionsSheet({required this.heading, required this.actions});

  final String heading;
  final List<(T, IconData, String, bool)> actions;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final online = canWriteNow(ref);
    final c = context.colors;
    return SafeArea(
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Gutter(
              child: Semantics(
                header: true,
                child: Text(heading, style: Theme.of(context).textTheme.titleMedium),
              ),
            ),
            for (final (value, icon, label, danger) in actions)
              ListTile(
                enabled: online,
                leading: Icon(icon, color: danger ? c.accentText : null),
                title: Text(label, style: danger ? TextStyle(color: c.accentText) : null),
                onTap: () => Navigator.of(context).pop(value),
              ),
            if (!online) const Gutter(child: OfflineNote()),
            const SizedBox(height: SpaceTokens.s4),
          ],
        ),
      ),
    );
  }
}
