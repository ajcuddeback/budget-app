import 'dart:io';

import 'package:budget_owl/core/cache/cache_key_store.dart';
import 'package:budget_owl/core/cache/cache_store.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Owns the open cache database for the signed-in session, and destroys it on a wipe.
///
/// Opened lazily with the key from [CacheKeyStore]; [destroy] closes it and deletes the file and
/// its companions. The key is the caller's to delete (`LocalState.wipe` does both, in order).
final class CacheHolder {
  CacheHolder(this._keys, this._directory, {this.capBytes = CacheStore.defaultCapBytes});

  final CacheKeyStore _keys;
  final Future<Directory> Function() _directory;
  final int capBytes;
  Future<CacheStore>? _opening;

  /// Set by [destroy] and cleared by [unseal] at the next sign-in. While sealed, nothing can open
  /// the cache — so a response that arrives after a sign-out cannot quietly create a new database
  /// and write the household's figures back onto the phone.
  bool _sealed = false;

  bool get isSealed => _sealed;

  void unseal() => _sealed = false;

  Future<CacheStore> obtain() {
    if (_sealed) return Future.error(StateError('the cache is sealed until the next sign-in'));
    return _opening ??= _open();
  }

  Future<CacheStore> _open() async {
    final directory = await _directory();
    final key = await _keys.obtain();
    return await CacheStore.open(
      file: File('${directory.path}/${CacheStore.fileName}'),
      keyHex: key,
      capBytes: capBytes,
    );
  }

  /// Closes the database if it is open and deletes its file, open or not.
  Future<void> destroy() async {
    _sealed = true;
    final opening = _opening;
    _opening = null;
    if (opening != null) {
      try {
        await (await opening).close();
      } on Object catch (_) {
        // A database that failed to open has nothing to close; the file is deleted regardless.
      }
    }
    final directory = await _directory();
    await CacheStore.deleteFiles(File('${directory.path}/${CacheStore.fileName}'));
  }
}

final cacheHolderProvider = Provider<CacheHolder>(
  (ref) =>
      CacheHolder(ref.watch(cacheKeyStoreProvider), () => ref.read(cacheDirectoryProvider.future)),
);
