import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/cache/cache_key_store.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Everything Budget Owl keeps on the phone, and the one way to destroy it.
///
/// *Wipe local state* (mobile-app.md) is: the token, the cache database file and its key, the
/// cached user and household (they live in that database), and in-memory state — which follows,
/// because every screen's data hangs off the session that this wipe ends. Only the server address
/// survives, so signing back in is one screen; `forgetServer` removes it too, for a change of
/// server.
///
/// Called on sign-out (whether or not the server answered), on a change of server, and on any
/// `401` to a request that carried the token.
final class LocalState {
  LocalState(this._tokens, this._cache, this._keys, this._servers);

  final TokenStore _tokens;
  final CacheHolder _cache;
  final CacheKeyStore _keys;
  final ServerStore _servers;

  /// How many wipes have run. Tests assert that a burst of `401`s wipes exactly once.
  int wipes = 0;

  Future<void> wipe({bool forgetServer = false}) async {
    wipes++;
    // The token first: whatever fails after this, the phone can no longer act as the member.
    await _tokens.clear();
    await _cache.destroy();
    await _keys.clear();
    if (forgetServer) await _servers.clear();
  }
}

final localStateProvider = Provider<LocalState>(
  (ref) => LocalState(
    ref.watch(tokenStoreProvider),
    ref.watch(cacheHolderProvider),
    ref.watch(cacheKeyStoreProvider),
    ref.watch(serverStoreProvider),
  ),
);
