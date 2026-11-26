import 'package:budget_owl/features/categories/data/categories.dart';

/// Income first, then spending — the order the screens were designed in.
const List<String> categoryKinds = ['INCOME', 'EXPENSE'];

/// The web's message key for a kind; an unknown one (a newer server) gets a neutral word.
String categoryKindKey(String kind) =>
    categoryKinds.contains(kind) ? 'categories.kind.$kind' : 'categories.kind.unknown';

/// A top-level category and its children: how the screen and every picker read the tree.
final class CategoryBranch {
  const CategoryBranch(this.parent, this.children);

  final Category parent;
  final List<Category> children;
}

int _byName(Category a, Category b) {
  final names = a.name.toLowerCase().compareTo(b.name.toLowerCase());
  return names != 0 ? names : a.id.compareTo(b.id);
}

/// The flat list assembled into its two levels, as the web's `buildCategoryTree`.
///
/// **A child whose parent is not in the list is promoted rather than dropped**: an archived parent
/// with unarchived children is ordinary, and hiding a category that still has transactions filed
/// under it is worse than showing it a level up.
List<CategoryBranch> buildCategoryTree(List<Category> categories) {
  final ids = {for (final c in categories) c.id};
  final parents = categories.where((c) => c.parentId == null || !ids.contains(c.parentId)).toList()
    ..sort(_byName);
  return [
    for (final parent in parents)
      CategoryBranch(
        parent,
        categories.where((c) => c.parentId == parent.id).toList()..sort(_byName),
      ),
  ];
}

/// Each kind's branches, income first; a kind with none is left out.
List<MapEntry<String, List<CategoryBranch>>> groupByKind(List<Category> categories) => [
  for (final kind in categoryKinds)
    if (categories.any((c) => c.kind == kind))
      MapEntry(kind, buildCategoryTree(categories.where((c) => c.kind == kind).toList())),
];

/// What may be chosen as a parent: top-level, unarchived, of [kind], and not [excluding] itself.
/// A child cannot be a parent, and the server refuses a third level (`category-depth-exceeded`).
List<Category> possibleParents(List<Category> all, String kind, {String? excluding}) =>
    all
        .where((c) => c.parentId == null && !c.archived && c.kind == kind && c.id != excluding)
        .toList()
      ..sort(_byName);

/// What the add and edit sheet holds. [parentId] `''` is "no parent": a top-level category.
final class CategoryForm {
  const CategoryForm({required this.name, required this.kind, this.parentId = ''});

  final String name;
  final String kind;
  final String parentId;
}

/// `POST /api/categories`, exactly as the web builds it: a top-level category states its kind, a
/// child names its parent and **no kind** — it takes its parent's, and one that disagreed would be
/// a `400` rather than a silent preference for either.
Map<String, Object?> createCategoryBody(CategoryForm form) => {
  'name': form.name.trim(),
  if (form.parentId.isEmpty) 'kind': form.kind else 'parentId': form.parentId,
};

/// The patch for an edit: the version, and only what changed. There is no `kind` — it is fixed at
/// creation, because re-kinding a category in use would reinterpret everything filed under it.
///
/// `parentId` is three-way: absent keeps the parent, `null` moves it to the top level, an id moves
/// it under that one. Sending it unchanged would be harmless; *sending null for "unchanged"* would
/// promote every child anybody renamed, which is why absent is built by not adding the key.
Map<String, Object?> categoryPatch(Category original, CategoryForm form) {
  final patch = <String, Object?>{'version': original.version};
  final name = form.name.trim();
  if (name != original.name) patch['name'] = name;
  final wanted = form.parentId.isEmpty ? null : form.parentId;
  if (wanted != original.parentId) patch['parentId'] = wanted;
  return patch;
}

/// Whether a patch would change anything beyond the version it carries.
bool patchIsEmpty(Map<String, Object?> patch) => patch.length == 1;
