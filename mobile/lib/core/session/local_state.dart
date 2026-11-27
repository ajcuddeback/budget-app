import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/cache/cache_key_store.dart';
import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Everything Budget Owl keeps on the phone, and the one way to destroy it.
///
/// *Wipe local state* (mobile-app.md) is: the token, the cache database file and its key, the
/// cached user and household (they live in that database), and in-memory state — which follows,
/// because every screen's data hangs off the session that this wipe ends. Only the server address
/// (and the CA it is pinned to) survives, so signing back in is one screen; `forgetServer` removes
/// them too, for a change of server — from storage and from the transport.
///
/// Called on sign-out (whether or not the server answered), on a change of server, and on any
/// `401` to a request that carried the token.
final class LocalState {
  LocalState(this._tokens, this._cache, this._keys, this._servers, {void Function()? forgetTrust})
    : _forgetTrust = forgetTrust ?? _nothing;

  static void _nothing() {}

  final TokenStore _tokens;
  final CacheHolder _cache;
  final CacheKeyStore _keys;
  final ServerStore _servers;
  final void Function() _forgetTrust;

  /// How many wipes have run. Tests assert that a burst of `401`s wipes exactly once.
  int wipes = 0;

  /// **Every step is attempted**, even when one before it fails: a secure-storage error deleting
  /// the token must not leave the cache file and its key behind. The first failure is rethrown
  /// once all of them have run. Idempotent, and cheap when there is nothing to delete: a few
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
  ),
);
