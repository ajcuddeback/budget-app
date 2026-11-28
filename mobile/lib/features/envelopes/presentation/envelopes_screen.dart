import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/presentation/accounts_screen.dart'
    show subjectAndMessage;
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/envelopes/data/envelopes.dart';
import 'package:budget_owl/features/envelopes/domain/envelope.dart';
import 'package:budget_owl/features/envelopes/presentation/entry_sheet.dart';
import 'package:budget_owl/features/envelopes/presentation/envelope_sheet.dart';
import 'package:budget_owl/shared/widgets/grouped_row.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/write_failure.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// What the household decided to spend, and what is left of it (envelopes.md, UI).
///
/// - **`remaining` is the headline on every card**, negative when overspent and never clamped;
///   the running total sits beneath it. Every figure, and the month's totals, are the server's:
///   this screen does no arithmetic on money.
/// - **Logging spend is one tap from the card**, and the sheet opens on the amount and asks for
///   nothing else. This is the action somebody takes eighty times a month.
/// - **The log says where every row came from**: typed, or taken from a transaction.
/// - It opens on the current month, or on the most recent month that has envelopes when this one
///   has none, and names the month it is showing either way.
/// - **A `VIEWER` sees no write control.** The API refuses one regardless.
class EnvelopesScreen extends ConsumerStatefulWidget {
  const EnvelopesScreen({super.key});

  @override
  ConsumerState<EnvelopesScreen> createState() => _EnvelopesScreenState();
}

enum _Action { edit, archive, unarchive, delete }

enum _EntryAction { edit, delete }

class _EnvelopesScreenState extends ConsumerState<EnvelopesScreen> {
  /// The month shown. Chosen once, when the periods arrive (or fail to); moved by the arrows.
  String? _period;
  bool _showArchived = false;

  /// The envelope whose log is open. One at a time: this is a list, not a grid.
  String? _openLog;
  String? _notice;
  String? _actionError;
  String? _actionSubject;

  void _clear() {
    _notice = null;
    _actionError = null;
    _actionSubject = null;
  }

  void _said(String? notice) {
    if (notice != null && mounted) {
      setState(() {
        _clear();
        _notice = notice;
      });
    }
  }

  void _offline() => setState(() {
    _clear();
    _actionError = writesOffNow(context, ref);
  });

  void _shift(String period, int step) => setState(() {
    _clear();
    _period = shiftPeriod(period, step);
    _openLog = null;
  });

  Future<void> _run(String subject, Future<void> Function() action, String notice) async {
    setState(_clear);
    try {
      await action();
      _said(notice);
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) {
        setState(() {
          _actionError = writeFailureText(context, ref, error);
          _actionSubject = subject;
        });
      }
    }
  }

  Future<void> _add(String period, String currency) async {
    setState(_clear);
    final saved = await showEnvelopeSheet(
      context,
      period: period,
      currency: currency,
      categories: ref.read(categoriesProvider).data ?? const [],
    );
    if (saved == null || !mounted) return;
    // A new envelope may be for another month: follow it there, rather than leave the person
    // looking at a list it is not in.
    setState(() => _period = saved.period);
    _said(saved.notice);
  }

  Future<void> _logSpend(Envelope envelope, {EnvelopeEntry? editing}) async {
    setState(_clear);
    _said(await showEntrySheet(context, envelope: envelope, editing: editing));
  }

  Future<void> _envelopeActions(Envelope envelope) async {
    final l = context.l10n;
    final online = ref.read(reachabilityProvider).canWrite;
    final choice = await showRowActions<_Action>(
      context,
      title: l.transactions_actionsFor(envelope.name),
      online: online,
      actions: [
        if (envelope.archived)
          RowAction(_Action.unarchive, Icons.unarchive_outlined, l.envelopes_unarchive)
        else ...[
          RowAction(_Action.edit, Icons.edit_outlined, l.common_edit),
          RowAction(_Action.archive, Icons.archive_outlined, l.envelopes_archive),
        ],
        RowAction(_Action.delete, Icons.delete_outline, l.common_delete, danger: true),
      ],
    );
    if (choice == null || !mounted) return;
    final writer = ref.read(envelopesWriterProvider);
    switch (choice) {
      case _Action.edit:
        setState(_clear);
        final saved = await showEnvelopeSheet(
          context,
          period: envelope.period,
          currency: envelope.currency,
          categories: ref.read(categoriesProvider).data ?? const [],
          editing: envelope,
        );
        _said(saved?.notice);
      case _Action.archive:
        final confirmed = await confirmAction(
          context,
          heading: l.envelopes_archiveHeading(envelope.name),
          message: l.envelopes_archiveMessage,
          action: l.envelopes_archive,
        );
        if (confirmed) {
          await _run(envelope.name, () => writer.archive(envelope), l.envelopes_archived);
        }
      case _Action.unarchive:
        await _run(envelope.name, () => writer.unarchive(envelope), l.envelopes_unarchived);
      case _Action.delete:
        final confirmed = await confirmAction(
          context,
          heading: l.envelopes_deleteHeading(envelope.name),
          // Says what goes and what stays: the transactions are what somebody is afraid of.
          message: l.envelopes_deleteMessage('${envelope.entryCount}'),
          action: l.common_delete,
          destructive: true,
        );
        if (confirmed) {
          if (_openLog == envelope.id) setState(() => _openLog = null);
          await _run(envelope.name, () => writer.delete(envelope), l.envelopes_deleted);
        }
    }
  }

  Future<void> _entryActions(Envelope envelope, EnvelopeEntry entry) async {
    final l = context.l10n;
    final online = ref.read(reachabilityProvider).canWrite;
    final choice = await showRowActions<_EntryAction>(
      context,
      title: l.envelopes_editEntryTitle,
      online: online,
      actions: [
        RowAction(_EntryAction.edit, Icons.edit_outlined, l.common_edit),
        RowAction(_EntryAction.delete, Icons.delete_outline, l.common_delete, danger: true),
      ],
    );
    if (choice == null || !mounted) return;
    switch (choice) {
      case _EntryAction.edit:
        await _logSpend(envelope, editing: entry);
      case _EntryAction.delete:
        final confirmed = await confirmAction(
          context,
          heading: l.envelopes_deleteEntryHeading,
          message: l.envelopes_deleteEntryMessage(envelope.name),
          action: l.common_delete,
          destructive: true,
        );
        if (confirmed) {
          await _run(
            envelope.name,
            () => ref.read(envelopesWriterProvider).deleteEntry(envelope, entry),
            l.envelopes_entryDeleted,
          );
        }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final membership = ref.watch(signedInProvider)?.membership;
    final canWrite = membership?.role.canWrite ?? false;
    final online = canWriteNow(ref);
    final periods = ref.watch(envelopePeriodsProvider);
    if (_period == null && (periods.hasValue || periods.hasError)) {
      // Decided once, before anything is read for a month — offline, the current one.
      _period = openingPeriod(periods.value ?? const [], currentPeriod(ref.read(clockProvider)()));
    }
    final period = _period;
    final error = _actionError;
    final currency = membership?.baseCurrency ?? '';

    return Screen(
      title: l.envelopes_title,
      onRefresh: period == null
          ? null
          : () => ref.read(envelopesProvider(period).notifier).refresh(),
      actions: [
        if (canWrite && period != null)
          TextButton.icon(
            onPressed: online ? () => _add(period, currency) : _offline,
            style: online ? null : TextButton.styleFrom(foregroundColor: c.muted),
            icon: const Icon(Icons.add),
            label: Text(l.envelopes_add),
          ),
      ],
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.envelopes_lede),
          if (period == null)
            const SnapshotView<Object>(snapshot: Snapshot.loading(), builder: _never)
          else ...[
            _PeriodBar(
              period: period,
              onPrevious: () => _shift(period, -1),
              onNext: () => _shift(period, 1),
            ),
            if (_notice != null) Gutter(child: NoticeBanner(text: _notice!, success: true)),
            if (error != null)
              Gutter(child: NoticeBanner(text: subjectAndMessage(_actionSubject, error))),
            SnapshotView<EnvelopePage>(
              snapshot: ref.watch(envelopesProvider(period)),
              onRetry: () => ref.read(envelopesProvider(period).notifier).refresh(),
              builder: (context, page, stale) => _body(
                context,
                page,
                stale,
                canWrite: canWrite,
                online: online,
                onAddFirst: online ? () => _add(period, currency) : _offline,
              ),
            ),
          ],
        ],
      ),
    );
  }

  static Widget _never(BuildContext context, Object data, Widget? stale) => const SizedBox.shrink();

  Widget _body(
    BuildContext context,
    EnvelopePage page,
    Widget? stale, {
    required bool canWrite,
    required bool online,
    required VoidCallback onAddFirst,
  }) {
    final l = context.l10n;
    final active = [
      for (final e in page.envelopes)
        if (!e.archived) e,
    ];
    final archived = [
      for (final e in page.envelopes)
        if (e.archived) e,
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (stale != null) Gutter(child: stale),
        Overline(l.envelopes_heading),
        if (active.isNotEmpty) _Totals(page: page),
        if (active.isEmpty) ...[
          Lede(l.envelopes_emptyBody),
          if (canWrite)
            Gutter(
              child: Align(
                alignment: AlignmentDirectional.centerStart,
                child: FilledButton(onPressed: onAddFirst, child: Text(l.envelopes_addFirst)),
              ),
            )
          else
            Lede(l.envelopes_emptyReadOnly),
        ],
        for (final envelope in active)
          _EnvelopeCard(
            envelope: envelope,
            canWrite: canWrite,
            online: online,
            logOpen: _openLog == envelope.id,
            onLogSpend: online ? () => _logSpend(envelope) : _offline,
            onToggleLog: () => setState(() {
              _openLog = _openLog == envelope.id ? null : envelope.id;
            }),
            onActions: () => _envelopeActions(envelope),
            onEntry: canWrite ? (entry) => _entryActions(envelope, entry) : null,
          ),
        if (archived.isNotEmpty) ...[
          Overline(l.envelopes_archivedHeading),
          Gutter(
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: OutlinedButton(
                onPressed: () => setState(() => _showArchived = !_showArchived),
                child: Text(
                  _showArchived
                      ? l.envelopes_hideArchived('${archived.length}')
                      : l.envelopes_showArchived('${archived.length}'),
                ),
              ),
            ),
          ),
          if (_showArchived) ...[
            Lede(l.envelopes_archivedNote),
            for (var i = 0; i < archived.length; i++)
              _ArchivedRow(
                envelope: archived[i],
                first: i == 0,
                last: i == archived.length - 1,
                onTap: canWrite ? () => _envelopeActions(archived[i]) : null,
              ),
          ],
        ],
      ],
    );
  }
}

/// "‹ August 2026 ›" — the month named in the viewer's language, and the way to the next one.
class _PeriodBar extends StatelessWidget {
  const _PeriodBar({required this.period, required this.onPrevious, required this.onNext});

  final String period;
  final VoidCallback onPrevious;
  final VoidCallback onNext;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    return Semantics(
      container: true,
      label: l.envelopes_periodLabel,
      child: Padding(
        padding: const EdgeInsetsDirectional.symmetric(horizontal: SpaceTokens.s2),
        child: Row(
          children: [
            IconButton(
              tooltip: l.envelopes_previousMonth,
              icon: const Icon(Icons.chevron_left),
              onPressed: onPrevious,
            ),
            Expanded(
              child: Semantics(
                liveRegion: true,
                header: true,
                child: Text(
                  periodName(period, context.localeName),
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
            ),
            IconButton(
              tooltip: l.envelopes_nextMonth,
              icon: const Icon(Icons.chevron_right),
              onPressed: onNext,
            ),
          ],
        ),
      ),
    );
  }
}

/// Set aside, spent, left: the month's totals as the server computed them.
class _Totals extends StatelessWidget {
  const _Totals({required this.page});

  final EnvelopePage page;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    Widget figure(String label, String amount, {bool headline = false}) => Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(label, style: text.bodySmall?.copyWith(color: c.muted)),
        Text(
          amount,
          textDirection: context.amountDirection,
          style: (headline ? text.titleLarge : text.titleMedium)?.copyWith(
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
        ),
      ],
    );
    return Padding(
      padding: const EdgeInsetsDirectional.fromSTEB(
        SpaceTokens.s4,
        0,
        SpaceTokens.s4,
        SpaceTokens.s3,
      ),
      child: Wrap(
        spacing: SpaceTokens.s6,
        runSpacing: SpaceTokens.s2,
        crossAxisAlignment: WrapCrossAlignment.end,
        children: [
          figure(l.envelopes_totalAllocated, context.money(page.totalAllocated)),
          figure(l.envelopes_totalSpent, context.money(page.totalSpent)),
          figure(l.envelopes_totalRemaining, context.money(page.totalRemaining), headline: true),
        ],
      ),
    );
  }
}

class _EnvelopeCard extends ConsumerWidget {
  const _EnvelopeCard({
    required this.envelope,
    required this.canWrite,
    required this.online,
    required this.logOpen,
    required this.onLogSpend,
    required this.onToggleLog,
    required this.onActions,
    required this.onEntry,
  });

  final Envelope envelope;
  final bool canWrite;
  final bool online;
  final bool logOpen;
  final VoidCallback onLogSpend;
  final VoidCallback onToggleLog;
  final VoidCallback onActions;
  final void Function(EnvelopeEntry entry)? onEntry;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final e = envelope;
    final category = e.categoryName;
    final parent = e.categoryParentName;
    // Hidden when it only repeats the name: naming an envelope after its category is the norm.
    final categoryLine = category == null || category == e.name
        ? null
        : parent == null
        ? category
        : l.transactions_categoryTrail(parent, category);
    final remaining = context.money(e.remaining);
    final stacked = MediaQuery.textScalerOf(context).scale(1) > 1.5;

    final naming = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(e.name, style: text.titleMedium),
        if (categoryLine != null)
          Text(categoryLine, style: text.bodySmall?.copyWith(color: c.muted)),
      ],
    );
    // The headline. Negative when overspent, and never clamped.
    final headline = Column(
      crossAxisAlignment: stacked ? CrossAxisAlignment.start : CrossAxisAlignment.end,
      children: [
        Text(
          remaining,
          textDirection: context.amountDirection,
          style: text.titleLarge?.copyWith(
            color: e.overspent ? c.accentText : null,
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
        ),
        Text(
          e.overspent ? l.envelopes_over : l.envelopes_left,
          style: text.bodySmall?.copyWith(color: c.muted),
        ),
      ],
    );

    return Padding(
      padding: const EdgeInsetsDirectional.symmetric(
        horizontal: SpaceTokens.s4,
        vertical: SpaceTokens.s1,
      ),
      child: Card(
        margin: EdgeInsetsDirectional.zero,
        child: Padding(
          padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (stacked)
                Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [naming, headline])
              else
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(child: naming),
                    const SizedBox(width: SpaceTokens.s2),
                    headline,
                  ],
                ),
              const SizedBox(height: SpaceTokens.s2),
              // Presentational only; the running total beneath says the real figures.
              Semantics(
                label: l.envelopes_meterLabel(e.name, '${e.spentPercent}'),
                excludeSemantics: true,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(RadiusTokens.sm),
                  child: LinearProgressIndicator(
                    value: e.spentPercent / 100,
                    minHeight: SpaceTokens.s2,
                    color: e.overspent ? c.accentText : c.primary,
                    backgroundColor: c.goalTrack,
                  ),
                ),
              ),
              const SizedBox(height: SpaceTokens.s2),
              Text(
                '${l.envelopes_runningTotal} ${context.money(e.spent)} / ${context.money(e.amount)}',
                style: text.bodySmall?.copyWith(color: c.muted),
              ),
              const SizedBox(height: SpaceTokens.s2),
              Wrap(
                spacing: SpaceTokens.s2,
                runSpacing: SpaceTokens.s1,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  if (canWrite)
                    FilledButton.icon(
                      // Offline it stays tappable and says why; nothing is queued.
                      onPressed: onLogSpend,
                      style: online
                          ? null
                          : FilledButton.styleFrom(
                              backgroundColor: c.surface,
                              foregroundColor: c.muted,
                            ),
                      icon: const Icon(Icons.add),
                      label: Text(l.envelopes_logSpend),
                    ),
                  // Nothing logged, nothing to show: a toggle that opens an empty list wastes a tap.
                  if (e.entryCount > 0)
                    TextButton(
                      onPressed: onToggleLog,
                      child: Text(
                        logOpen
                            ? l.envelopes_hideLog
                            : e.entryCount == 1
                            ? l.envelopes_showLogOne
                            : l.envelopes_showLog('${e.entryCount}'),
                      ),
                    ),
                  if (canWrite)
                    IconButton(
                      tooltip: l.transactions_actionsFor(e.name),
                      icon: const Icon(Icons.more_horiz),
                      onPressed: onActions,
                    ),
                ],
              ),
              if (logOpen) _Log(envelope: e, onEntry: onEntry),
            ],
          ),
        ),
      ),
    );
  }
}

/// An envelope's log, newest first, each row saying where its figure came from.
class _Log extends ConsumerWidget {
  const _Log({required this.envelope, required this.onEntry});

  final Envelope envelope;
  final void Function(EnvelopeEntry entry)? onEntry;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final snapshot = ref.watch(envelopeLogProvider(envelope.id));
    return SnapshotView<List<EnvelopeEntry>>(
      snapshot: snapshot,
      onRetry: () => ref.read(envelopeLogProvider(envelope.id).notifier).refresh(),
      builder: (context, entries, stale) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ?stale,
          if (entries.isEmpty)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
              child: Text(l.envelopes_logEmpty, style: text.bodySmall?.copyWith(color: c.muted)),
            ),
          for (final entry in entries) _EntryRow(entry: entry, onTap: onEntry),
        ],
      ),
    );
  }
}

class _EntryRow extends StatelessWidget {
  const _EntryRow({required this.entry, required this.onTap});

  final EnvelopeEntry entry;
  final void Function(EnvelopeEntry entry)? onTap;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final date = context.date(entry.date);
    final source = context.message(entry.sourceKey);
    final amount = context.money(entry.amount);
    final payee = entry.payeeName;
    final note = entry.note;
    final tap = onTap;
    return InkWell(
      onTap: tap == null ? null : () => tap(entry),
      borderRadius: BorderRadius.circular(RadiusTokens.sm),
      child: Semantics(
        button: tap != null,
        label: [amount, date, ?payee, source, ?note].join(', '),
        excludeSemantics: true,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 48),
          child: Container(
            decoration: BoxDecoration(
              border: Border(top: BorderSide(color: c.divider)),
            ),
            padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Wrap(
                        spacing: SpaceTokens.s2,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          Text(payee == null ? date : '$date · $payee', style: text.bodyMedium),
                          // Said on every row: a figure typed and one taken from a transaction are
                          // different kinds of claim.
                          _Badge(source),
                        ],
                      ),
                      if (note != null) Text(note, style: text.bodySmall?.copyWith(color: c.muted)),
                    ],
                  ),
                ),
                const SizedBox(width: SpaceTokens.s2),
                Text(
                  amount,
                  textDirection: context.amountDirection,
                  style: text.titleMedium?.copyWith(
                    fontFeatures: const [FontFeature.tabularFigures()],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsetsDirectional.symmetric(horizontal: SpaceTokens.s1),
    decoration: BoxDecoration(
      border: Border.all(color: context.colors.divider),
      borderRadius: BorderRadius.circular(RadiusTokens.sm),
    ),
    child: Text(text, style: Theme.of(context).textTheme.labelMedium),
  );
}

class _ArchivedRow extends StatelessWidget {
  const _ArchivedRow({
    required this.envelope,
    required this.first,
    required this.last,
    required this.onTap,
  });

  final Envelope envelope;
  final bool first;
  final bool last;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final figures = '${context.money(envelope.spent)} / ${context.money(envelope.amount)}';
    return GroupedRow(
      title: envelope.name,
      semanticLabel: '${envelope.name}, $figures',
      muted: true,
      first: first,
      last: last,
      onTap: onTap,
      lines: [RowLine(figures)],
    );
  }
}
