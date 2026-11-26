import 'dart:io';

import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:budget_owl/core/storage/secure_store.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'fixture_server.dart';

/// The demo household's "now": 20 August 2026, midday UTC (DemoFixtureIT.DEMO_TODAY).
final DateTime demoNow = DateTime.utc(2026, 8, 20, 12);

/// Secure storage in memory, inspectable by a test: what the app left behind is the assertion.
final class MemorySecureStore implements SecureStore {
  final Map<String, String> values = {};

  @override
  Future<void> delete(String key) async => values.remove(key);

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async => values[key] = value;
}

/// Everything a widget test controls about the phone around the app.
final class TestPhone {
  TestPhone({
    required this.server,
    MemorySecureStore? store,
    Directory? cacheDirectory,
    this.allowLocalCleartext = false,
  }) : store = store ?? MemorySecureStore(),
       cacheDirectory = cacheDirectory ?? Directory.systemTemp.createTempSync('budget-owl-cache');

  final HttpClientAdapter server;
  final MemorySecureStore store;
  final Directory cacheDirectory;
  final bool allowLocalCleartext;
  DateTime now = demoNow;

  /// Already connected to the test server, as a returning member's phone is.
  void connected() => store.values['budget_owl.server'] = testServerOrigin;

  /// Already signed in as [who] (`owner`, `viewer`, `member`) with the fixture's token.
  void signedIn(String who) {
    connected();
    store.values['budget_owl.bearer_token'] = 'fixture-token-$who';
  }

  List<Override> get overrides => [
    httpAdapterProvider.overrideWithValue(server),
    secureStoreProvider.overrideWithValue(store),
    cacheDirectoryProvider.overrideWith((ref) async => cacheDirectory),
    clockProvider.overrideWithValue(() => now),
    allowLocalCleartextProvider.overrideWithValue(allowLocalCleartext),
    targetPlatformProvider.overrideWithValue(TargetPlatform.android),
  ];

  /// The cache file, which a wipe must remove.
  File get cacheFile => File('${cacheDirectory.path}/budget_owl_cache.db');
}

/// Pumps the whole app — router, theme, l10n, privacy cover — at a phone's size, against [phone].
Future<ProviderContainer> pumpApp(
  WidgetTester tester,
  TestPhone phone, {
  ThemeMode themeMode = ThemeMode.light,
  TextDirection? direction,
  double textScale = 1,
  Size size = const Size(390, 844),
}) async {
  tester.view
    ..physicalSize = size
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  // flutter_test draws elevation as a solid black outline by default, which made every floating
  // button in the goldens look ringed in black. Real shadows, as the phone draws them.
  debugDisableShadows = false;
  addTearDown(() => debugDisableShadows = true);
  tester.platformDispatcher.textScaleFactorTestValue = textScale;
  addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
  final container = ProviderContainer(
    overrides: [
      ...phone.overrides,
      themeModeProvider.overrideWithValue(themeMode),
      textDirectionOverrideProvider.overrideWithValue(direction),
    ],
    retry: noRetry,
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(
    UncontrolledProviderScope(container: container, child: const BudgetOwlApp()),
  );
  await settle(tester);
  return container;
}

/// Lets real I/O (the SQLCipher cache, file deletes) finish, then frames settle.
Future<void> settle(WidgetTester tester) async {
  for (var i = 0; i < 6; i++) {
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pump(const Duration(milliseconds: 50));
  }
  await tester.pumpAndSettle();
}

/// Scrolls the front-most list until [finder] is built and on screen, then taps it.
Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await revealIn(tester, finder);
  await tester.tap(finder);
  await settle(tester);
}

/// Scrolls the front-most list until [finder] is built and on screen.
Future<void> revealIn(WidgetTester tester, Finder finder) async {
  if (finder.evaluate().isEmpty) {
    await tester.scrollUntilVisible(
      finder,
      200,
      scrollable: find.byType(Scrollable).last,
      maxScrolls: 30,
    );
  }
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
}
