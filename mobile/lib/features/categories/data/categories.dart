import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/categories/domain/requests.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// One category, as `CategoryResponse` sends it. **The tree arrives flat**: each row carries its
/// `parentId`, and the client assembles the two levels (categories.md, API).
final class Category {
  const Category({
    required this.id,
    required this.name,
    required this.kind,
    required this.archived,
    this.parentId,
    this.parentName,
    this.version = 0,
  });

  factory Category.fromJson(JsonMap json) => Category(
    id: json.string('id'),
    name: json.string('name'),
    kind: json.string('kind'),
    parentId: json.stringOrNull('parentId'),
    parentName: json.stringOrNull('parentName'),
    archived: json.boolean('archived'),
    version: json.integer('version'),
  );

  final String id;
  final String name;
  final String kind;
  final String? parentId;
  final String? parentName;
  final bool archived;

  /// What a `PATCH` must send back, or the optimistic lock cannot work.
  final int version;
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

/// One payee rule, as `GET /api/payee-rules` sends it: *"SQ \*BLUE BOTTLE → Eating Out"*.
///
/// Created only by completing a weekly check-in; this app lists and removes them, nothing else.
final class PayeeRule {
  const PayeeRule({
    required this.id,
    required this.payeeName,
    required this.categoryName,
    required this.categoryArchived,
  });

  factory PayeeRule.fromJson(JsonMap json) => PayeeRule(
    id: json.string('id'),
    payeeName: json.string('payeeName'),
    categoryName: json.string('categoryName'),
    categoryArchived: json.boolean('categoryArchived'),
  );

  final String id;

  /// From a bank file: any script, so it is drawn in its own direction.
  final String payeeName;
  final String categoryName;

  /// A rule on an archived category suggests nothing until the category is restored.
  final bool categoryArchived;
}

final class PayeeRules {
  const PayeeRules(this.rules, {required this.totalElements});

  final List<PayeeRule> rules;
  final int totalElements;
}

/// `GET /api/payee-rules`, one page of 200, by payee name — the web's request.
final class PayeeRulesResource extends CachedResource<PayeeRules> {
  @override
  String get path => '/api/payee-rules';

  @override
  Map<String, String> get query => const {'page': '0', 'size': '200'};

  @override
  PayeeRules decode(JsonMap json) => PayeeRules([
    for (final rule in json.objects('content')) PayeeRule.fromJson(rule),
  ], totalElements: json.integer('totalElements'));
}

final payeeRulesProvider = NotifierProvider.autoDispose<PayeeRulesResource, Snapshot<PayeeRules>>(
  PayeeRulesResource.new,
);

/// Every write the categories screen makes — `TransactionsWriter`'s pattern: sent only when the
/// last request reached the server, never queued, and followed by every screen re-reading.
final class CategoriesWriter {
  CategoriesWriter(this._ref);

  final Ref _ref;

  ApiClient get _api {
    final api = _ref.read(apiClientProvider);
    if (api == null) throw const AppError.unexpected();
    return api;
  }

  Future<JsonMap?> _write(String method, String path, [Object? body]) async {
    if (!_ref.read(reachabilityProvider).canWrite) throw const AppError.offline();
    final result = await _api.write(method, path, body: body);
    _ref.read(dataRevisionProvider.notifier).bump();
    return result;
  }

  static String _one(String id) => '/api/categories/${Uri.encodeComponent(id)}';

  Future<void> create(CategoryForm form) =>
      _write('POST', '/api/categories', createCategoryBody(form));

  /// Sends the patch, or nothing when nothing changed. Returns whether anything was sent.
  Future<bool> update(Category original, CategoryForm form) async {
    final patch = categoryPatch(original, form);
    if (patchIsEmpty(patch)) return false;
    await _write('PATCH', _one(original.id), patch);
    return true;
  }

  Future<void> archive(Category category) => _write('POST', '${_one(category.id)}/archive');

  Future<void> unarchive(Category category) => _write('POST', '${_one(category.id)}/unarchive');

  /// Its children go with it; its transactions stay, needing a category again (categories.md 6).
  Future<void> delete(Category category) => _write('DELETE', _one(category.id));

  Future<void> removeRule(PayeeRule rule) =>
      _write('DELETE', '/api/payee-rules/${Uri.encodeComponent(rule.id)}');
}

final categoriesWriterProvider = Provider<CategoriesWriter>(CategoriesWriter.new);
