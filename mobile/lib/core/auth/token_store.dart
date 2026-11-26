import 'package:budget_owl/core/storage/secure_store.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The bearer token: full access to the household's finances until it is revoked.
///
/// **This class is the only holder.** The token is written to secure storage and nowhere else —
/// never `SharedPreferences`, a file, the Drift cache, a log line or a URL (mobile-app.md, attack 1)
/// — and the storage key below appears in no other file in `lib/`, which
/// `test/architecture/guards_test.dart` checks. The in-memory copy exists so the HTTP interceptor
/// can attach it without a Keychain read per request; [clear] drops both.
final class TokenStore {
  TokenStore(this._store);

  static const String _key = 'budget_owl.bearer_token';

  final SecureStore _store;
  String? _current;

  /// The token for the next request, or null when signed out. Synchronous on purpose: the
  /// interceptor must not await between deciding to send and sending.
  String? get current => _current;

  bool get hasToken => _current != null;

  /// Reads the stored token into memory, at start-up.
  Future<void> load() async {
    _current = await _store.read(_key);
  }

  Future<void> save(String token) async {
    await _store.write(_key, token);
    _current = token;
  }

  Future<void> clear() async {
    _current = null;
    await _store.delete(_key);
  }
}

final tokenStoreProvider = Provider<TokenStore>(
  (ref) => TokenStore(ref.watch(secureStoreProvider)),
);
