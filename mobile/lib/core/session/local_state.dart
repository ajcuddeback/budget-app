import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/cache/cache_key_store.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/http/api_client.dart' show debugLogFailure;
import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:budget_owl/core/storage/temp_files.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Everything Budget Owl keeps on the phone, and the one way to destroy it.
///
/// *Wipe local state* (mobile-app.md) is: the token, the cache database file and its key, the
/// cached user and household (they live in that database), whatever the app or a plugin left in
/// the temporary directory (an exported CSV, a picked statement's copy: [sweepTemporary]), and
/// in-memory state — which follows,
/// because every screen's data hangs off the session that this wipe ends. Only the server address
/// (and the CA it is pinned to) survives, so signing back in is one screen; `forgetServer` removes
/// them too, for a change of server — from storage and from the transport.
///
/// Called on sign-out (whether or not the server answered), on a change of server, and on any
/// `401` to a request that carried the token.
final class LocalState {
  LocalState(
    this._tokens,
    this._cache,
    this._keys,
    this._servers, {
    void Function()? forgetTrust,
    Future<void> Function()? sweepTemporary,
  }) : _forgetTrust = forgetTrust ?? _nothing,
       _sweepTemporary = sweepTemporary ?? _nothingAsync;

  static void _nothing() {}

  static Future<void> _nothingAsync() async {}

  final TokenStore _tokens;
  final CacheHolder _cache;
  final CacheKeyStore _keys;
  final ServerStore _servers;
  final void Function() _forgetTrust;
  final Future<void> Function() _sweepTemporary;

  /// Deletes what the app or a plugin left in the temporary directory (TempFiles): a step of every
  /// wipe, and run once at start-up, so a CSV left by an app killed mid-share is gone at its next
  /// launch (M1, L1).
  Future<void> sweepTemporary() => _sweepTemporary();

  /// How many wipes have run. Tests assert that a burst of `401`s wipes exactly once.
  int wipes = 0;

  /// **Every step is attempted**, even when one before it fails: a secure-storage error deleting
  /// the token must not leave the cache file and its key behind. The first failure is rethrown
  /// once all of them have run — except the temporary-directory sweep's, which is logged (debug
  /// builds only: the step and the error's type) and never fails the wipe: an export or a picked
  /// copy left behind is swept again at the next start (round 5, U1). Callers change state in a
  /// `finally`, so whatever this rethrows, the person always reaches sign-in or connect. Idempotent, and cheap when there is nothing to delete: a few
  /// secure-storage deletes and a file delete that finds no file.
  Future<void> wipe({bool forgetServer = false}) async {
    wipes++;
    Object? failure;
    StackTrace? trace;
    Future<void> step(Future<void> Function() run) async {
      try {
        await run();
      } on Object catch (error, stack) {
        failure ??= error;
        trace ??= stack;
      }
    }

    // The token first: whatever fails after this, the phone can no longer act as the member.
    await step(_tokens.clear);
    await step(_cache.destroy);
    await step(_keys.clear);
    try {
      await _sweepTemporary();
    } on Object catch (error) {
      debugLogFailure('sweeping the temporary directory', error);
    }
    if (forgetServer) {
      // The transport stops trusting the old server's CA before its record goes.
      await step(() async => _forgetTrust());
      await step(_servers.clear);
    }
    if (failure != null) Error.throwWithStackTrace(failure!, trace!);
  }
}

final localStateProvider = Provider<LocalState>(
  (ref) => LocalState(
    ref.watch(tokenStoreProvider),
    ref.watch(cacheHolderProvider),
    ref.watch(cacheKeyStoreProvider),
    ref.watch(serverStoreProvider),
    forgetTrust: () {
      serverTrustOf(ref.read(httpAdapterProvider))?.pinTo(null);
      ref.read(pinnedCaProvider.notifier).set(null);
    },
    sweepTemporary: () async {
      await sweepTemporaryFiles(
        await ref.read(temporaryDirectoryProvider.future),
        platform: ref.read(targetPlatformProvider),
        inbox: await ref.read(pickerInboxProvider.future),
      );
    },
  ),
);
