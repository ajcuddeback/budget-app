import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/import/domain/import.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// `GET /api/imports`, first page, newest first. Counts only: no file name is kept to list.
final class ImportHistoryResource extends CachedResource<List<ImportBatch>> {
  @override
  String get path => '/api/imports';

  @override
  Map<String, String> get query => const {'page': '0', 'size': '10'};

  @override
  List<ImportBatch> decode(JsonMap json) => [
    for (final batch in json.objects('content')) ImportBatch.fromJson(batch),
  ];
}

final importHistoryProvider =
    NotifierProvider.autoDispose<ImportHistoryResource, Snapshot<List<ImportBatch>>>(
      ImportHistoryResource.new,
    );

/// Import over HTTP, every request only when the last one reached the server.
///
/// **The preview writes nothing; the commit sends the same file again** (ADR-0037), as base64
/// inside JSON — never `multipart/form-data`, which the server does not accept (ADR-0038). Neither
/// request nor response is cached: a preview carries the statement's own rows, and is the person's
/// to see once. Nothing here logs a body (the API client's one debug line never carries one).
///
/// A commit and an undo change the ledger, so each bumps the data revision and every screen
/// showing a balance asks the server again.
final class ImportsWriter {
  ImportsWriter(this._ref);

  final Ref _ref;

  ApiClient get _api {
    final api = _ref.read(apiClientProvider);
    if (api == null) throw const AppError.unexpected();
    return api;
  }

  void _online() {
    if (!_ref.read(reachabilityProvider).canWrite) throw const AppError.offline();
  }

  Future<ImportPreview> preview(ImportRequest request) async {
    _online();
    final body = await _api.write('POST', '/api/imports/preview', body: request);
    if (body == null) throw const AppError.unexpected();
    final preview = _decode(() => ImportPreview.fromJson(body));
    return preview;
  }

  /// `201` with the batch; `200` with the same batch when a retry finds it already written.
  Future<ImportBatch> commit(ImportRequest request) async {
    _online();
    final body = await _api.write('POST', '/api/imports', body: request);
    if (body == null) throw const AppError.unexpected();
    _ref.read(dataRevisionProvider.notifier).bump();
    final batch = _decode(() => ImportBatch.fromJson(body));
    return batch;
  }

  /// Undo: removes the batch's rows that are not transfer legs.
  Future<void> undo(ImportBatch batch) async {
    _online();
    await _api.write('DELETE', '/api/imports/${Uri.encodeComponent(batch.id)}');
    _ref.read(dataRevisionProvider.notifier).bump();
  }

  /// The account's saved CSV mapping, or null when it has none (`404`) or cannot be read: a lost
  /// convenience, never a failed preview.
  Future<ImportMapping?> mapping(String accountId) async {
    try {
      final body = await _api.getJson(
        '/api/accounts/${Uri.encodeComponent(accountId)}/import-mapping',
      );
      return ImportMapping.fromJson(body);
    } on Object {
      return null;
    }
  }

  T _decode<T>(T Function() read) {
    try {
      return read();
    } on UnexpectedJson {
      throw const AppError.unexpected();
    }
  }
}

final importsWriterProvider = Provider<ImportsWriter>(ImportsWriter.new);
