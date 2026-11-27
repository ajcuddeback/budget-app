import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/transactions/domain/requests.dart';
import 'package:budget_owl/features/transactions/domain/transaction.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// `GET /api/transactions`, one query per filter combination — cache-first through
/// [CachedResource]. No household id anywhere: the server resolves it from the caller's verified
/// membership (ADR-0017).
final class LedgerResource extends CachedResource<Ledger> {
  LedgerResource(this.ledgerQuery);

  final LedgerQuery ledgerQuery;

  @override
  String get path => '/api/transactions';

  @override
  Map<String, String> get query => ledgerQuery.toQuery();

  @override
  bool get cacheable => ledgerQuery.cacheable;

  @override
  Ledger decode(JsonMap json) => Ledger.fromJson(json);
}

final ledgerProvider = NotifierProvider.autoDispose
    .family<LedgerResource, Snapshot<Ledger>, LedgerQuery>(LedgerResource.new);

/// Every write the ledger makes, each sent only when the last request reached the server.
///
/// **Nothing is queued** (mobile-app.md decision 3). Offline, a write throws
/// [AppError.offline] without being sent — the screens disable the control first, and this is the
/// second refusal behind them. After a success the household's data revision is bumped, so every
/// screen showing a balance asks the server again rather than this client patching a figure.
/// Each body is the one the web sends, `version` included.
final class TransactionsWriter {
  TransactionsWriter(this._ref);

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

  Future<void> create(TransactionForm form) =>
      _write('POST', '/api/transactions', createTransactionBody(form));

  /// Sends the patch, or nothing when nothing changed. Returns whether anything was sent.
  Future<bool> update(Transaction original, TransactionForm form) async {
    final patch = transactionPatch(original, form);
    if (patchIsEmpty(patch)) return false;
    await _write('PATCH', '/api/transactions/${Uri.encodeComponent(original.id)}', patch);
    return true;
  }

  Future<void> delete(Transaction row) =>
      _write('DELETE', '/api/transactions/${Uri.encodeComponent(row.id)}');

  /// The transfer a leg belongs to, fetched rather than assembled from the row: the sheet needs
  /// both accounts, both figures and the pair's current version, and a leg carries none of them.
  ///
  /// `getJson` maps nothing, so a body this client cannot read (not JSON, not an object, or not a
  /// `TransferResponse`) is mapped here: the screen catches [AppError] only, and an
  /// [UnexpectedJson] would escape it as an uncaught error.
  Future<Transfer> transfer(String transferGroupId) async {
    try {
      return Transfer.fromJson(
        await _api.getJson('/api/transfers/${Uri.encodeComponent(transferGroupId)}'),
      );
    } on UnexpectedJson {
      throw const AppError.unexpected();
    }
  }

  Future<void> createTransfer(TransferForm form, {required bool crossCurrency}) =>
      _write('POST', '/api/transfers', createTransferBody(form, crossCurrency: crossCurrency));

  Future<bool> updateTransfer(Transfer original, TransferForm form) async {
    final patch = transferPatch(original, form);
    if (patchIsEmpty(patch)) return false;
    await _write('PATCH', '/api/transfers/${Uri.encodeComponent(original.id)}', patch);
    return true;
  }

  /// Both legs, by the group id — never a leg's own id, which the server refuses.
  Future<void> deleteTransfer(String transferGroupId) =>
      _write('DELETE', '/api/transfers/${Uri.encodeComponent(transferGroupId)}');

  /// "These two rows are one transfer": both versions as read, so an edit elsewhere is a `409`.
  Future<void> pair(Transaction row, Transaction other) => _write(
    'POST',
    '/api/transactions/${Uri.encodeComponent(row.id)}/pair',
    {'withTransactionId': other.id, 'version': row.version, 'withVersion': other.version},
  );

  /// Two ordinary rows again, with the pair's version.
  Future<void> split(Transfer transfer) => _write(
    'POST',
    '/api/transfers/${Uri.encodeComponent(transfer.id)}/split',
    {'version': transfer.version},
  );

  /// Payee names starting with [typed]. A convenience: a failure is an empty list, not an alert.
  Future<List<String>> suggestPayees(String typed) async {
    try {
      final text = await _api.getText(
        '/api/transactions/payee-suggestions',
        query: {'typed': typed},
      );
      final decoded = decodeList(text);
      return decoded;
    } on Object {
      return const [];
    }
  }
}

/// A JSON array of strings, the payee suggestions' shape.
List<String> decodeList(String text) {
  final wrapped = decodeObject('{"v":$text}');
  return wrapped.strings('v');
}

final transactionsWriterProvider = Provider<TransactionsWriter>(TransactionsWriter.new);
