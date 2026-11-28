import 'dart:convert';
import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';

/// One cached response: the body exactly as the API sent it, and when.
final class CacheEntry {
  const CacheEntry(this.body, this.fetchedAt);
  final String body;
  final DateTime fetchedAt;
}

/// The response cache: a Drift database **encrypted with SQLCipher** (ADR-0042).
///
/// - **Not a second data model.** One table of response bodies keyed by route and household. It
///   never computes a figure the server did not send, so it cannot disagree with the server about
///   anything but age.
/// - **Encrypted at rest** with a 32-byte random key from the Keychain / Keystore, applied as a raw
///   key before any other statement. Opening refuses to continue if the linked SQLite is not
///   SQLCipher — an app that silently fell back to plaintext would be worse than no cache.
/// - **Bounded**: [capBytes] of bodies at most, oldest evicted first, and a body larger than the
///   cap is not stored at all.
/// - **Disposable**: lives in the app's cache directory (never backed up on iOS; Android backup
///   is off for the whole app), and is deleted, file and key, on every wipe.
final class CacheStore {
  CacheStore._(this._db, this.file, this.capBytes);

  /// 50 MB (mobile-app.md, The cache).
  static const int defaultCapBytes = 50 * 1024 * 1024;

  static const String fileName = 'budget_owl_cache.db';

  final _CacheDatabase _db;
  final File file;
  final int capBytes;

  /// Opens (or creates) the database at [file] with [keyHex]. A file this key cannot open — left
  /// behind by a wipe that was interrupted, or by a key the platform lost — is deleted and started
  /// again: the cache is disposable and the server has everything in it.
  static Future<CacheStore> open({
    required File file,
    required String keyHex,
    int capBytes = defaultCapBytes,
  }) async {
    if (!RegExp(r'^[0-9a-f]{64}$').hasMatch(keyHex)) {
      throw ArgumentError('the cache key must be 32 bytes of hex');
    }
    try {
      return await _openWith(file, keyHex, capBytes);
    } on Object catch (_) {
      await deleteFiles(file);
      return await _openWith(file, keyHex, capBytes);
    }
  }

  static Future<CacheStore> _openWith(File file, String keyHex, int capBytes) async {
    final executor = NativeDatabase(
      file,
      setup: (raw) {
        raw.execute("PRAGMA key = \"x'$keyHex'\"");
        final cipher = raw.select('PRAGMA cipher_version');
        if (cipher.isEmpty) {
          throw StateError('SQLCipher is not linked; refusing to cache in plaintext');
        }
        // Fails here, not on first use, when the key does not open the file.
        raw.select('SELECT count(*) FROM sqlite_master');
      },
    );
    final db = _CacheDatabase(executor);
    await db.customSelect('SELECT 1').get();
    return CacheStore._(db, file, capBytes);
  }

  /// The file and SQLite's companions, for a wipe. Safe to call on files that do not exist.
  static Future<void> deleteFiles(File file) async {
    for (final suffix in ['', '-journal', '-wal', '-shm']) {
      final companion = File('${file.path}$suffix');
      if (companion.existsSync()) await companion.delete();
    }
  }

  Future<CacheEntry?> read(String key) async {
    final rows = await _db
        .customSelect(
          'SELECT body, fetched_at FROM cache_entries WHERE key = ?',
          variables: [Variable.withString(key)],
        )
        .get();
    if (rows.isEmpty) return null;
    final row = rows.single;
    return CacheEntry(
      row.read<String>('body'),
      DateTime.fromMillisecondsSinceEpoch(row.read<int>('fetched_at'), isUtc: true),
    );
  }

  Future<void> write(String key, String householdId, String body, DateTime fetchedAt) async {
    final bytes = utf8.encode(body).length;
    if (bytes > capBytes) return;
    await _db.transaction(() async {
      await _db.customStatement(
        'INSERT OR REPLACE INTO cache_entries (key, household_id, body, fetched_at, bytes) '
        'VALUES (?, ?, ?, ?, ?)',
        [key, householdId, body, fetchedAt.millisecondsSinceEpoch, bytes],
      );
      await _evict();
    });
  }

  /// Oldest first until the total fits. Bounded by the number of rows, which the cap bounds.
  Future<void> _evict() async {
    while (await totalBytes() > capBytes) {
      await _db.customStatement(
        'DELETE FROM cache_entries WHERE key = '
        '(SELECT key FROM cache_entries ORDER BY fetched_at ASC, key ASC LIMIT 1)',
      );
    }
  }

  Future<int> totalBytes() async {
    final row = await _db
        .customSelect('SELECT COALESCE(SUM(bytes), 0) AS total FROM cache_entries')
        .getSingle();
    return row.read<int>('total');
  }

  Future<int> count() async {
    final row = await _db.customSelect('SELECT count(*) AS n FROM cache_entries').getSingle();
    return row.read<int>('n');
  }

  Future<void> close() => _db.close();
}

/// Drift with no generated code: one table, written in SQL, so the gate never needs build_runner.
final class _CacheDatabase extends GeneratedDatabase {
  _CacheDatabase(super.executor);

  @override
  Iterable<TableInfo<Table, Object?>> get allTables => const [];

  @override
  int get schemaVersion => 1;

  @override
  MigrationStrategy get migration => MigrationStrategy(
    onCreate: (m) => customStatement(
      'CREATE TABLE cache_entries ( '
      'key TEXT NOT NULL PRIMARY KEY, '
      'household_id TEXT NOT NULL, '
      'body TEXT NOT NULL, '
      'fetched_at INTEGER NOT NULL, '
      'bytes INTEGER NOT NULL)',
    ),
  );
}
