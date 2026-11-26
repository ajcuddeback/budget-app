import 'dart:async';

import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/check_in/data/check_in_repository.dart';
import 'package:budget_owl/features/check_in/domain/check_in.dart';
import 'package:budget_owl/features/check_in/domain/requests.dart';
import 'package:budget_owl/features/check_in/presentation/check_in_sheets.dart';
import 'package:budget_owl/features/check_in/presentation/check_in_steps.dart';
import 'package:budget_owl/features/check_in/presentation/check_in_text.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

/// The weekly check-in tab (weekly-check-in.md): one step per screen, as the mobile artboards
/// draw it, over the week the server says is current.
///
/// - **Every answer is saved as it is given**, one request each, and the week is read again, so
///   every figure shown is the server's. Back and forward never lose an answer.
/// - **A stale refusal re-reads and marks each item with its reason**, opening the first one's
///   step. Nothing still valid is cleared.
/// - **A `VIEWER` sees every step, item, answer and figure and no control.** The server refuses
///   the writes regardless; a forced one shows the translated `403`.
/// - **Offline, every control is disabled** with the offline note, and nothing is queued.
class CheckInScreen extends ConsumerStatefulWidget {
  const CheckInScreen({super.key});

  @override
  ConsumerState<CheckInScreen> createState() => _CheckInScreenState();
}

class _CheckInScreenState extends ConsumerState<CheckInScreen> implements CheckInActions {
  /// Another week's draft, opened from `409 check-in-draft-open`; null is the current week.
  String? _requested;

  /// Which shown step is on screen.
  int _screen = 0;
  bool _busy = false;
  AppError? _actionError;
  String? _notice;
  List<StaleItem> _stale = const [];

  /// Another week's unfinished draft, from `409 check-in-draft-open`.
  String? _draftOpen;

  /// The debt-plan prompt's tick, per item, before the answer it belongs to is given.
  final Map<String, bool> _prompts = {};

  final GlobalKey _topKey = GlobalKey();
  final GlobalKey _progressKey = GlobalKey();

  Snapshot<Week> get _snapshot => ref.read(checkInProvider(_requested));

  Week? get _week => _snapshot.data;

  bool get _canWrite => ref.read(signedInProvider)?.membership?.role.canWrite ?? false;

  bool get _online => ref.read(reachabilityProvider).canWrite;

  @override
  bool get editable => _canWrite && _week?.status == CheckInStatus.draft;

  @override
  bool get enabled => editable && _online && !_busy;

  Future<void> _reread() => ref.read(checkInProvider(_requested).notifier).refresh();

  /// One write at a time; every refusal is read for what it means.
  Future<void> _run(Future<void> Function() write, {bool reread = true}) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _actionError = null;
      _notice = null;
    });
    try {
      await write();
      if (reread) await _reread();
    } on AppError catch (error) {
      await _refused(error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// A refusal a re-read answers: someone else answered first, finished the week, or the draft
  /// moved. Anything else is shown as it came.
  Future<void> _refused(AppError error) async {
    final l = context.l10n;
    switch (error.code) {
      case 'stale-version':
        setState(() => _notice = l.checkIn_answerConflict);
        await _reread();
      case 'check-in-completed' || 'not-found':
        setState(() => _actionError = error);
        await _reread();
      case 'check-in-stale':
        setState(() => _stale = staleItems(error.params));
        await _reread();
        _showFirstStale();
      case 'check-in-draft-open':
        final open = error.params['week'];
        if (open is String && weekPattern.hasMatch(open)) {
          setState(() => _draftOpen = open);
        } else {
          setState(() => _actionError = error);
        }
      default:
        setState(() => _actionError = error);
    }
  }

  void _showFirstStale() {
    final week = _week;
    if (week == null || !mounted) return;
    String? first;
    for (final item in _stale) {
      if (item.itemKey != null) {
        first = item.itemKey;
        break;
      }
    }
    final index = first == null
        ? week.steps.length - 1
        : week.steps.indexWhere((s) => s.items.any((i) => i.itemKey == first));
    setState(() => _screen = index < 0 ? 0 : index);
    _reveal(_topKey);
  }

  void _forget(String itemKey) {
    _stale = [
      for (final item in _stale)
        if (item.itemKey != itemKey) item,
    ];
  }

  // ------------------------------------------------------------------------------- actions

  @override
  String? staleFor(Item item) {
    for (final stale in _stale) {
      if (stale.itemKey == item.itemKey) return CheckInWords(context).stale(stale.reason);
    }
    return null;
  }

  @override
  bool prompted(Item item) => _prompts[item.itemKey] ?? item.answer?.recordDebtPayment ?? false;

  @override
  void answer(Item item, Body body) {
    final week = _week;
    if (week == null || !enabled) return;
    unawaited(
      _run(() async {
        await ref.read(checkInWriterProvider).answer(week.week, item, body);
        _forget(item.itemKey);
      }),
    );
  }

  @override
  void togglePrompt(Item item, {required bool value}) {
    setState(() => _prompts[item.itemKey] = value);
    final given = item.answer;
    if (given?.choice == AnswerChoice.markPaid) {
      answer(item, markPaid(recordDebtPayment: value));
    } else if (given?.choice == AnswerChoice.attach &&
        given?.billId != null &&
        given?.dueDate != null) {
      answer(
        item,
        attach(
          billId: given!.billId!,
          dueDate: given.dueDate!,
          remember: given.remember,
          recordDebtPayment: value,
        ),
      );
    }
  }

  @override
  Future<void> pickCategory(Item item) async {
    final week = _week;
    if (week == null || !enabled) return;
    final categories = ref.read(categoriesProvider).data ?? const <Category>[];
    final pick = await showCategorySheet(context, item: item, categories: categories);
    if (pick == null || !mounted) return;
    if (pick.clear) {
      if (item.answer == null) return;
      await _run(() async {
        await ref.read(checkInWriterProvider).clear(week.week, item);
        _forget(item.itemKey);
      });
      return;
    }
    answer(item, category(pick.categoryId!, remember: item.answer?.remember ?? false));
  }

  @override
  void toggleRemember(Item item, {required bool value}) {
    final chosen = item.chosenCategoryId;
    if (chosen != null) answer(item, category(chosen, remember: value));
  }

  @override
  Future<void> pickBill(Item item) async {
    final choices = _week?.stepNamed(StepName.bills)?.billChoices ?? const <BillChoice>[];
    if (!enabled || choices.isEmpty) return;
    final pick = await showBillSheet(context, item: item, choices: choices);
    if (pick == null || !mounted) return;
    answer(
      item,
      attach(
        billId: pick.billId,
        dueDate: pick.dueDate,
        remember: pick.remember,
        // The prompt belongs to the bill the server named; a different bill carries none.
        recordDebtPayment: pick.billId == item.billId && prompted(item),
      ),
    );
  }

  @override
  void allocate(Choice choice) {
    final step = _week?.stepNamed(StepName.nextWeek);
    final item = step?.allocation;
    final surplus = step?.figures?.surplusWire;
    if (item == null || surplus == null) return;
    // The server's own surplus, sent back so it can tell the person saw the same figure.
    final body = allocation(choice, surplus);
    if (body != null) answer(item, body);
  }

  @override
  void finish() {
    final week = _week;
    if (week == null || !enabled || week.version == null || week.basis == null) return;
    unawaited(
      _run(() async {
        await ref.read(checkInWriterProvider).complete(week);
        if (mounted) {
          setState(() {
            _stale = const [];
            _screen = 0;
          });
        }
      }),
    );
  }

  @override
  Future<void> discard() async {
    final week = _week;
    if (week == null || !enabled) return;
    final l = context.l10n;
    final confirmed = await confirmAction(
      context,
      heading: l.checkIn_discardTitle,
      message: l.checkIn_discardMessage,
      action: l.checkIn_discard,
      destructive: true,
    );
    if (!confirmed || !mounted) return;
    await _run(() async {
      await ref.read(checkInWriterProvider).discard(week.week);
      if (mounted) {
        setState(() {
          _notice = l.checkIn_discarded;
          _stale = const [];
          _screen = 0;
        });
      }
    });
    // _run cleared the notice before the write; say what happened once it is done.
    if (mounted && _actionError == null) setState(() => _notice = l.checkIn_discarded);
  }

  void _start() {
    final week = _week;
    if (week == null || !_canWrite || !_online) return;
    unawaited(_run(() => ref.read(checkInWriterProvider).start(week.week)));
  }

  /// Discards the other week's draft that blocks this one; the person then starts this one.
  Future<void> _discardOpenDraft() async {
    final open = _draftOpen;
    if (open == null || !_canWrite) return;
    final l = context.l10n;
    await _run(() async {
      await ref.read(checkInWriterProvider).discard(open);
      if (mounted) setState(() => _draftOpen = null);
    });
    if (mounted && _actionError == null) setState(() => _notice = l.checkIn_discarded);
  }

  void _openWeek(String? week) => setState(() {
    _requested = week;
    _draftOpen = null;
    _stale = const [];
    _notice = null;
    _actionError = null;
    _screen = 0;
  });

  @override
  void open(String route) => context.go(route);

  void _go(int index, int shown) {
    setState(() => _screen = index.clamp(0, shown == 0 ? 0 : shown - 1));
    _reveal(_progressKey);
  }

  /// Brings [key]'s widget to the top once the new step is laid out: a step opens at its start,
  /// as the web's scrolls to the top, never half-way down the one before.
  void _reveal(GlobalKey key) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final target = key.currentContext;
      if (target != null && target.mounted) {
        Scrollable.ensureVisible(target);
      }
    });
  }

  // --------------------------------------------------------------------------------- build

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    // Watched so a change of role, connection or categories rebuilds what is offered.
    ref
      ..watch(signedInProvider)
      ..watch(reachabilityProvider)
      ..watch(categoriesProvider);
    final snapshot = ref.watch(checkInProvider(_requested));
    final week = snapshot.data;
    return Screen(
      title: week == null ? l.title_checkIn : l.checkIn_title('${week.number}'),
      onRefresh: _reread,
      body: SnapshotView<Week>(snapshot: snapshot, onRetry: _reread, builder: _body),
    );
  }

  Widget _body(BuildContext context, Week week, Widget? stale) {
    final l = context.l10n;
    final words = CheckInWords(context);
    final text = Theme.of(context).textTheme;
    final steps = week.steps;
    final screen = _screen.clamp(0, steps.isEmpty ? 0 : steps.length - 1);
    final general = {
      for (final item in _stale)
        if (item.itemKey == null) item.reason,
    };
    final soFar = week.stepNamed(StepName.nextWeek)?.figures?.soFar ?? false;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (_requested != null)
          Gutter(
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: TextButton.icon(
                onPressed: () => _openWeek(null),
                icon: const Icon(Icons.arrow_back),
                label: Text(l.checkIn_backToCurrent),
              ),
            ),
          ),
        ?(stale == null ? null : Gutter(child: stale)),
        KeyedSubtree(key: _topKey, child: Overline(l.checkIn_eyebrowShort('${week.number}'))),
        Gutter(
          child: Wrap(
            spacing: SpaceTokens.s2,
            runSpacing: SpaceTokens.s1,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Tag(words.status(week.status)),
              if (week.status != CheckInStatus.completed)
                Text(
                  l.checkIn_progress(words.count(week.answeredSteps), words.count(steps.length)),
                  style: text.bodyMedium,
                ),
            ],
          ),
        ),
        if (week.status != CheckInStatus.completed) Lede(l.checkIn_keptAsYouGo),
        if (_actionError != null)
          Gutter(
            child: NoticeBanner(
              text: _actionError!.isConnectivity
                  ? writesOffText(l, ref.read(reachabilityProvider))
                  : context.errorMessage(_actionError!),
              icon: Icons.error_outline,
            ),
          ),
        if (_notice != null) Gutter(child: NoticeBanner(text: _notice!, success: true)),
        if (_draftOpen != null) Gutter(child: _draftOpenBanner(context, _draftOpen!)),
        if (_stale.isNotEmpty)
          Gutter(
            child: NoticeBanner(
              icon: Icons.error_outline,
              text: [l.checkIn_staleIntro, for (final r in general) words.stale(r)].join('\n'),
            ),
          ),
        if (week.otherCurrencyRows > 0)
          Lede(l.checkIn_otherCurrencies(words.count(week.otherCurrencyRows))),
        if (week.status == CheckInStatus.completed)
          _completed(context, week, words)
        else ...[
          if (week.status == CheckInStatus.notStarted)
            _notStarted(context)
          else if (!_canWrite)
            Lede(l.checkIn_readOnly),
          // Why the controls are off — unless the stale label above already says "offline"
          // (one banner, not two: phase 1's G2).
          if (editable && !_online && stale == null) const Gutter(child: OfflineNote()),
          if (steps.isNotEmpty) ...[
            _Progress(key: _progressKey, current: screen, total: steps.length),
            StepSection(
              key: ValueKey(steps[screen].step),
              step: steps[screen],
              number: screen + 1,
              soFar: soFar,
              billChoices: week.stepNamed(StepName.bills)?.billChoices ?? const [],
              actions: this,
            ),
            _footer(context, words, steps, screen),
          ],
        ],
      ],
    );
  }

  Widget _draftOpenBanner(BuildContext context, String open) {
    final l = context.l10n;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        NoticeBanner(text: l.checkIn_draftOpen(open), icon: Icons.info_outline),
        Wrap(
          spacing: SpaceTokens.s2,
          children: [
            TextButton(onPressed: () => _openWeek(open), child: Text(l.checkIn_draftOpenOpen)),
            TextButton(
              onPressed: _busy || !_online ? null : _discardOpenDraft,
              child: Text(l.checkIn_draftOpenDiscard),
            ),
          ],
        ),
      ],
    );
  }

  Widget _notStarted(BuildContext context) {
    final l = context.l10n;
    return Gutter(
      child: Card(
        child: Padding(
          padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Semantics(
                header: true,
                child: Text(l.checkIn_notStarted, style: Theme.of(context).textTheme.titleLarge),
              ),
              const SizedBox(height: SpaceTokens.s2),
              if (_canWrite) ...[
                Text(l.checkIn_notStartedBody),
                const SizedBox(height: SpaceTokens.s3),
                Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: FilledButton(
                    onPressed: _busy || !_online ? null : _start,
                    child: Text(l.checkIn_start),
                  ),
                ),
                const OfflineNote(),
              ] else
                Text(l.checkIn_notStartedReadOnly),
            ],
          ),
        ),
      ),
    );
  }

  Widget _completed(BuildContext context, Week week, CheckInWords words) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final by = week.completedBy?.name;
    final at = week.completedAt;
    return Gutter(
      child: Card(
        child: Padding(
          padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Semantics(header: true, child: Text(l.checkIn_completed, style: text.titleLarge)),
              if (at != null) Muted(l.checkIn_completedBy(by ?? '', words.instant(at))),
              Muted(l.checkIn_completedLede),
              const SizedBox(height: SpaceTokens.s2),
              // The sentence is the link: "Open" five times over says nothing to a screen reader.
              for (final line in words.summary(week))
                if (line.route == null)
                  Padding(
                    padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
                    child: Text(line.text, style: text.bodyLarge),
                  )
                else
                  ListTile(
                    contentPadding: EdgeInsetsDirectional.zero,
                    title: Text(line.text),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () => open(line.route!),
                  ),
            ],
          ),
        ),
      ),
    );
  }

  /// Each screen ends by naming the next one, so the bar is never the only thing saying where
  /// you are.
  Widget _footer(BuildContext context, CheckInWords words, List<CheckInStep> steps, int screen) =>
      Padding(
        padding: const EdgeInsetsDirectional.fromSTEB(
          SpaceTokens.s4,
          SpaceTokens.s4,
          SpaceTokens.s4,
          SpaceTokens.s2,
        ),
        child: Wrap(
          alignment: WrapAlignment.spaceBetween,
          spacing: SpaceTokens.s2,
          runSpacing: SpaceTokens.s2,
          children: [
            if (screen > 0)
              OutlinedButton(
                onPressed: () => _go(screen - 1, steps.length),
                child: Text(context.l10n.checkIn_back),
              ),
            if (screen < steps.length - 1)
              FilledButton(
                onPressed: () => _go(screen + 1, steps.length),
                child: Text(words.nextStep(steps[screen + 1].step)),
              ),
          ],
        ),
      );
}

/// A segment per shown step, filled to the one on screen.
class _Progress extends StatelessWidget {
  const _Progress({required this.current, required this.total, super.key});

  final int current;
  final int total;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final words = CheckInWords(context);
    return Semantics(
      label: context.l10n.checkIn_progressBar(words.count(current + 1), words.count(total)),
      child: ExcludeSemantics(
        child: Padding(
          padding: const EdgeInsetsDirectional.fromSTEB(
            SpaceTokens.s4,
            SpaceTokens.s2,
            SpaceTokens.s4,
            SpaceTokens.s4,
          ),
          child: Row(
            children: [
              for (var i = 0; i < total; i++) ...[
                if (i > 0) const SizedBox(width: SpaceTokens.s1),
                Expanded(
                  child: Container(
                    height: SpaceTokens.s1,
                    decoration: BoxDecoration(
                      color: i <= current ? c.primary : c.divider,
                      borderRadius: BorderRadius.circular(RadiusTokens.sm),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
