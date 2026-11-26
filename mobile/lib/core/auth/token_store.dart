import 'package:budget_owl/core/storage/secure_store.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The bearer token: full access to the household's finances until it is revoked.
///
/// **This class is the only holder.** The token is written to secure storage and nowhere else —
/// never `SharedPreferences`, a file, the Drift cache, a log line or a URL (mobile-app.md, attack 1)
/// — and the storage key below appears in no other file in `lib/`, which
/// `test/architecture/guards_test.dart` checks. The in-memory copy exists so the HTTP interceptor
/// can attach it without a Keychain read per request; [clear] drops both.
///
/// **The token is bound to the origin that issued it.** It is stored with that origin, and [load]
/// drops a token whose origin is not the server the phone is about to talk to, so no sequence of
/// stored state — a change of server, an interrupted wipe, a restored Keychain — can send one
/// server's token to another (security audit L3).
final class TokenStore {
  TokenStore(this._store);

  static const String _key = 'budget_owl.bearer_token';
  static const String _originKey = 'budget_owl.token_origin';

  final SecureStore _store;
  String? _current;

  /// The token for the next request, or null when signed out. Synchronous on purpose: the
  /// interceptor must not await between deciding to send and sending.
  String? get current => _current;

  bool get hasToken => _current != null;

  /// Reads the stored token into memory, at start-up — only if it was issued by [origin]. A token
  /// for any other origin, or with no origin recorded, is deleted, not loaded.
  Future<void> load({required String origin}) async {
    final token = await _store.read(_key);
    if (token == null) {
      _current = null;
      return;
    }
    if (await _store.read(_originKey) != origin) {
      await clear();
      return;
    }
    _current = token;
  }

  /// Keeps [token], issued by [origin].
  Future<void> save(String token, {required String origin}) async {
    await _store.write(_originKey, origin);
    await _store.write(_key, token);
    _current = token;
  }

  Future<void> clear() async {
    _current = null;
    await _store.delete(_key);
    await _store.delete(_originKey);
  }
}

final tokenStoreProvider = Provider<TokenStore>(
  (ref) => TokenStore(ref.watch(secureStoreProvider)),
);
