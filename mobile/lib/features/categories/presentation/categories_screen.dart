import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/presentation/accounts_screen.dart'
    show subjectAndMessage;
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/categories/domain/requests.dart';
import 'package:budget_owl/features/categories/presentation/category_sheet.dart';
import 'package:budget_owl/shared/widgets/grouped_row.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/write_failure.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart' show Bidi;
import 'package:material_ui/material_ui.dart';

/// How the household classifies what its money was for (categories.md, UI), and the payee rules
/// a weekly check-in leaves behind (weekly-check-in.md, UI, Rules).
///
/// - **Income and spending are separate groups**; children sit under their parent with a rule at
///   the start rather than a bullet, so the two levels read without relying on colour.
/// - **Deleting says your transactions are kept** — and, when it has children, that those go too.
///   That is the thing somebody is actually afraid of, answered before they commit.
/// - **Archived categories are behind a disclosure** that says how many there are.
/// - **Payee rules are listed and removable, never made here**: completing a check-in is the only
///   thing that writes one.
/// - **A `VIEWER` sees no write control.** The API refuses one regardless.
class CategoriesScreen extends ConsumerStatefulWidget {
  const CategoriesScreen({super.key});

  @override
  ConsumerState<CategoriesScreen> createState() => _CategoriesScreenState();
}

enum _Action { addChild, edit, archive, unarchive, delete }

class _CategoriesScreenState extends ConsumerState<CategoriesScreen> {
  bool _showArchived = false;
  bool _showRules = false;
  String? _notice;
  String? _actionError;
  String? _actionSubject;

  /// The rules section's own outcome, said beside it as the web does.
  bool _ruleRemoved = false;
  String? _ruleError;
  String? _busyRule;

  void _clear() {
    _notice = null;
    _actionError = null;
    _actionSubject = null;
    _ruleRemoved = false;
    _ruleError = null;
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

  Future<void> _run(Category category, Future<void> Function() action, String notice) async {
    setState(_clear);
    try {
      await action();
      _said(notice);
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) {
        setState(() {
          _actionError = writeFailureText(context, ref, error);
          _actionSubject = category.name;
        });
      }
    }
  }

  Future<void> _open({required List<Category> all, Category? editing, String? parentId}) async {
    setState(_clear);
    _said(await showCategorySheet(context, all: all, editing: editing, parentId: parentId));
  }

  Future<void> _rowActions(Category category, List<Category> all) async {
    final l = context.l10n;
    final online = ref.read(reachabilityProvider).canWrite;
    final choice = await showRowActions<_Action>(
      context,
      title: l.transactions_actionsFor(category.name),
      online: online,
      actions: [
        if (category.archived)
          RowAction(_Action.unarchive, Icons.unarchive_outlined, l.categories_unarchive)
        else ...[
          // Only a true top-level row: a promoted orphan has a parent, and a third level is refused.
          if (category.parentId == null)
            RowAction(_Action.addChild, Icons.subdirectory_arrow_right, l.categories_addChild),
          RowAction(_Action.edit, Icons.edit_outlined, l.common_edit),
          RowAction(_Action.archive, Icons.archive_outlined, l.categories_archive),
        ],
        RowAction(_Action.delete, Icons.delete_outline, l.categories_delete, danger: true),
      ],
    );
    if (choice == null || !mounted) return;
    final writer = ref.read(categoriesWriterProvider);
    switch (choice) {
      case _Action.addChild:
        await _open(all: all, parentId: category.id);
      case _Action.edit:
        await _open(all: all, editing: category);
      case _Action.archive:
        final confirmed = await confirmAction(
          context,
          heading: l.categories_archiveHeading(category.name),
          message: l.categories_archiveMessage,
          action: l.categories_archive,
        );
        if (confirmed) {
          await _run(category, () => writer.archive(category), l.categories_archived);
        }
      case _Action.unarchive:
        await _run(category, () => writer.unarchive(category), l.categories_unarchived);
      case _Action.delete:
        final children = all.where((c) => c.parentId == category.id).length;
        final confirmed = await confirmAction(
          context,
          heading: l.categories_deleteHeading(category.name),
          message: children > 0
              ? l.categories_deleteMessageWithChildren('$children')
              : l.categories_deleteMessage,
          action: l.categories_delete,
          destructive: true,
        );
        if (confirmed) {
          await _run(category, () => writer.delete(category), l.categories_deleted);
        }
    }
  }

  Future<void> _removeRule(PayeeRule rule) async {
    setState(() {
      _clear();
      _busyRule = rule.id;
    });
    try {
      await ref.read(categoriesWriterProvider).removeRule(rule);
      if (mounted) setState(() => _ruleRemoved = true);
    } on AppError catch (error) {
      if (mounted) setState(() => _ruleError = writeFailureText(context, ref, error));
    } finally {
      if (mounted) setState(() => _busyRule = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final canWrite = ref.watch(signedInProvider)?.membership?.role.canWrite ?? false;
    final online = canWriteNow(ref);
    final snapshot = ref.watch(categoriesProvider);
    final rules = ref.watch(payeeRulesProvider);
    final all = snapshot.data ?? const <Category>[];
    final hasActive = all.any((c) => !c.archived);
    final error = _actionError;

    return Screen(
      title: l.categories_title,
      onRefresh: () => Future.wait([
        ref.read(categoriesProvider.notifier).refresh(),
        ref.read(payeeRulesProvider.notifier).refresh(),
      ]),
      actions: [
        if (canWrite && hasActive)
          TextButton.icon(
            onPressed: online ? () => _open(all: all) : _offline,
            style: online ? null : TextButton.styleFrom(foregroundColor: c.muted),
            icon: const Icon(Icons.add),
            label: Text(l.categories_add),
          ),
      ],
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.categories_lede),
          if (_notice != null) Gutter(child: NoticeBanner(text: _notice!, success: true)),
          if (error != null)
            Gutter(child: NoticeBanner(text: subjectAndMessage(_actionSubject, error))),
          SnapshotView<List<Category>>(
            snapshot: snapshot,
            onRetry: () => ref.read(categoriesProvider.notifier).refresh(),
            builder: (context, categories, stale) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (stale != null) Gutter(child: stale),
                ..._tree(context, categories, canWrite: canWrite, online: online),
                // Created only by completing a weekly check-in; listed and removable here.
                _RulesSection(
                  snapshot: rules,
                  canWrite: canWrite,
                  online: online,
                  open: _showRules,
                  busyRule: _busyRule,
                  removed: _ruleRemoved,
                  error: _ruleError,
                  onToggle: () => setState(() => _showRules = !_showRules),
                  onRemove: online
                      ? _removeRule
                      : (_) => setState(() {
                          _clear();
                          _ruleError = writesOffNow(context, ref);
                        }),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  List<Widget> _tree(
    BuildContext context,
    List<Category> all, {
    required bool canWrite,
    required bool online,
  }) {
    final l = context.l10n;
    final active = all.where((c) => !c.archived).toList();
    final archived = all.where((c) => c.archived).toList()
      ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    void Function()? tap(Category category) => canWrite ? () => _rowActions(category, all) : null;

    return [
      Overline(l.categories_heading),
      if (active.isEmpty) ...[
        Lede(l.categories_emptyBody),
        if (canWrite)
          Gutter(
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: FilledButton(
                onPressed: online ? () => _open(all: all) : _offline,
                child: Text(l.categories_addFirst),
              ),
            ),
          )
        else
          Lede(l.categories_emptyReadOnly),
      ] else
        for (final group in groupByKind(active)) ...[
          _KindHeading(context.message(categoryKindKey(group.key))),
          ..._branchRows(group.value, tap),
        ],
      if (archived.isNotEmpty) ...[
        Overline(l.categories_archivedHeading),
        Gutter(
          child: Align(
            alignment: AlignmentDirectional.centerStart,
            child: OutlinedButton(
              onPressed: () => setState(() => _showArchived = !_showArchived),
              child: Text(
                _showArchived
                    ? l.categories_hideArchived('${archived.length}')
                    : l.categories_showArchived('${archived.length}'),
              ),
            ),
          ),
        ),
        if (_showArchived) ...[
          Lede(l.categories_archivedNote),
          for (var i = 0; i < archived.length; i++)
            GroupedRow(
              title: categoryTrail(context, archived[i]),
              semanticLabel: '${categoryTrail(context, archived[i])}, ${l.accounts_archivedTag}',
              muted: true,
              first: i == 0,
              last: i == archived.length - 1,
              onTap: tap(archived[i]),
            ),
        ],
      ],
    ];
  }

  List<Widget> _branchRows(List<CategoryBranch> branches, void Function()? Function(Category) tap) {
    final rows = <(Category, bool)>[
      for (final branch in branches) ...[
        (branch.parent, false),
        for (final child in branch.children) (child, true),
      ],
    ];
    return [
      for (var i = 0; i < rows.length; i++)
        GroupedRow(
          title: rows[i].$1.name,
          semanticLabel: rows[i].$2 ? categoryTrail(context, rows[i].$1) : rows[i].$1.name,
          indent: rows[i].$2,
          first: i == 0,
          last: i == rows.length - 1,
          onTap: tap(rows[i].$1),
        ),
    ];
  }
}

/// "Bills › Utilities": the phone's separator, which the bundled font draws and which mirrors in
/// right-to-left (mobile-app.md, Departures: the web's arrow drew as a box).
String categoryTrail(BuildContext context, Category category) {
  final parent = category.parentName;
  return parent == null
      ? category.name
      : context.l10n.transactions_categoryTrail(parent, category.name);
}

class _KindHeading extends StatelessWidget {
  const _KindHeading(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.fromSTEB(
      SpaceTokens.s4,
      SpaceTokens.s3,
      SpaceTokens.s4,
      SpaceTokens.s1,
    ),
    child: Semantics(
      header: true,
      child: Text(text, style: Theme.of(context).textTheme.titleSmall),
    ),
  );
}

/// The categories page's **Payee rules** disclosure: *"SQ \*BLUE BOTTLE — files under Eating
/// Out · Remove"*. A `VIEWER` sees the list with no Remove.
class _RulesSection extends StatelessWidget {
  const _RulesSection({
    required this.snapshot,
    required this.canWrite,
    required this.online,
    required this.open,
    required this.busyRule,
    required this.removed,
    required this.error,
    required this.onToggle,
    required this.onRemove,
  });

  final Snapshot<PayeeRules> snapshot;
  final bool canWrite;
  final bool online;
  final bool open;
  final String? busyRule;
  final bool removed;
  final String? error;
  final VoidCallback onToggle;
  final void Function(PayeeRule rule) onRemove;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final failure = error;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Overline(l.checkIn_rules_payeeHeading),
        Lede(l.checkIn_rules_payeeBody),
        if (removed) Gutter(child: NoticeBanner(text: l.checkIn_rules_removed, success: true)),
        if (failure != null) Gutter(child: NoticeBanner(text: failure)),
        SnapshotView<PayeeRules>(
          snapshot: snapshot,
          builder: (context, page, stale) {
            final rules = page.rules;
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (stale != null) Gutter(child: stale),
                if (rules.isEmpty)
                  Lede(l.checkIn_rules_payeeEmpty)
                else ...[
                  Gutter(
                    child: Align(
                      alignment: AlignmentDirectional.centerStart,
                      child: OutlinedButton(
                        onPressed: onToggle,
                        child: Text(
                          open
                              ? l.checkIn_rules_hide
                              : l.checkIn_rules_show('${page.totalElements}'),
                        ),
                      ),
                    ),
                  ),
                  if (open) ...[
                    for (var i = 0; i < rules.length; i++)
                      GroupedRow(
                        title: rules[i].payeeName,
                        titleDirection: Bidi.detectRtlDirectionality(rules[i].payeeName)
                            ? TextDirection.rtl
                            : TextDirection.ltr,
                        semanticLabel: [
                          rules[i].payeeName,
                          l.checkIn_rules_files(rules[i].categoryName),
                          if (rules[i].categoryArchived) l.checkIn_rules_archived,
                        ].join(', '),
                        first: i == 0,
                        last: i == rules.length - 1,
                        lines: [
                          RowLine(l.checkIn_rules_files(rules[i].categoryName)),
                          if (rules[i].categoryArchived)
                            RowLine(l.checkIn_rules_archived, emphasis: true),
                        ],
                        action: canWrite
                            ? TextButton(
                                onPressed: busyRule != null ? null : () => onRemove(rules[i]),
                                style: online
                                    ? null
                                    : TextButton.styleFrom(foregroundColor: c.muted),
                                child: Semantics(
                                  label: '${l.checkIn_rules_remove} ${rules[i].payeeName}',
                                  excludeSemantics: true,
                                  child: Text(l.checkIn_rules_remove),
                                ),
                              )
                            : null,
                      ),
                    if (page.totalElements > rules.length)
                      Lede(l.checkIn_rules_more('${page.totalElements - rules.length}')),
                  ],
                ],
              ],
            );
          },
        ),
      ],
    );
  }
}
