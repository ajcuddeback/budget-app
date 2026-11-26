import 'dart:async';

import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:budget_owl/core/session/current_user.dart';
import 'package:budget_owl/core/session/local_state.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Why the sign-in screen is showing, when it is not the first time.
enum SignInNotice { expired, created, joined }

/// Where the app is in its life with one server.
sealed class SessionState {
  const SessionState();
}

/// Reading what was stored. [failure] is set when a stored session could not be resumed — the
/// server could not be reached and this phone has no copy of who the member is.
final class SessionStarting extends SessionState {
  const SessionStarting({this.failure});
  final AppError? failure;
}

/// No server chosen: the connect screen.
final class SessionNoServer extends SessionState {
  const SessionNoServer();
}

/// A verified server, nobody signed in: the connect screen's choices, sign-in, setup, join.
final class SessionSignedOut extends SessionState {
  const SessionSignedOut(this.server, {this.notice, this.email});
  final ServerAddress server;
  final SignInNotice? notice;

  /// Pre-filled on the sign-in form after setup or joining.
  final String? email;
}

/// Signed in. [epoch] changes with every sign-in, so nothing from a previous session can be
/// mistaken for this one's.
final class SessionSignedIn extends SessionState {
  const SessionSignedIn(this.server, this.user, this.epoch);
  final ServerAddress server;
  final CurrentUser user;
  final int epoch;

  Membership? get membership => user.membership;
}

/// The server has moved to an API this app does not speak. The app says so and stops talking to
/// it (mobile-app.md, Edge cases).
final class SessionUnsupported extends SessionState {
  const SessionUnsupported(this.server, this.failure);
  final ServerAddress server;
  final ConnectFailure failure;
}

/// The server requests go to. Set by [SessionController]; read by [apiClientProvider].
final class CurrentServer extends Notifier<ServerAddress?> {
  @override
  ServerAddress? build() => null;

  // A setter would read as a field; this is a command the session gives.
  // ignore: use_setters_to_change_properties
  void set(ServerAddress? server) => state = server;
}

final currentServerProvider = NotifierProvider<CurrentServer, ServerAddress?>(CurrentServer.new);

/// The client for the current server, or null before one is chosen.
final apiClientProvider = Provider<ApiClient?>((ref) {
  final server = ref.watch(currentServerProvider);
  if (server == null) return null;
  return ApiClient(
    server: server,
    adapter: ref.watch(httpAdapterProvider),
    tokens: ref.watch(tokenStoreProvider),
    onSessionEnded: () => unawaited(ref.read(sessionProvider.notifier).sessionEnded()),
    onOutcome: (outcome) => ref.read(reachabilityProvider.notifier).record(outcome),
  );
});

/// The cache key for who the member is. Not a route: it is not under any household.
const String meCacheKey = 'session:me';

final class SessionController extends Notifier<SessionState> {
  int _epoch = 0;

  /// Set while this controller is itself ending the session, so the `401` its own logout may
  /// provoke does not end it a second time.
  bool _ending = false;

  LocalState get _local => ref.read(localStateProvider);
  TokenStore get _tokens => ref.read(tokenStoreProvider);
  ServerStore get _servers => ref.read(serverStoreProvider);
  bool get _allowCleartext => ref.read(allowLocalCleartextProvider);

  ApiClient get _api {
    final api = ref.read(apiClientProvider);
    if (api == null) throw StateError('no server chosen');
    return api;
  }

  @override
  SessionState build() {
    scheduleMicrotask(resume);
    return const SessionStarting();
  }

  /// Start-up: the stored server and token, then the member — from the cache first, so a phone on
  /// a train opens to what it last knew, and refreshed when the server can be reached.
  Future<void> resume() async {
    final server = await _servers.read(allowLocalCleartext: _allowCleartext);
    if (server == null) {
      state = const SessionNoServer();
      return;
    }
    ref.read(currentServerProvider.notifier).set(server);
    unawaited(_checkVersion(server));
    await _tokens.load();
    if (!_tokens.hasToken) {
      state = SessionSignedOut(server);
      return;
    }

    final cached = await _cachedMe();
    if (cached != null) {
      state = SessionSignedIn(server, cached, ++_epoch);
      unawaited(_refreshMe(server));
      return;
    }
    try {
      final me = await _fetchMe();
      state = SessionSignedIn(server, me, ++_epoch);
    } on AppError catch (failure) {
      if (state is SessionSignedOut) return; // a 401 already ended it
      state = SessionStarting(failure: failure);
    }
  }

  /// Asks the server who it is, on launch. Only a version mismatch changes anything: the app
  /// does not guess at an API it was not written for.
  Future<void> _checkVersion(ServerAddress server) async {
    try {
      await ServerCheck(ref.read(httpAdapterProvider)).check(server);
    } on ConnectFailure catch (failure) {
      if (failure.problem == ConnectProblem.versionMismatch) {
        state = SessionUnsupported(server, failure);
      }
    }
  }

  /// Checks [address] with `GET /api/meta` and, if it is a Budget Owl server this app can talk
  /// to, makes it the server. Nothing else is sent to it first (attack 4).
  Future<void> connect(ServerAddress address) async {
    await ServerCheck(ref.read(httpAdapterProvider)).check(address);
    await _servers.save(address);
    ref.read(currentServerProvider.notifier).set(address);
    state = SessionSignedOut(address);
  }

  /// `POST /api/auth/token`, then `GET /api/auth/me`. The token goes to secure storage and nowhere
  /// else; if anything after issuing it fails, it is revoked locally and nothing is left behind.
  Future<void> signIn({
    required String email,
    required String password,
    required String deviceLabel,
  }) async {
    final current = state;
    if (current is! SessionSignedOut) throw StateError('already signed in');
    final issued = await _api.write(
      'POST',
      '/api/auth/token',
      body: {'email': email, 'password': password, 'deviceLabel': deviceLabel},
      public: true,
    );
    if (issued == null) throw const AppError.unexpected();
    final String token;
    try {
      token = issued.string('token');
    } on UnexpectedJson {
      throw const AppError.unexpected();
    }
    ref.read(cacheHolderProvider).unseal();
    await _tokens.save(token);
    try {
      final me = await _fetchMe();
      state = SessionSignedIn(current.server, me, ++_epoch);
    } on Object {
      await _local.wipe();
      rethrow;
    }
  }

  /// After setup or joining: back to sign-in with the email filled in.
  void signedOutWith(SignInNotice notice, {String? email}) {
    final current = state;
    if (current is SessionSignedOut) {
      state = SessionSignedOut(current.server, notice: notice, email: email);
    }
  }

  /// Signs out: revokes the token on the server if it can be reached (`POST /api/auth/logout`
  /// ends the token the request carries), then wipes local state **whether or not it answered**.
  Future<void> signOut({bool forgetServer = false}) async {
    final current = state;
    final server = switch (current) {
      SessionSignedIn(:final server) => server,
      SessionSignedOut(:final server) => server,
      SessionUnsupported(:final server) => server,
      _ => null,
    };
    _ending = true;
    try {
      if (current is SessionSignedIn) {
        try {
          await _api.write('POST', '/api/auth/logout').timeout(const Duration(seconds: 8));
        } on Object {
          // Unreachable, already revoked, timed out: the wipe below happens regardless.
        }
      }
      await _local.wipe(forgetServer: forgetServer);
      if (forgetServer || server == null) {
        ref.read(currentServerProvider.notifier).set(null);
        state = const SessionNoServer();
      } else {
        state = SessionSignedOut(server);
      }
    } finally {
      _ending = false;
    }
  }

  /// Any `401` on a request that carried the token: wipe once, route to sign-in once, with "Your
  /// session ended". Idempotent — a burst of failing requests ends the session one time.
  Future<void> sessionEnded() async {
    final current = state;
    if (_ending || current is! SessionSignedIn) return;
    _ending = true;
    try {
      await _local.wipe();
      state = SessionSignedOut(current.server, notice: SignInNotice.expired);
    } finally {
      _ending = false;
    }
  }

  /// Re-reads who the member is (a role may have changed on the web).
  Future<void> refreshMe() async {
    final current = state;
    if (current is SessionSignedIn) await _refreshMe(current.server);
  }

  Future<void> _refreshMe(ServerAddress server) async {
    try {
      final me = await _fetchMe();
      final current = state;
      if (current is SessionSignedIn && current.server == server) {
        state = SessionSignedIn(server, me, current.epoch);
      }
    } on AppError {
      // Offline: the cached member stands. A 401 has already ended the session.
    }
  }

  Future<CurrentUser> _fetchMe() async {
    final body = await _api.getText('/api/auth/me');
    final CurrentUser me;
    try {
      me = CurrentUser.fromJson(decodeObject(body));
    } on UnexpectedJson {
      throw const AppError.unexpected();
    }
    final cache = await ref.read(cacheHolderProvider).obtain();
    await cache.write(meCacheKey, '', body, ref.read(clockProvider)().toUtc());
    return me;
  }

  Future<CurrentUser?> _cachedMe() async {
    try {
      final cache = await ref.read(cacheHolderProvider).obtain();
      final entry = await cache.read(meCacheKey);
      return entry == null ? null : CurrentUser.fromJson(decodeObject(entry.body));
    } on Object {
      return null;
    }
  }
}

final sessionProvider = NotifierProvider<SessionController, SessionState>(SessionController.new);

/// The signed-in session, or null. Every screen's data hangs off this, so a wipe — which ends
/// the session — drops every in-memory copy with it.
final signedInProvider = Provider<SessionSignedIn?>((ref) {
  final state = ref.watch(sessionProvider);
  return state is SessionSignedIn ? state : null;
});

/// The headers a public write needs on the cookie transport's CSRF check.
///
/// The server exempts bearer requests and token issue from CSRF, but not first-run setup or
/// invitation acceptance (SecurityConfig.csrfExempt), and a cookie-less client is still asked for
/// the double-submit pair. So: read the `XSRF-TOKEN` cookie from a fresh `GET /api/setup/status`
/// and send it back as both the cookie and the `X-XSRF-TOKEN` header. Nothing is stored.
Future<({Map<String, String> headers, JsonMap status})> csrfForPublicWrite(ApiClient api) async {
  final response = await api.exchange('GET', '/api/setup/status', public: true);
  final cookies = response.headers['set-cookie'] ?? const <String>[];
  String? token;
  for (final cookie in cookies) {
    final match = RegExp('^XSRF-TOKEN=([^;]+)').firstMatch(cookie);
    if (match != null) token = match.group(1);
  }
  final text = response.data ?? '';
  final status = text.isEmpty ? const <String, Object?>{} : decodeObject(text);
  if (token == null) return (headers: const <String, String>{}, status: status);
  return (
    headers: {'X-XSRF-TOKEN': token, 'Cookie': 'XSRF-TOKEN=$token'},
    status: status,
  );
}
