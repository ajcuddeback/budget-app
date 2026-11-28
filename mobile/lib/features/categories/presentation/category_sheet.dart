import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/categories/domain/requests.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/validation.dart';
import 'package:budget_owl/shared/widgets/write_failure.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Opens the add or edit sheet on the root navigator. [parentId] pre-selects a parent ("Add a
/// sub-category" on a row). Returns the notice to show, or null when nothing was saved.
Future<String?> showCategorySheet(
  BuildContext context, {
  required List<Category> all,
  Category? editing,
  String? parentId,
}) => Navigator.of(context, rootNavigator: true).push<String>(
  MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) => CategorySheet(all: all, editing: editing, parentId: parentId),
  ),
);

/// Adding a category or correcting one — the web's dialog. **It disables rather than hides**:
///
/// - **A child's kind is its parent's**: choosing a parent replaces the kind question, because a
///   form that let the two disagree would be offering a `400`.
/// - **An existing category's kind is shown and disabled**, with the reason: it is fixed at
///   creation.
/// - **A category with children is not offered a parent**, and is told why: the server refuses a
///   third level with `409 category-depth-exceeded`.
class CategorySheet extends ConsumerStatefulWidget {
  const CategorySheet({required this.all, this.editing, this.parentId, super.key});

  final List<Category> all;
  final Category? editing;
  final String? parentId;

  @override
  ConsumerState<CategorySheet> createState() => _CategorySheetState();
}

class _CategorySheetState extends ConsumerState<CategorySheet> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  late String _kind;
  late String _parentId;
  String? _error;
  bool _busy = false;

  Category? get _editing => widget.editing;

  bool get _hasChildren {
    final editing = _editing;
    return editing != null && widget.all.any((c) => c.parentId == editing.id);
  }

  @override
  void initState() {
    super.initState();
    final editing = _editing;
    _name.text = editing?.name ?? '';
    _kind = editing?.kind ?? 'EXPENSE';
    _parentId = editing?.parentId ?? widget.parentId ?? '';
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  /// The kind the form is effectively on: a child's is its parent's, not the control's.
  String get _effectiveKind {
    if (_parentId.isEmpty) return _kind;
    for (final c in widget.all) {
      if (c.id == _parentId) return c.kind;
    }
    return _kind;
  }

  Future<void> _save() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final form = CategoryForm(name: _name.text, kind: _effectiveKind, parentId: _parentId);
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final writer = ref.read(categoriesWriterProvider);
      final editing = _editing;
      if (editing == null) {
        await writer.create(form);
        if (mounted) Navigator.of(context).pop(l.categories_created);
      } else {
        // An untouched form closes without a request: a PATCH would bump the version for nothing.
        final sent = await writer.update(editing, form);
        if (mounted) Navigator.of(context).pop(sent ? l.categories_updated : null);
      }
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) setState(() => _error = writeFailureText(context, ref, error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final editing = _editing;
    final online = canWriteNow(ref);
    final error = _error;
    final kindLocked = editing != null || _parentId.isNotEmpty;
    final parents = possibleParents(widget.all, _effectiveKind, excluding: editing?.id);
    final current = _parentId.isEmpty || parents.any((p) => p.id == _parentId)
        ? null
        : widget.all.where((c) => c.id == _parentId).firstOrNull;
    const gap = SizedBox(height: SpaceTokens.s4);

    return Scaffold(
      appBar: AppBar(
        title: Text(editing == null ? l.categories_addTitle : l.categories_editTitle, maxLines: 2),
        leading: IconButton(
          icon: const Icon(Icons.close),
          tooltip: l.common_cancel,
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: Form(
          key: _form,
          child: ListView(
            padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
            children: [
              if (error != null) ...[NoticeBanner(text: error), gap],
              TextFormField(
                controller: _name,
                maxLength: 60,
                decoration: InputDecoration(labelText: l.categories_name),
                validator: (v) =>
                    Rules.required(l, v ?? '') ??
                    ((v ?? '').trim().length > 60 ? l.validation_maxLength('60') : null),
              ),
              gap,
              DropdownButtonFormField<String>(
                key: ValueKey('parent-$_effectiveKind'),
                initialValue: _parentId,
                isExpanded: true,
                // Not dense: a dense one is a fixed height, which cut the chosen parent mid-word at
                // large text. This one grows.
                isDense: false,
                decoration: InputDecoration(
                  labelText: l.categories_parent,
                  helperText: _hasChildren ? l.categories_hasChildrenHint : l.categories_parentHint,
                  helperMaxLines: 10,
                ),
                items: [
                  DropdownMenuItem(value: '', child: Text(l.categories_noParent)),
                  for (final parent in parents)
                    DropdownMenuItem(value: parent.id, child: Text(parent.name)),
                  // A parent since archived keeps its place in this form, so it can be shown.
                  if (current != null)
                    DropdownMenuItem(value: current.id, child: Text(current.name)),
                ],
                // Said rather than hidden: a category with children cannot sit under another.
                onChanged: _hasChildren ? null : (value) => setState(() => _parentId = value ?? ''),
              ),
              gap,
              if (kindLocked)
                // Shown, disabled, with the reason — not hidden, and not a control that only
                // looks like one (categories.md, UI: "disables rather than hides").
                InputDecorator(
                  decoration: InputDecoration(
                    labelText: l.categories_kind,
                    enabled: false,
                    helperText: editing != null
                        ? l.categories_kindFixedHint
                        : l.categories_kindFromParentHint,
                    helperMaxLines: 10,
                  ),
                  child: Text(
                    context.message(categoryKindKey(_effectiveKind)),
                    style: TextStyle(color: Theme.of(context).disabledColor),
                  ),
                )
              else ...[
                Text(l.categories_kind, style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: SpaceTokens.s2),
                SegmentedButton<String>(
                  segments: [
                    for (final kind in categoryKinds)
                      ButtonSegment(
                        value: kind,
                        label: Text(context.message(categoryKindKey(kind))),
                      ),
                  ],
                  selected: {_kind},
                  showSelectedIcon: false,
                  onSelectionChanged: (selected) => setState(() => _kind = selected.first),
                ),
              ],
              gap,
              FilledButton(
                onPressed: _busy || !online ? null : _save,
                child: Text(_busy ? l.common_saving : l.common_save),
              ),
              const OfflineNote(),
            ],
          ),
        ),
      ),
    );
  }
}
