import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/check_in/domain/check_in.dart';
import 'package:budget_owl/features/check_in/domain/requests.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// `GET /api/check-ins/current` (null) or `GET /api/check-ins/{week}`, cache-first.
///
/// **The week comes from the server's clock**, never the phone's: "current" is the week containing
/// the server's today (weekly-check-in.md, What slice 14 has built). The draft lives on the server
/// and nowhere on the phone but this ordinary read's cached copy, which a sign-out wipes.
final class CheckInResource extends CachedResource<Week> {
  CheckInResource(this.week);

  /// A week other than the current one: another week's open draft, opened from its refusal.
  final String? week;

  @override
  String get path => week == null ? '/api/check-ins/current' : weekPath(week!);

  @override
  Week decode(JsonMap json) => Week.fromJson(json);
}

final checkInProvider = NotifierProvider.autoDispose
    .family<CheckInResource, Snapshot<Week>, String?>(CheckInResource.new);

/// Every write the check-in makes, each sent only when the last request reached the server.
///
/// **Nothing is queued**, as for every write (mobile-app.md decision 3); offline is refused here
/// behind the disabled controls. Each answer is one request, as the web sends it, and the week is
/// then read again from the server, so the figure at the bottom is always the server's.
///
/// Answers, a start and a discard change only the check-in, so only its read is refreshed (the
/// devices precedent). **Completion writes categories, bill statuses, a goal contribution or a
/// debt payment**, so it bumps the data revision and every screen asks again.
final class CheckInWriter {
  CheckInWriter(this._ref);

  final Ref _ref;

  ApiClient get _api {
    final api = _ref.read(apiClientProvider);
    if (api == null) throw const AppError.unexpected();
    return api;
  }

  Future<void> _write(String method, String path, [Object? body]) async {
    if (!_ref.read(reachabilityProvider).canWrite) throw const AppError.offline();
    await _api.write(method, path, body: body);
  }

  /// Idempotent: two members pressing Start get one draft. No body.
  Future<void> start(String week) => _write('PUT', weekPath(week));

  /// Writes nothing outside the check-in.
  Future<void> discard(String week) => _write('DELETE', weekPath(week));

  /// Records or replaces one answer: [body] already carries the answer's version when replacing.
  Future<void> answer(String week, Item item, Body body) =>
      _write('PUT', answerPath(week, item.itemKey), withVersion(item, body));

  Future<void> clear(String week, Item item) => _write('DELETE', answerPath(week, item.itemKey));

  /// Everything, atomically, on the server. A retry after a lost response is
  /// `409 check-in-completed`, and the screen re-reads.
  Future<void> complete(Week week) async {
    await _write('POST', '${weekPath(week.week)}/complete', completion(week));
    _ref.read(dataRevisionProvider.notifier).bump();
  }
}

final checkInWriterProvider = Provider<CheckInWriter>(CheckInWriter.new);
