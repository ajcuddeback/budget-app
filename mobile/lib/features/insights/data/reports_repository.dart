import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/features/insights/domain/reports.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// `GET /api/reports/income-and-spending?from=&to=`, cache-first. **`GET` only**: there is nothing
/// to write, and the server refuses every other method. `from` and `to` are always sent: only the
/// phone knows what "the last twelve months" means where its owner is (decision 10).
final class IncomeAndSpendingResource extends CachedResource<IncomeAndSpending> {
  IncomeAndSpendingResource(this.range);

  final ({String from, String to}) range;

  @override
  String get path => '/api/reports/income-and-spending';

  @override
  Map<String, String> get query => {'from': range.from, 'to': range.to};

  @override
  IncomeAndSpending decode(JsonMap json) => IncomeAndSpending.fromJson(json);
}

final incomeAndSpendingProvider = NotifierProvider.autoDispose
    .family<IncomeAndSpendingResource, Snapshot<IncomeAndSpending>, ({String from, String to})>(
      IncomeAndSpendingResource.new,
    );

/// `GET /api/reports/spending-by-category?from=M&to=M`, one month, against the month before.
final class SpendingByCategoryResource extends CachedResource<SpendingByCategory> {
  SpendingByCategoryResource(this.month);

  final String month;

  @override
  String get path => '/api/reports/spending-by-category';

  @override
  Map<String, String> get query => {'from': month, 'to': month};

  @override
  SpendingByCategory decode(JsonMap json) => SpendingByCategory.fromJson(json);
}

final spendingByCategoryProvider = NotifierProvider.autoDispose
    .family<SpendingByCategoryResource, Snapshot<SpendingByCategory>, String>(
      SpendingByCategoryResource.new,
    );
