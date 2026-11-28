import 'dart:async';

import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/http/user_trust.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:budget_owl/core/session/current_user.dart';
import 'package:budget_owl/core/session/local_state.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:budget_owl/core/storage/install_marker.dart';
import 'package:budget_owl/core/storage/sign_out_marker.dart';
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

/// A sign-out (or a failed sign-in's clean-up) that could not remove the token from secure
/// storage, even after the wipe retried it (round 6, V1). Neither signed in nor honestly signed
/// out: the next launch would find the token and resume. The connect screen says so and offers
/// to try again; nothing of the session is in memory.
final class SessionSignOutIncomplete extends SessionState {
  const SessionSignOutIncomplete({required this.server, required this.forgetServer});
  final ServerAddress? server;

  /// Whether the sign-out was a change of server, so trying again does the same.
  final bool forgetServer;
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
    onOutcome: (outcome) {
      ref.read(reachabilityProvider.notifier).record(outcome);
      // A request got through while the version is unconfirmed: the next refresh retries the check.
      if (outcome == Reachability.reached) {
        ref.read(sessionProvider.notifier).recheckVersionIfNeeded();
      }
    },
    versionConfirmed: () => ref.read(reachabilityProvider.notifier).versionConfirmed,
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
  SignOutMarker get _signOutMarker => ref.read(signOutMarkerProvider);
  ServerStore get _servers => ref.read(serverStoreProvider);
  bool get _allowCleartext => ref.read(allowLocalCleartextProvider);

  ApiClient get _api {
    final api = ref.read(apiClientProvider);
    if (api == null) throw StateError('no server chosen');
    return api;
  }

  /// The version check in flight, start-up's or a retry: at most one at a time, so the reads a
  /// screen sends while it is out do not each start another.
  Future<bool>? _checking;

  ReachabilityNotifier get _gate => ref.read(reachabilityProvider.notifier);

  @override
  SessionState build() {
    scheduleMicrotask(resume);
    return const SessionStarting();
  }

  /// Start-up: the stored server and token, then the member — from the cache first, so a phone on
  /// a train opens to what it last knew, and refreshed when the server can be reached.
  ///
  /// **It never fails silently.** Whatever goes wrong in here — storage that cannot be read, a
  /// cache that cannot be opened, anything — the phone's local state is wiped and the person is
  /// shown the signed-out state with a message (or the connect screen, when not even the server
  /// is known), never a spinner that turns forever. This runs unawaited from [build], so nothing
  /// else would ever see the error.
  Future<void> resume() async {
    try {
      await _resume();
    } on Object {
      await _recoverFromFailedStart();
    }
  }

  Future<void> _resume() async {
    // Writes wait for this launch's version check, whatever an earlier start had seen.
    _gate.unconfirmVersion();
    // An export or a picked statement's copy an earlier run was killed holding (M1, L1). Not a
    // failed start if it cannot: every wipe sweeps again.
    try {
      await _local.sweepTemporary();
    } on Object catch (error) {
      debugLogFailure('sweeping the temporary directory', error);
    }
    // A new installation reads nothing an earlier one left in the Keychain (audit L5).
    var wiped = false;
    final marker = ref.read(installMarkerProvider);
    if (!await marker.present) {
      await _local.wipe(forgetServer: true);
      wiped = true;
      await marker.place();
    }
    // A sign-out that had not finished when the app last closed (round 7, W2): end it again —
    // wipe, no token loaded, the delete tried again — before anything is read.
    final pending = await _signOutMarker.read();
    if (pending != null) {
      final finished = await _endLocally(forgetServer: pending.forgetServer, revoke: false);
      if (!finished) {
        final at = pending.forgetServer
            ? null
            : await _servers.read(allowLocalCleartext: _allowCleartext);
        if (!ref.mounted) return;
        state = SessionSignOutIncomplete(server: at, forgetServer: pending.forgetServer);
        return;
      }
      wiped = true;
    }
    final server = await _servers.read(allowLocalCleartext: _allowCleartext);
    if (server == null) {
      // No server, no session: nothing a session left may outlive it, an unreadable address
      // included. A sign-in can only follow connect(), which wipes again; this makes it so on disk.
      if (!wiped) await _local.wipe(forgetServer: true);
      if (!ref.mounted) return;
      state = const SessionNoServer();
      return;
    }
    // Trust only what this server was pinned to when it was connected to (Android): the system
    // roots, plus its one user CA while that is still on the phone. Before anything is sent to it.
    final pin = await _servers.readCaPin();
    serverTrustOf(ref.read(httpAdapterProvider))?.pinTo(pin?.fingerprint);
    ref.read(pinnedCaProvider.notifier).set(pin);
    ref.read(currentServerProvider.notifier).set(server);
    // Only a token this server issued (audit L3). Anything another origin's token left behind —
    // the cache and its key — goes with it (audit N2). **No token at all means the same** (R1): a
    // sign-out killed after the token was taken but before the wipe left the cache file and its
    // key, and the next sign-in would have reopened them. So with no token, everything goes.
    if (await _tokens.load(origin: server.stored) != TokenLoad.loaded && !wiped) {
      await _local.wipe();
    }

    // The version check runs in this sequence, never beside it (audit H-b): each step below
    // finishes before the next decides the state, so nothing overwrites anything. It waits only
    // where the network is needed anyway; a member with a cached copy sees it first — with writes
    // off until the check passes (reachability.dart).
    // The app can be torn down part-way (ref.mounted): nothing is written after that.
    if (!_tokens.hasToken) {
      if (!ref.mounted) return;
      state = SessionSignedOut(server);
      await _checkVersion(server);
      return;
    }
    final cached = await _cachedMe();
    if (!ref.mounted) return;
    if (cached != null) {
      final epoch = ++_epoch;
      state = SessionSignedIn(server, cached, epoch);
      if (await _checkVersion(server) || !ref.mounted) return;
      await _refreshMe(server, epoch: epoch);
      return;
    }
    if (await _checkVersion(server) || !ref.mounted) return;
    try {
      final me = await _fetchMe();
      if (!ref.mounted) return;
      state = SessionSignedIn(server, me, ++_epoch);
    } on AppError catch (failure) {
      if (!ref.mounted) return;
      // A 401 is the server ending the session: sessionEnded() wipes and routes to sign-in with
      // the notice (audit L2). Anything else leaves the token for a retry.
      if (failure.status == 401 || state is SessionSignedOut) return;
      state = SessionStarting(failure: failure);
    }
  }

  /// Asks the server who it is: on launch, and again on the next request that reaches it while the
  /// answer is still unknown. **Only a pass opens writes** (`Reachability`); anything else keeps
  /// them off and **fails closed**. A version mismatch also stops the app talking to the server —
  /// it does not guess at an API it was not written for — and returns true. Unreachable, busy,
  /// timed out or anything else: reads carry on with what the phone has, writes stay off.
  Future<bool> _checkVersion(ServerAddress server) {
    final running = _versionCheck(server);
    _checking = running;
    // _versionCheck catches everything, so neither callback sees an error.
    unawaited(running.then((_) => _checkDone(running), onError: (Object _) => _checkDone(running)));
    return running;
  }

  void _checkDone(Future<bool> running) {
    if (identical(_checking, running)) _checking = null;
  }

  Future<bool> _versionCheck(ServerAddress server) async {
    bool current() => ref.mounted && ref.read(currentServerProvider) == server;
    try {
      await ServerCheck(ref.read(httpAdapterProvider)).check(server);
      if (current()) _gate.confirmVersion();
    } on ConnectFailure catch (failure) {
      if (!current()) return false;
      _gate.unconfirmVersion();
      if (failure.problem == ConnectProblem.versionMismatch) {
        state = SessionUnsupported(server, failure);
        return true;
      }
    } on Object {
      // Not a recognised failure: still not a pass.
      if (current()) _gate.unconfirmVersion();
    }
    return false;
  }

  /// For a write the person has just asked for — sign-in, setup, accepting an invitation: returns
  /// once this launch's version check has passed, **waiting for the one in flight or running one
  /// now**, so a launch check that failed (busy, timed out) does not make the first tap fail.
  ///
  /// Throws [AppError.noAnswer] when it still cannot be confirmed — the write is not sent — and
  /// [AppError.unexpected] after a version mismatch, which has also moved the session to
  /// [SessionUnsupported].
  Future<void> ensureVersionConfirmed() async {
    if (_gate.versionConfirmed) return;
    final server = ref.read(currentServerProvider);
    if (server == null) throw const AppError.noAnswer();
    // One that was already out may have been sent while the server was still busy: if it fails,
    // this tap asks once more, now.
    final inFlight = _checking;
    if (inFlight != null) {
      if (await inFlight) throw const AppError.unexpected();
      if (!ref.mounted) throw const AppError.noAnswer();
      if (_gate.versionConfirmed) return;
    }
    if (await _checkVersion(server)) throw const AppError.unexpected();
    if (!ref.mounted || !_gate.versionConfirmed) throw const AppError.noAnswer();
  }

  /// Retries the version check when a request has just reached the server and the version is not
  /// yet confirmed this launch — "on the next refresh". At most one check at a time (start-up's
  /// included), and only while there is a server to talk to.
  void recheckVersionIfNeeded() {
    if (!ref.mounted || _checking != null || _gate.versionConfirmed) return;
    final server = ref.read(currentServerProvider);
    final current = state;
    if (server == null || (current is! SessionSignedIn && current is! SessionSignedOut)) return;
    unawaited(_checkVersion(server));
  }

  /// [resume] failed: wipe what the phone holds, best effort, and show the signed-out state with
  /// "Your session ended. Sign in again to continue." — or the connect screen when no server was
  /// read. Never leaves the app on its starting spinner.
  Future<void> _recoverFromFailedStart() async {
    if (!ref.mounted) return;
    final server = ref.read(currentServerProvider);
    _ending = true;
    var finished = false;
    try {
      // A token that was loaded is ended on the server too, not only dropped here: it is taken out
      // of storage first, then revoked best effort (audit N1, L4) — and whether it is gone is
      // checked, as every other path that ends a session does (round 7, W1).
      finished = await _endLocally(forgetServer: server == null);
    } finally {
      _ending = false;
    }
    if (!ref.mounted) return;
    _gate.unconfirmVersion();
    if (!finished) {
      state = SessionSignOutIncomplete(server: server, forgetServer: server == null);
    } else if (server == null) {
      state = const SessionNoServer();
    } else {
      state = SessionSignedOut(server, notice: SignInNotice.expired);
    }
  }

  /// Checks [address] with `GET /api/meta` and, if it is a Budget Owl server this app can talk
  /// to, makes it the server. Nothing else is sent to it first (attack 4).
  ///
  /// Everything the phone held for the previous server is wiped first, so no token, cache or
  /// member can ever be carried to a second origin (audit L3).
  Future<void> connect(ServerAddress address) async {
    _gate.unconfirmVersion();
    // On Android the check also decides which user-installed CA, if any, this server is trusted
    // through from now on: none when the system roots verify it, else the one that does (pinned).
    final transport = ref.read(httpAdapterProvider);
    final trust = serverTrustOf(transport);
    CaPin? caPin;
    if (trust != null) {
      caPin = await trust.checkAndPin(address);
    } else {
      await ServerCheck(transport).check(address);
    }
    try {
      await _local.wipe(forgetServer: true);
    } on Object catch (error) {
      // Something of the previous server may be left: connect nothing to it (fails closed). Every
      // step was attempted, the old address and its pin included, so the connect screen is the
      // honest state.
      debugLogFailure('wiping before connecting', error);
      ref.read(currentServerProvider.notifier).set(null);
      if (ref.mounted) state = const SessionNoServer();
      throw const ConnectFailure(ConnectProblem.phoneNotCleared);
    }
    await _servers.save(address, caPin: caPin);
    trust?.pinTo(caPin?.fingerprint);
    // Said on the Connect screen's choices and under More → Server, so a CA the person does not
    // recognise can be noticed (S1): the pin is trust-on-first-use.
    ref.read(pinnedCaProvider.notifier).set(caPin);
    ref.read(currentServerProvider.notifier).set(address);
    // The check above was this server's, this launch: writes (sign-in, setup, join) may follow.
    _gate.confirmVersion();
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
    // The password goes only to a server whose version was confirmed this launch (attack 4).
    await ensureVersionConfirmed();
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
    // A sign-in is reachable only once the previous sign-out finished, so a marker still here is
    // stale (its removal failed). It goes **before** the new token is saved: a kill in between
    // must not leave a marker that makes the next launch wipe this good session (round 8, X2).
    await _signOutMarker.remove();
    await _tokens.save(token, origin: current.server.stored);
    try {
      final me = await _fetchMe();
      state = SessionSignedIn(current.server, me, ++_epoch);
    } on Object {
      // The token was issued; end it on the server too, not only here (audit L4).
      _ending = true;
      var finished = false;
      try {
        finished = await _endLocally();
      } finally {
        _ending = false;
      }
      if (!finished && ref.mounted) {
        state = SessionSignOutIncomplete(server: current.server, forgetServer: false);
      }
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
      SessionSignOutIncomplete(:final server) => server,
      _ => null,
    };
    _ending = true;
    var finished = false;
    try {
      // Whenever a token is held — signed in, or stopped at the unsupported-version screen
      // (audit L4) — it is taken out of storage first, then the server is asked to end it; the
      // wipe runs whatever that did (round 6, V1).
      finished = await _endLocally(forgetServer: forgetServer);
    } finally {
      _ending = false;
      if (ref.mounted) {
        if (!finished) {
          // The token is still stored: "signed out" would be untrue, and the next launch would
          // resume. Nothing of the session stays in memory; the person can try again.
          state = SessionSignOutIncomplete(server: server, forgetServer: forgetServer);
        } else if (forgetServer || server == null) {
          ref.read(currentServerProvider.notifier).set(null);
          _gate.unconfirmVersion();
          state = const SessionNoServer();
        } else if (current is SessionSignOutIncomplete) {
          // Finished from the unfinished state, which a launch may have reached before setting the
          // current server or applying its CA pin: start over, so start-up does both, in order,
          // and the sign-in that follows has a client to send with (round 8, X1).
          ref.invalidateSelf();
        } else {
          state = SessionSignedOut(server);
        }
      }
    }
  }

  /// Ends the held token: **out of secure storage and memory first** (audit N1), then
  /// `POST /api/auth/logout` with the copy, best effort. If the app is killed while the logout is
  /// in flight, the next launch finds no token and is signed out. Unreachable, already revoked or
  /// slow, the caller wipes the rest regardless.
  ///
  /// A delete that fails does not stop the revocation: the copy taken first is revoked, then the
  /// failure is reported, and the caller's wipe tries the delete again (round 6, V1).
  Future<void> _endToken() async {
    final token = _tokens.held;
    Object? failure;
    StackTrace? trace;
    try {
      await _tokens.take();
    } on Object catch (error, stack) {
      failure = error;
      trace = stack;
    }
    final api = ref.read(apiClientProvider);
    if (api != null && token != null) {
      try {
        await api.revoke(token).timeout(revokeTimeout);
      } on Object {
        // The wipe that follows happens regardless.
      }
    }
    if (failure != null) Error.throwWithStackTrace(failure, trace!);
  }

  /// Ends the session on this phone: the token ([_endToken], when [revoke]), then the wipe — each
  /// in its own try, so neither stops the other, and the wipe's own steps are each attempted
  /// (LocalState.wipe; its token step retries the delete). True only when the token is then
  /// confirmed gone from secure storage (round 6, V1).
  ///
  /// It is marked pending on disk first ([SignOutMarker]) and unmarked only then, so an app closed
  /// before it finished ends it at its next launch rather than resuming (round 7, W2).
  Future<bool> _endLocally({bool forgetServer = false, bool revoke = true}) async {
    await _signOutMarker.place(forgetServer: forgetServer);
    if (revoke) {
      try {
        await _endToken();
      } on Object catch (error) {
        debugLogFailure('ending the token', error);
      }
    }
    try {
      await _local.wipe(forgetServer: forgetServer);
    } on Object catch (error) {
      debugLogFailure('wiping local state', error);
    }
    final gone = !await _tokens.isStored();
    if (gone) await _signOutMarker.remove();
    return gone;
  }

  /// How long a sign-out waits for the server to confirm the revocation.
  static const Duration revokeTimeout = Duration(seconds: 8);

  /// Any `401` on a request that carried the token: wipe once, route to sign-in once, with "Your
  /// session ended". Idempotent — a burst of failing requests ends the session one time. Also while
  /// starting: a phone whose token was revoked while it was off finds out from `/api/auth/me`
  /// (audit L2).
  Future<void> sessionEnded() async {
    final current = state;
    final server = switch (current) {
      SessionSignedIn(:final server) => server,
      SessionStarting() => ref.read(currentServerProvider),
      _ => null,
    };
    if (_ending || server == null) return;
    _ending = true;
    var finished = false;
    try {
      // The server has already ended the token: nothing to revoke, only to wipe.
      finished = await _endLocally(revoke: false);
    } finally {
      _ending = false;
      if (ref.mounted) {
        state = finished
            ? SessionSignedOut(server, notice: SignInNotice.expired)
            : SessionSignOutIncomplete(server: server, forgetServer: false);
      }
    }
  }

  /// Re-reads who the member is (a role may have changed on the web).
  Future<void> refreshMe() async {
    final current = state;
    if (current is SessionSignedIn) await _refreshMe(current.server, epoch: current.epoch);
  }

  /// Re-reads the member once it is already on screen. What the server sent is shown **first**;
  /// caching it is best effort. A cache that cannot be written (sealed by a sign-out meanwhile, a
  /// full disk, anything) is logged in a debug build and costs nothing else: it never reaches
  /// start-up's catch-all, which would sign a working session out.
  ///
  /// The answer belongs to the sign-in that asked ([epoch]): one that arrives after a sign-out and
  /// another sign-in — another member, perhaps — is dropped, neither shown nor cached.
  Future<void> _refreshMe(ServerAddress server, {required int epoch}) async {
    final ({CurrentUser me, String body}) fetched;
    try {
      fetched = await _fetchMeFromServer();
    } on AppError {
      // Offline: the cached member stands. A 401 has already ended the session.
      return;
    } on Object catch (error) {
      debugLogFailure('re-reading the member', error);
      return;
    }
    if (!ref.mounted) return;
    final current = state;
    if (current is! SessionSignedIn || current.server != server || current.epoch != epoch) {
      return;
    }
    state = SessionSignedIn(server, fetched.me, current.epoch);
    try {
      await _cacheMe(fetched.body);
    } on Object catch (error) {
      debugLogFailure('caching the member', error);
    }
  }

  /// `GET /api/auth/me`, decoded, and cached. A cache that cannot be written is a failure here: at
  /// start-up and at sign-in nothing has been shown yet, and the caller ends what it began.
  Future<CurrentUser> _fetchMe() async {
    final fetched = await _fetchMeFromServer();
    await _cacheMe(fetched.body);
    return fetched.me;
  }

  Future<({CurrentUser me, String body})> _fetchMeFromServer() async {
    final body = await _api.getText('/api/auth/me');
    // Torn down while the read was in flight: there is no session left to cache it for.
    if (!ref.mounted) throw const AppError.unexpected();
    try {
      return (me: CurrentUser.fromJson(decodeObject(body)), body: body);
    } on UnexpectedJson {
      throw const AppError.unexpected();
    }
  }

  Future<void> _cacheMe(String body) async {
    final cache = await ref.read(cacheHolderProvider).obtain();
    await cache.write(meCacheKey, '', body, ref.read(clockProvider)().toUtc());
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
  return (headers: {'X-XSRF-TOKEN': token, 'Cookie': 'XSRF-TOKEN=$token'}, status: status);
}
