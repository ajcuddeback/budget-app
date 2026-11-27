import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/admin/domain/admin.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The four administrator-only routes (`AdminController`), each refused by the server with
/// `403 not-instance-admin` for anybody but the one administrator — a household `OWNER` exactly
/// like a `VIEWER` (ADR-0029). Hiding the screen is a courtesy; that refusal is the control.
///
/// Nothing here is scoped by a household: this is the instance, not its money. The reads go
/// through the encrypted cache like every other, so the console can be read offline with its
/// stale label. What they hold is no financial figure and no secret: the API never returns the
/// OIDC client secret to anybody.
final class InstanceResource extends CachedResource<InstanceInfo> {
  @override
  String get path => '/api/admin/instance';

  @override
  InstanceInfo decode(JsonMap json) => InstanceInfo.fromJson(json);
}

final class SettingsResource extends CachedResource<InstanceSettings> {
  @override
  String get path => '/api/admin/settings';

  @override
  InstanceSettings decode(JsonMap json) => InstanceSettings.fromJson(json);
}

/// `GET /api/admin/users`: `{content, total}`, the whole list (admin-console.md, Known gaps).
final class AdminAccountsResource extends CachedResource<List<AdminAccount>> {
  @override
  String get path => '/api/admin/users';

  @override
  List<AdminAccount> decode(JsonMap json) => [
    for (final account in json.objects('content')) AdminAccount.fromJson(account),
  ];
}

final instanceProvider = NotifierProvider.autoDispose<InstanceResource, Snapshot<InstanceInfo>>(
  InstanceResource.new,
);

final settingsProvider = NotifierProvider.autoDispose<SettingsResource, Snapshot<InstanceSettings>>(
  SettingsResource.new,
);

final adminAccountsProvider =
    NotifierProvider.autoDispose<AdminAccountsResource, Snapshot<List<AdminAccount>>>(
      AdminAccountsResource.new,
    );

/// The console's one write, `PATCH /api/admin/settings`, sent only when the last request reached
/// the server and never queued.
///
/// **The client secret goes out in the body and never comes back** — never in a path, a query or
/// a `GET`. Neither the body nor the response is cached or logged: `ApiClient.write` caches
/// nothing, and its debug line is a method, a path and a status.
final class AdminWriter {
  AdminWriter(this._ref);

  final Ref _ref;

  /// Sends [patch] and returns the settings as the server saved them — the copy the form is filled
  /// from and the next change is diffed against, whatever the re-reads below manage.
  ///
  /// The console then re-reads its settings and the instance block (opening registration is the
  /// one setting that lets the account count move on its own), as the web does. No household data
  /// changed, so nothing else is re-read.
  Future<InstanceSettings> saveSettings(Map<String, Object?> patch) async {
    if (!_ref.read(reachabilityProvider).canWrite) throw const AppError.offline();
    final api = _ref.read(apiClientProvider);
    if (api == null) throw const AppError.unexpected();
    final body = await api.write('PATCH', '/api/admin/settings', body: patch);
    final InstanceSettings saved;
    try {
      if (body == null) throw const UnexpectedJson('no body');
      saved = InstanceSettings.fromJson(body);
    } on UnexpectedJson {
      throw const AppError.unexpected();
    }
    await Future.wait([
      _ref.read(settingsProvider.notifier).refresh(),
      _ref.read(instanceProvider.notifier).refresh(),
    ]);
    return saved;
  }
}

final adminWriterProvider = Provider<AdminWriter>(AdminWriter.new);
