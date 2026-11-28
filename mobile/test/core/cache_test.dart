import 'dart:io';
import 'dart:math';

import 'package:budget_owl/core/auth/token_store.dart';
import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/cache/cache_key_store.dart';
import 'package:budget_owl/core/cache/cache_store.dart';
import 'package:budget_owl/core/session/local_state.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqlite3/sqlite3.dart' as sqlite;

import '../support/fixture_server.dart';
import '../support/harness.dart';

/// The encrypted, bounded, disposable cache (ADR-0042 decision 4; mobile-app.md attack 2).
void main() {
  late Directory dir;
  late File file;
  const key = '00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff';
  const otherKey = 'ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100';
  const marker = '{"balance":"3603.4000","payee":"Greenfield Market"}';
  final when = DateTime.utc(2026, 8, 20, 12);

  setUp(() {
    dir = Directory.systemTemp.createTempSync('budget-owl-cache-test');
    file = File('${dir.path}/${CacheStore.fileName}');
  });

  tearDown(() => dir.deleteSync(recursive: true));

  test('stores a body and gives it back with when it was fetched', () async {
    final cache = await CacheStore.open(file: file, keyHex: key);

    await cache.write('h|/api/accounts', 'h', marker, when);
    final entry = await cache.read('h|/api/accounts');
    await cache.close();

    expect(entry?.body, marker);
    expect(entry?.fetchedAt, when);
  });

  test('the file on disk holds no figure and no name in the clear', () async {
    final cache = await CacheStore.open(file: file, keyHex: key);
    await cache.write('h|/api/accounts', 'h', marker, when);
    await cache.close();

    final bytes = String.fromCharCodes(file.readAsBytesSync());

    expect(bytes, isNot(contains('3603.4000')));
    expect(bytes, isNot(contains('Greenfield')));
    expect(bytes, isNot(contains('SQLite format 3')));
  });

  test('the file cannot be read without the key', () async {
    final cache = await CacheStore.open(file: file, keyHex: key);
    await cache.write('h|/api/accounts', 'h', marker, when);
    await cache.close();

    final raw = sqlite.sqlite3.open(file.path);
    addTearDown(raw.close);

    expect(
      () => raw.select('SELECT body FROM cache_entries'),
      throwsA(isA<sqlite.SqliteException>()),
    );
  });

  test(
    'a different key reads nothing: the unreadable file is discarded and started again',
    () async {
      final cache = await CacheStore.open(file: file, keyHex: key);
      await cache.write('h|/api/accounts', 'h', marker, when);
      await cache.close();

      final reopened = await CacheStore.open(file: file, keyHex: otherKey);
      addTearDown(reopened.close);

      expect(await reopened.read('h|/api/accounts'), isNull);
    },
  );

  test('refuses a key that is not 32 bytes of hex', () async {
    expect(() => CacheStore.open(file: file, keyHex: 'short'), throwsArgumentError);
  });

  test('evicts the oldest entries first to stay under its cap', () async {
    final cache = await CacheStore.open(file: file, keyHex: key, capBytes: 250);
    addTearDown(cache.close);
    final body = 'x' * 100;

    await cache.write('a', 'h', body, when);
    await cache.write('b', 'h', body, when.add(const Duration(minutes: 1)));
    await cache.write('c', 'h', body, when.add(const Duration(minutes: 2)));

    expect(await cache.read('a'), isNull);
    expect(await cache.read('b'), isNotNull);
    expect(await cache.read('c'), isNotNull);
    expect(await cache.totalBytes(), lessThanOrEqualTo(250));
  });

  test('does not store a body bigger than the whole cap', () async {
    final cache = await CacheStore.open(file: file, keyHex: key, capBytes: 50);
    addTearDown(cache.close);

    await cache.write('big', 'h', 'x' * 51, when);

    expect(await cache.count(), 0);
  });

  test('the default cap is 50 MB', () {
    expect(CacheStore.defaultCapBytes, 50 * 1024 * 1024);
  });

  group('the key', () {
    test('is 32 random bytes, generated once and then reused', () async {
      final store = MemorySecureStore();
      final keys = CacheKeyStore(store, random: Random(7));

      final first = await keys.obtain();
      final second = await keys.obtain();

      expect(first, matches(RegExp(r'^[0-9a-f]{64}$')));
      expect(second, first);
      expect(store.values['budget_owl.cache_key'], first);
    });

    test('is replaced if storage holds something that is not a key', () async {
      final store = MemorySecureStore()..values['budget_owl.cache_key'] = 'nonsense';

      expect(await CacheKeyStore(store).obtain(), matches(RegExp(r'^[0-9a-f]{64}$')));
    });
  });

  group('the holder', () {
    test('after a wipe nothing can open the cache until the next sign-in', () async {
      final store = MemorySecureStore();
      final holder = CacheHolder(CacheKeyStore(store), () async => dir);
      final cache = await holder.obtain();
      await cache.write('a', 'h', marker, when);

      await holder.destroy();

      expect(file.existsSync(), isFalse);
      expect(holder.isSealed, isTrue);
      await expectLater(holder.obtain(), throwsStateError);
      holder.unseal();
      final fresh = await holder.obtain();
      expect(await fresh.read('a'), isNull);
      await holder.destroy();
    });
  });

  test('a wipe removes the token, the database and its key, and keeps only the server', () async {
    final store = MemorySecureStore()
      ..values['budget_owl.server'] = 'https://owl.example.test'
      ..values['budget_owl.bearer_token'] = 'fixture-token-owner'
      ..values['budget_owl.token_origin'] = testServerOrigin;
    final keys = CacheKeyStore(store);
    final holder = CacheHolder(keys, () async => dir);
    final tokens = TokenStore(store);
    await tokens.load(origin: testServerOrigin);
    expect(tokens.current, 'fixture-token-owner');
    await (await holder.obtain()).write('a', 'h', marker, when);
    final local = LocalState(tokens, holder, keys, ServerStore(store));

    await local.wipe();

    expect(store.values.keys, ['budget_owl.server']);
    expect(tokens.current, isNull);
    expect(file.existsSync(), isFalse);

    await local.wipe(forgetServer: true);
    expect(store.values, isEmpty);
    expect(local.wipes, 2);
  });

  group('TokenStore', () {
    test('keeps the token in secure storage and in memory only', () async {
      final store = MemorySecureStore();
      final tokens = TokenStore(store);

      await tokens.save('fixture-token-owner', origin: testServerOrigin);

      expect(store.values, {
        'budget_owl.bearer_token': 'fixture-token-owner',
        'budget_owl.token_origin': testServerOrigin,
      });
      expect(tokens.current, 'fixture-token-owner');
      expect(tokens.hasToken, isTrue);
    });

    test('loads what was stored, and clears both copies', () async {
      final store = MemorySecureStore()
        ..values['budget_owl.bearer_token'] = 't'
        ..values['budget_owl.token_origin'] = testServerOrigin;
      final tokens = TokenStore(store);

      await tokens.load(origin: testServerOrigin);
      expect(tokens.current, 't');
      await tokens.clear();

      expect(tokens.current, isNull);
      expect(store.values, isEmpty);
    });

    test('drops, rather than loads, a token another origin issued (audit L3)', () async {
      final store = MemorySecureStore()
        ..values['budget_owl.bearer_token'] = 't'
        ..values['budget_owl.token_origin'] = 'https://old.example.test';
      final tokens = TokenStore(store);

      await tokens.load(origin: testServerOrigin);

      expect(tokens.current, isNull);
      expect(store.values, isEmpty);
    });

    test('drops a token stored with no origin at all', () async {
      final store = MemorySecureStore()..values['budget_owl.bearer_token'] = 't';
      final tokens = TokenStore(store);

      await tokens.load(origin: testServerOrigin);

      expect(tokens.current, isNull);
      expect(store.values, isEmpty);
    });
  });
}
