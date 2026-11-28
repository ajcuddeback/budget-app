import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/envelopes/domain/envelope.dart';
import 'package:budget_owl/features/envelopes/domain/requests.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// `GET /api/envelopes?period=…` for one month, archived included so the screen can show them
/// apart — cache-first, one page of 200, the web's request. No household id: the server resolves
/// it (ADR-0017).
final class EnvelopesResource extends CachedResource<EnvelopePage> {
  EnvelopesResource(this.period);

  final String period;

  @override
  String get path => '/api/envelopes';

  @override
  Map<String, String> get query => {
    'period': period,
    'includeArchived': 'true',
    'page': '0',
    'size': '200',
  };

  @override
  EnvelopePage decode(JsonMap json) => EnvelopePage.fromJson(json);
}

final envelopesProvider = NotifierProvider.autoDispose
    .family<EnvelopesResource, Snapshot<EnvelopePage>, String>(EnvelopesResource.new);

/// `GET /api/envelopes/{id}/entries`, the first page of 50 — the log, newest first.
final class EnvelopeLogResource extends CachedResource<List<EnvelopeEntry>> {
  EnvelopeLogResource(this.envelopeId);

  final String envelopeId;

  @override
  String get path => '/api/envelopes/${Uri.encodeComponent(envelopeId)}/entries';

  @override
  Map<String, String> get query => const {'page': '0', 'size': '50'};

  @override
  List<EnvelopeEntry> decode(JsonMap json) => [
    for (final entry in json.objects('content')) EnvelopeEntry.fromJson(entry),
  ];
}

final envelopeLogProvider = NotifierProvider.autoDispose
    .family<EnvelopeLogResource, Snapshot<List<EnvelopeEntry>>, String>(EnvelopeLogResource.new);

/// `GET /api/envelopes/periods`: the months that have envelopes, newest first. Asked once when the
/// screen opens, to choose the month to land on.
///
/// **Not cached**: the body is a JSON array, which the cache's reader does not take, and the answer
/// only decides where to land. Without it — offline — the screen opens on the current month, whose
/// cached copy is then shown with its stale label like any other.
final envelopePeriodsProvider = FutureProvider.autoDispose<List<String>>((ref) async {
  final api = ref.watch(apiClientProvider);
  if (api == null) throw const AppError.unexpected();
  final body = await api.getText('/api/envelopes/periods');
  try {
    return decodeObject('{"periods":$body}').strings('periods');
  } on UnexpectedJson {
    throw const AppError.unexpected();
  }
});

/// Every write the envelopes screen makes — `TransactionsWriter`'s pattern: sent only when the
/// last request reached the server, **never queued**, and followed by every screen re-reading.
///
/// An entry's path names its envelope as well as the entry: the right household's entry under the
/// wrong envelope is still the wrong entry, and the server answers `404`.
final class EnvelopesWriter {
  EnvelopesWriter(this._ref);

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

  static String _one(String id) => '/api/envelopes/${Uri.encodeComponent(id)}';

  static String _entry(Envelope envelope, EnvelopeEntry entry) =>
      '${_one(envelope.id)}/entries/${Uri.encodeComponent(entry.id)}';

  Future<void> create(EnvelopeForm form) =>
      _write('POST', '/api/envelopes', createEnvelopeBody(form));

  /// Sends the patch, or nothing when nothing changed. Returns whether anything was sent.
  Future<bool> update(Envelope original, EnvelopeForm form) async {
    final patch = envelopePatch(original, form);
    if (patchIsEmpty(patch)) return false;
    await _write('PATCH', _one(original.id), patch);
    return true;
  }

  Future<void> archive(Envelope envelope) => _write('POST', '${_one(envelope.id)}/archive');

  Future<void> unarchive(Envelope envelope) => _write('POST', '${_one(envelope.id)}/unarchive');

  /// Takes its log with it; the household's transactions stay.
  Future<void> delete(Envelope envelope) => _write('DELETE', _one(envelope.id));

  Future<void> logEntry(Envelope envelope, EntryForm form) =>
      _write('POST', '${_one(envelope.id)}/entries', createEntryBody(form));

  Future<bool> updateEntry(Envelope envelope, EnvelopeEntry original, EntryForm form) async {
    final patch = entryPatch(original, form);
    if (patchIsEmpty(patch)) return false;
    await _write('PATCH', _entry(envelope, original), patch);
    return true;
  }

  Future<void> deleteEntry(Envelope envelope, EnvelopeEntry entry) =>
      _write('DELETE', _entry(envelope, entry));
}

final envelopesWriterProvider = Provider<EnvelopesWriter>(EnvelopesWriter.new);
