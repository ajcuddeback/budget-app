import 'dart:io';

import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:budget_owl/core/platform/sensitive_clipboard.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/storage/install_marker.dart';
import 'package:budget_owl/core/storage/secure_store.dart';
import 'package:budget_owl/core/storage/temp_files.dart';
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

  /// Keys whose read throws, as a Keystore that cannot decrypt a value does.
  final Set<String> unreadable = {};

  /// Keys whose delete throws.
  final Set<String> undeletable = {};

  @override
  Future<void> delete(String key) async {
    if (undeletable.contains(key)) throw StateError('secure storage could not delete $key');
    values.remove(key);
  }

  @override
  Future<String?> read(String key) async {
    if (unreadable.contains(key)) throw StateError('secure storage could not read $key');
    return values[key];
  }

  @override
  Future<void> write(String key, String value) async => values[key] = value;
}

/// The phone's clipboard: what is on it, and whether it was marked sensitive.
final class MemoryClipboard implements ClipboardPlatform {
  String? text;
  bool sensitive = false;
  int clears = 0;

  @override
  Future<void> setSensitive(String text) async {
    this.text = text;
    sensitive = true;
  }

  @override
  Future<String?> read() async => text;

  @override
  Future<void> clear() async {
    clears++;
    text = null;
    sensitive = false;
  }

  /// The person copies something else, in this app or another.
  void copiedElsewhere(String other) {
    text = other;
    sensitive = false;
  }
}

/// Everything a widget test controls about the phone around the app.
final class TestPhone {
  TestPhone({
    required this.server,
    MemorySecureStore? store,
    Directory? cacheDirectory,
    Directory? supportDirectory,
    Directory? temporaryDirectory,
    this.allowLocalCleartext = false,
    bool freshInstall = false,
  }) : store = store ?? MemorySecureStore(),
       cacheDirectory = cacheDirectory ?? Directory.systemTemp.createTempSync('budget-owl-cache'),
       supportDirectory =
           supportDirectory ?? Directory.systemTemp.createTempSync('budget-owl-support'),
       temporaryDirectory =
           temporaryDirectory ?? Directory.systemTemp.createTempSync('budget-owl-temp') {
    // Every phone has run the app before, unless a test says this launch is its first.
    if (!freshInstall) File(installMarker).writeAsStringSync('');
  }

  final HttpClientAdapter server;
  final MemorySecureStore store;
  final Directory cacheDirectory;

  /// Application Support, where the install marker lives.
  final Directory supportDirectory;

  /// The temporary directory: exports, and the plugins' copies (core/storage/temp_files.dart).
  final Directory temporaryDirectory;

  final MemoryClipboard clipboard = MemoryClipboard();

  String get installMarker => '${supportDirectory.path}/${InstallMarker.fileName}';
  final bool allowLocalCleartext;
  DateTime now = demoNow;

  /// Already connected to the test server, as a returning member's phone is.
  void connected() => store.values['budget_owl.server'] = testServerOrigin;

  /// Already signed in as [who] (`owner`, `viewer`, `member`) with the fixture's token.
  void signedIn(String who) {
    connected();
    store.values['budget_owl.bearer_token'] = 'fixture-token-$who';
    store.values['budget_owl.token_origin'] = testServerOrigin;
  }

  List<Override> get overrides => [
    httpAdapterProvider.overrideWithValue(server),
    secureStoreProvider.overrideWithValue(store),
    cacheDirectoryProvider.overrideWith((ref) async => cacheDirectory),
    supportDirectoryProvider.overrideWith((ref) async => supportDirectory),
    temporaryDirectoryProvider.overrideWith((ref) async => temporaryDirectory),
    clipboardPlatformProvider.overrideWithValue(clipboard),
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
  // A feature's own platform seams (a share sheet, a document picker) stood in for by its test.
  List<Override> overrides = const [],
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
      ...overrides,
    ],
    retry: noRetry,
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(
    UncontrolledProviderScope(container: container, child: const BudgetOwlApp()),
  );
  await settle(tester);
  // Start-up wipes whenever there is no token (R1), and the wipe's deletes are real I/O: wait for
  // the session to leave its starting state, not for a fixed time.
  await settleUntil(tester, () {
    final state = container.read(sessionProvider);
    return state is! SessionStarting || state.failure != null;
  }, what: 'start-up finished');
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

/// Lets real I/O and frames run until [done] holds, for up to [timeout] of real time.
///
/// [settle] gives real I/O a fixed ~120 ms. A wipe's secure-storage and file deletes take longer
/// under load, so an assertion about what a wipe left must wait for the wipe, not for a time.
Future<void> settleUntil(
  WidgetTester tester,
  bool Function() done, {
  required String what,
  Duration timeout = const Duration(seconds: 10),
}) async {
  final end = DateTime.now().add(timeout);
  while (!done()) {
    if (DateTime.now().isAfter(end)) fail('never: $what');
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pump(const Duration(milliseconds: 20));
  }
  await tester.pumpAndSettle();
}

/// Until the session is a [T] — e.g. [SessionSignedOut] after connecting, which wipes first.
Future<void> untilSession<T extends SessionState>(WidgetTester tester) {
  final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
  return settleUntil(tester, () => container.read(sessionProvider) is T, what: 'the session is $T');
}

/// Until the session has ended — signed out or no server. The session moves there only **after**
/// its wipe has finished (`SessionController.signOut`), so what the wipe left can be asserted then.
Future<void> untilSignedOut(WidgetTester tester) {
  final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
  return settleUntil(tester, () {
    final state = container.read(sessionProvider);
    return state is SessionSignedOut || state is SessionNoServer;
  }, what: 'the session ended and its wipe finished');
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
