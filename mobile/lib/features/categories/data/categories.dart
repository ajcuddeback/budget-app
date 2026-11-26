import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// One category, as `CategoryResponse` sends it.
final class Category {
  const Category({
    required this.id,
    required this.name,
    required this.kind,
    required this.archived,
    this.parentId,
    this.parentName,
  });

  factory Category.fromJson(JsonMap json) => Category(
    id: json.string('id'),
    name: json.string('name'),
    kind: json.string('kind'),
    parentId: json.stringOrNull('parentId'),
    parentName: json.stringOrNull('parentName'),
    archived: json.boolean('archived'),
  );

  final String id;
  final String name;
  final String kind;
  final String? parentId;
  final String? parentName;
  final bool archived;
}

/// `GET /api/categories`, archived included so old rows keep their names.
final class CategoriesResource extends CachedResource<List<Category>> {
  @override
  String get path => '/api/categories';

  @override
  Map<String, String> get query => const {'includeArchived': 'true', 'page': '0', 'size': '200'};

  @override
  List<Category> decode(JsonMap json) => [
    for (final category in json.objects('content')) Category.fromJson(category),
  ];
}

final categoriesProvider =
    NotifierProvider.autoDispose<CategoriesResource, Snapshot<List<Category>>>(
      CategoriesResource.new,
    );

/// What a picker may offer, as the web's `pickableCategories`: unarchived, income before
/// expenses, each parent followed by its children, by name. A child whose parent is not offered
/// is promoted rather than dropped — losing a row from a financial screen is never right.
List<Category> pickableCategories(List<Category> all) {
  final open = all.where((c) => !c.archived).toList();
  int byName(Category a, Category b) {
    final names = a.name.toLowerCase().compareTo(b.name.toLowerCase());
    return names != 0 ? names : a.id.compareTo(b.id);
  }

  final ids = {for (final c in open) c.id};
  final out = <Category>[];
  for (final kind in const ['INCOME', 'EXPENSE']) {
    final ofKind = open.where((c) => c.kind == kind).toList();
    final parents = ofKind.where((c) => c.parentId == null || !ids.contains(c.parentId)).toList()
      ..sort(byName);
    for (final parent in parents) {
      out
        ..add(parent)
        ..addAll(ofKind.where((c) => c.parentId == parent.id).toList()..sort(byName));
    }
  }
  return out;
}
