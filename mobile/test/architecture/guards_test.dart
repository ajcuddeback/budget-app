import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Rules that must hold in every file of `lib/`, checked by reading the source — the mobile
/// counterpart of the backend's ArchUnit tests. `tools/verify.sh` runs this file as its own step,
/// so a broken rule is named in the gate's output, not buried in the test count.
///
/// Comments are stripped before matching, so a rule can be explained in the code it governs.
void main() {
  final sources = {
    for (final file in Directory('lib').listSync(recursive: true).whereType<File>())
      if (file.path.endsWith('.dart') && !file.path.startsWith('lib/l10n/'))
        file.path: _code(file.readAsStringSync()),
  };

  List<String> offending(RegExp pattern, {Set<String> except = const {}}) => [
    for (final entry in sources.entries)
      if (!except.contains(entry.key) && pattern.hasMatch(entry.value)) entry.key,
  ];

  test('no amount is parsed into, or converted to, a floating-point number (ADR-0006)', () {
    expect(
      offending(
        RegExp(r'\bdouble\s*\.\s*(try)?[pP]arse\b|\bnum\s*\.\s*(try)?[pP]arse\b|\.toDouble\s*\('),
      ),
      isEmpty,
      reason: "amounts are Decimal from their wire string; a double rounds somebody's money",
    );
  });

  test("the token's storage key appears only in TokenStore", () {
    expect(offending(RegExp('bearer_token'), except: {'lib/core/auth/token_store.dart'}), isEmpty);
  });

  test('secure storage is constructed in one place, with the platform options', () {
    expect(
      offending(RegExp('FlutterSecureStorage'), except: {'lib/core/storage/secure_store.dart'}),
      isEmpty,
    );
    expect(sources['lib/core/storage/secure_store.dart'], contains('first_unlock_this_device'));
  });

  test('nothing in auth, http, cache or session touches SharedPreferences', () {
    expect(offending(RegExp('SharedPreferences|shared_preferences')), isEmpty);
  });

  test('no certificate check is ever overridden: there is no "trust anyway"', () {
    expect(
      offending(RegExp('badCertificateCallback|validateCertificate|onBadCertificate')),
      isEmpty,
    );
  });

  test('redirects are never followed', () {
    expect(offending(RegExp(r'followRedirects\s*:\s*true')), isEmpty);
    expect(sources['lib/core/http/api_client.dart'], contains('followRedirects: false'));
    expect(sources['lib/core/server/server_check.dart'], contains('followRedirects: false'));
  });

  test('a release build runs the app inside the zone that refuses http:// at the socket', () {
    expect(
      sources['lib/main.dart'],
      matches(RegExp(r'runWithCleartextPolicy\(\s*\(\)\s*=>\s*runApp\(')),
    );
    expect(sources['lib/main.dart'], contains('allowLocalCleartext: kDebugMode'));
    expect(offending(RegExp(r'\brunApp\(')), ['lib/main.dart']);
  });

  test('no transport is closed: the app has one, shared (security audit H1)', () {
    // Every close in lib/ is on this list, by file and receiver; anything else fails here, whatever
    // it is called. The shared transport also throws on close in a debug build (SharedTransport).
    const allowed = {
      'lib/core/cache/cache_holder.dart': {')'}, // `(await opening).close()`: the cache database
      'lib/core/cache/cache_store.dart': {'_db'}, // the cache database
      // A throwaway probe, and the inner client being replaced: never the shared one. (Neither
      // adapter the app sends through can be closed: both refuse, round 4 T2.)
      'lib/core/http/http_adapter.dart': {'probe', 'previous'},
    };
    // `Icons.close` is an icon, not a close.
    const anywhere = {'Icons'};
    final unlisted = [
      for (final entry in sources.entries)
        for (final match in _close.allMatches(entry.value))
          if (!anywhere.contains(match.group(1)) &&
              !(allowed[entry.key]?.contains(match.group(1)) ?? false))
            '${entry.key}: ${match.group(0)}',
    ];
    expect(unlisted, isEmpty);
  });

  test('the close rule sees every way of closing: called, null-aware, cascaded, torn off', () {
    for (final code in const [
      'adapter.close();',
      'adapter.close(force: true);',
      'adapter?.close();',
      'adapter..close();',
      'adapter?..close();',
      'ref.onDispose(adapter.close);',
      'ref.onDispose(adapter?.close);',
      'final shut = adapter.close;',
      'register(adapter.close, later);',
      'final all = [adapter.close];',
      'adapter\n    .close()',
      'adapter . close ( )',
      '(await opening).close();',
      'clients[0].close();',
    ]) {
      expect(_close.hasMatch(code), isTrue, reason: code);
    }
    for (final code in const [
      'adapter.closed;',
      'closeAll();',
      'adapter.closeSink();',
      'close();',
    ]) {
      expect(_close.hasMatch(code), isFalse, reason: code);
    }
  });

  test('nothing prints: a log line is how a token or a balance leaves the phone', () {
    expect(offending(RegExp(r'(^|[^.\w])print\s*\(')), isEmpty);
    expect(
      offending(RegExp(r'debugPrint\s*\('), except: {'lib/core/http/api_client.dart'}),
      isEmpty,
    );
  });

  test('the app ships knowing no server: no address is written into it', () {
    final address = RegExp('''['"]https?://[A-Za-z0-9]''');
    expect(offending(address), isEmpty);
  });

  test('widgets lay out right-to-left: no left/right-only insets or alignment', () {
    expect(
      offending(
        RegExp(
          r'EdgeInsets\.(only|fromLTRB)\(|Alignment\.(centerLeft|centerRight|topLeft|topRight|bottomLeft|bottomRight)',
        ),
      ),
      isEmpty,
    );
  });

  test('widgets take colours from the tokens, never a literal', () {
    expect(
      offending(RegExp(r'Color\(0x|Colors\.[a-z]'), except: {'lib/core/theme/tokens.dart'}),
      isEmpty,
    );
  });

  test('no package that phones home is in the lockfile (attack 12)', () {
    final lock = File('pubspec.lock').readAsStringSync();
    for (final banned in [
      'firebase',
      'sentry',
      'crashlytics',
      'bugsnag',
      'datadog',
      'amplitude',
      'mixpanel',
      'segment',
      'appsflyer',
      'adjust',
      'google_mobile_ads',
      'facebook',
      'posthog',
      'newrelic',
      'instabug',
    ]) {
      expect(RegExp('^  $banned', multiLine: true).hasMatch(lock), isFalse, reason: banned);
    }
  });

  test('the lockfile pins every package to a version and a hash', () {
    final lock = File('pubspec.lock').readAsStringSync();
    final hosted = RegExp('source: hosted').allMatches(lock).length;
    final hashed = RegExp('sha256: "?[0-9a-f]{64}').allMatches(lock).length;
    expect(hosted, greaterThan(0));
    expect(hashed, hosted);
  });
}

/// The source without comments, so a rule can be explained in the file it governs.
String _code(String source) => source
    .replaceAll(RegExp(r'/\*.*?\*/', dotAll: true), '')
    .split('\n')
    // A comment starts a line or follows whitespace; `https://` in a string does neither.
    .map((line) => line.replaceFirst(RegExp(r'(^|\s)//.*$'), ''))
    .join('\n');

/// A close of anything: a receiver (a name, or what a call or index returns), then `.`, `?.`, `..`
/// or `?..`, then `close` called or torn off — followed by `(`, `)`, `,`, `;`, `]` or `}`.
final RegExp _close = RegExp(
  r'([A-Za-z_]\w*|[)\]])\s*(?:\?\.\.|\?\.|\.\.|\.)\s*close\s*(?=[(),;\]}])',
);
