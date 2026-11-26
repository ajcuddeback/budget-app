import 'dart:math';

import 'package:budget_owl/core/storage/secure_store.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The key the cache database is encrypted with: 32 random bytes, held in the Keychain / Keystore
/// beside the token and nowhere else (ADR-0042).
///
/// Generated on first sign-in with [Random.secure] — the platform's CSPRNG — and deleted with the
/// cache on every wipe, so a new sign-in starts a new database under a new key.
final class CacheKeyStore {
  CacheKeyStore(this._store, {Random? random}) : _random = random ?? Random.secure();

  static const String _key = 'budget_owl.cache_key';
  static const int keyBytes = 32;

  final SecureStore _store;
  final Random _random;

  /// The existing key, or a new one stored before it is returned.
  Future<String> obtain() async {
    final existing = await _store.read(_key);
    if (existing != null && RegExp(r'^[0-9a-f]{64}$').hasMatch(existing)) return existing;
    final bytes = List<int>.generate(keyBytes, (_) => _random.nextInt(256));
    final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    await _store.write(_key, hex);
    return hex;
  }

  Future<void> clear() => _store.delete(_key);
}

final cacheKeyStoreProvider = Provider<CacheKeyStore>(
  (ref) => CacheKeyStore(ref.watch(secureStoreProvider)),
);
