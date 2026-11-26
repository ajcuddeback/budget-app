import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/features/debt/domain/requests.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// `GET /api/debts?includeArchived=false` — every debt, with its derived balance, rung, payment and
/// clear date. No household id anywhere: the server resolves it (ADR-0017).
final class DebtsResource extends CachedResource<DebtList> {
  @override
  String get path => '/api/debts';

  @override
  Map<String, String> get query => const {'includeArchived': 'false'};

  @override
  DebtList decode(JsonMap json) => DebtList.fromJson(json);
}

final debtsProvider = NotifierProvider.autoDispose<DebtsResource, Snapshot<DebtList>>(
  DebtsResource.new,
);

/// `GET /api/debt-plan` — the strategy, the amount, the order and **both** projections.
final class DebtPlanResource extends CachedResource<Plan> {
  @override
  String get path => '/api/debt-plan';

  @override
  Plan decode(JsonMap json) => Plan.fromJson(json);
}

final debtPlanProvider = NotifierProvider.autoDispose<DebtPlanResource, Snapshot<Plan>>(
  DebtPlanResource.new,
);

/// `GET /api/debt-plan/projection?monthlyAmount=` — the what-if. **A read that writes nothing**:
/// the plan re-run with fifty more a month. Its amount is the household's own monthly figure, which
/// the page already shows; moving it to a body would refuse it to a `VIEWER` (debt-plan.md).
final class WhatIfResource extends CachedResource<Plan> {
  WhatIfResource(this.monthlyAmount);

  final String monthlyAmount;

  @override
  String get path => '/api/debt-plan/projection';

  @override
  Map<String, String> get query => {'monthlyAmount': monthlyAmount};

  @override
  Plan decode(JsonMap json) => Plan.fromJson(json);
}

final whatIfProvider = NotifierProvider.autoDispose.family<WhatIfResource, Snapshot<Plan>, String>(
  WhatIfResource.new,
);

/// `GET /api/debts/{id}` — the terms with their sources, and the chart's two lines.
final class DebtDetailResource extends CachedResource<DebtDetail> {
  DebtDetailResource(this.id);

  final String id;

  @override
  String get path => '/api/debts/${Uri.encodeComponent(id)}';

  @override
  DebtDetail decode(JsonMap json) => DebtDetail.fromJson(json);
}

final debtDetailProvider = NotifierProvider.autoDispose
    .family<DebtDetailResource, Snapshot<DebtDetail>, String>(DebtDetailResource.new);

/// `GET /api/debts/{id}/payments?page=0&size=50` — the first page of the history, the only one
/// cached.
final class DebtPaymentsResource extends CachedResource<DebtPaymentPage> {
  DebtPaymentsResource(this.id);

  final String id;

  @override
  String get path => '/api/debts/${Uri.encodeComponent(id)}/payments';

  @override
  Map<String, String> get query => const {'page': '0', 'size': '50'};

  @override
  DebtPaymentPage decode(JsonMap json) => DebtPaymentPage.fromJson(json);
}

final debtPaymentsProvider = NotifierProvider.autoDispose
    .family<DebtPaymentsResource, Snapshot<DebtPaymentPage>, String>(DebtPaymentsResource.new);

/// Every write the debt screens make, each sent only when the last request reached the server.
/// **Nothing is queued**; after a success every screen re-reads. Each body is the web's.
final class DebtWriter {
  DebtWriter(this._ref);

  final Ref _ref;

  Future<JsonMap?> _send(String method, String path, Object? body, {required bool bump}) async {
    if (!_ref.read(reachabilityProvider).canWrite) throw const AppError.offline();
    final api = _ref.read(apiClientProvider);
    if (api == null) throw const AppError.unexpected();
    final result = await api.write(method, path, body: body);
    if (bump) _ref.read(dataRevisionProvider.notifier).bump();
    return result;
  }

  static String _debt(String id) => '/api/debts/${Uri.encodeComponent(id)}';

  Future<void> create(DebtForm form) =>
      _send('POST', '/api/debts', createDebtBody(form), bump: true);

  /// Sends the patch, or nothing when nothing changed. Returns the patch sent, or null.
  Future<Map<String, Object?>?> update(Debt original, DebtForm form) async {
    final patch = debtPatch(original, form);
    if (patchIsEmpty(patch)) return null;
    await _send('PATCH', _debt(original.id), patch, bump: true);
    return patch;
  }

  /// Logs a payment, returning the split **the server** recorded. Writes no transaction and moves
  /// no account balance (ADR-0035).
  Future<DebtPayment> logPayment(Debt debt, PaymentForm form) async {
    final body = await _send(
      'POST',
      '${_debt(debt.id)}/payments',
      logPaymentBody(form),
      bump: true,
    );
    if (body == null) throw const AppError.unexpected();
    try {
      return DebtPayment.fromJson(body);
    } on UnexpectedJson {
      throw const AppError.unexpected();
    }
  }

  /// What logging [amount] **would** record, writing nothing — so nothing re-reads after it.
  /// Read strictly: anything not shaped like a preview is no preview.
  Future<PaymentPreview> preview(Debt debt, String amount) async {
    final body = await _send(
      'POST',
      '${_debt(debt.id)}/payments/preview',
      previewBody(amount),
      bump: false,
    );
    if (body == null) throw const AppError.unexpected();
    try {
      return PaymentPreview.fromJson(body);
    } on UnexpectedJson {
      throw const AppError.unexpected();
    }
  }

  /// Confirms or corrects the balance from a statement: the anchor, and a correction row when the
  /// figure disagrees with the arithmetic.
  Future<void> confirmBalance(Debt debt, Map<String, Object?> body) =>
      _send('POST', '${_debt(debt.id)}/balance', body, bump: true);

  /// The plan: a strategy, the whole custom order, or the monthly amount — with its version, so
  /// two members editing it collide rather than one silently winning.
  Future<void> updatePlan(Map<String, Object?> body) =>
      _send('PUT', '/api/debt-plan', body, bump: true);
}

final debtWriterProvider = Provider<DebtWriter>(DebtWriter.new);
