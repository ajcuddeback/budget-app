import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';

/// The mobile equivalent of tools/ui-check.sh (CLAUDE.md rule 8): every built screen rendered at
/// 390×844 with the real fonts, from the generated fixtures, in light and dark, right-to-left and
/// at text scale 2.0 — and the states that matter where a screen has them: `VIEWER`, offline with
/// a stale copy, and an error. `tools/mobile-check.sh` renders them; **read them**.
///
/// A golden is a picture somebody looked at. Updating one (`--update-goldens`) without opening the
/// PNG is the same as not having it.
void main() {
  for (final scenario in scenarios) {
    for (final variant in scenario.variants) {
      testWidgets('${scenario.name} — ${variant.name}', (tester) async {
        final phone = await scenario.arrange(tester, variant);
        await expectLater(
          find.byType(MaterialApp),
          matchesGoldenFile('png/${scenario.name}__${variant.name}.png'),
        );
        phone.cacheDirectory.deleteSync(recursive: true);
      });
    }
  }
}

final class Variant {
  const Variant(this.name, {this.dark = false, this.rtl = false, this.textScale = 1});

  final String name;
  final bool dark;
  final bool rtl;
  final double textScale;
}

const Variant light = Variant('light');
const Variant dark = Variant('dark', dark: true);
const Variant rtl = Variant('rtl', rtl: true);
const Variant large = Variant('text-2x', textScale: 2);

/// Every screen in both themes, mirrored, and at double text size.
const List<Variant> everyWay = [light, dark, rtl, large];

/// A state of a screen, shown in light and dark.
const List<Variant> bothThemes = [light, dark];

final class Scenario {
  const Scenario(this.name, this.variants, this.arrange);

  final String name;
  final List<Variant> variants;
  final Future<TestPhone> Function(WidgetTester tester, Variant variant) arrange;
}

Future<TestPhone> _pump(WidgetTester tester, Variant v, TestPhone phone) async {
  await pumpApp(
    tester,
    phone,
    themeMode: v.dark ? ThemeMode.dark : ThemeMode.light,
    direction: v.rtl ? TextDirection.rtl : null,
    textScale: v.textScale,
  );
  return phone;
}

Future<TestPhone> _signedIn(
  WidgetTester tester,
  Variant v, {
  String state = 'demo',
  String who = 'owner',
  String file = 'demo',
}) {
  final phone = TestPhone(server: FixtureServer.load(file, state: state))..signedIn(who);
  return _pump(tester, v, phone);
}

Future<void> _tapText(WidgetTester tester, String text) => tapVisible(tester, find.text(text).last);

final List<Scenario> scenarios = [
  Scenario('connect', everyWay, (tester, v) {
    return _pump(tester, v, TestPhone(server: FixtureServer.load('demo', state: 'connect')));
  }),

  Scenario('connect-cleartext-refused', bothThemes, (tester, v) async {
    final phone = await _pump(
      tester,
      v,
      TestPhone(server: FixtureServer.load('demo', state: 'connect')),
    );
    await tester.enterText(find.byType(TextField), 'http://192.168.1.20:8080');
    await _tapText(tester, 'Connect');
    return phone;
  }),

  Scenario('connect-certificate-untrusted', bothThemes, (tester, v) async {
    final phone = await _pump(tester, v, TestPhone(server: const _UntrustedCertificate()));
    await tester.enterText(find.byType(TextField), 'owl.example.test');
    await _tapText(tester, 'Connect');
    return phone;
  }),

  Scenario('connect-choices', everyWay, (tester, v) {
    final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected();
    return _pump(tester, v, phone);
  }),

  Scenario('connect-choices-fresh', bothThemes, (tester, v) {
    final phone = TestPhone(server: FixtureServer.load('first-run', state: 'fresh'))..connected();
    return _pump(tester, v, phone);
  }),

  Scenario('sign-in', everyWay, (tester, v) async {
    final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected();
    await _pump(tester, v, phone);
    await _tapText(tester, 'Sign in with my account');
    return phone;
  }),

  Scenario('sign-in-refused', bothThemes, (tester, v) async {
    final phone = TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected();
    await _pump(tester, v, phone);
    await _tapText(tester, 'Sign in with my account');
    await tester.enterText(find.widgetWithText(TextField, 'Email address'), 'ada@example.com');
    await tester.enterText(find.widgetWithText(TextField, 'Password'), 'wrong-guess-1');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Sign in'));
    return phone;
  }),

  Scenario('setup', everyWay, (tester, v) async {
    final phone = TestPhone(server: FixtureServer.load('first-run', state: 'fresh'))..connected();
    await _pump(tester, v, phone);
    await _tapText(tester, 'Set up a new server');
    return phone;
  }),

  Scenario('join-paste', bothThemes, (tester, v) async {
    final phone = TestPhone(server: FixtureServer.load('first-run', state: 'invited'))..connected();
    await _pump(tester, v, phone);
    await _tapText(tester, 'I have an invitation link');
    return phone;
  }),

  Scenario('join-other-host', bothThemes, (tester, v) async {
    final phone = TestPhone(server: FixtureServer.load('first-run', state: 'invited'))..connected();
    await _pump(tester, v, phone);
    await _tapText(tester, 'I have an invitation link');
    await tester.enterText(
      find.byType(TextField),
      'https://other.example.test/join/fixture-invitation',
    );
    await _tapText(tester, 'Connect');
    return phone;
  }),

  Scenario('join-disclosure', everyWay, (tester, v) async {
    final phone = TestPhone(server: FixtureServer.load('first-run', state: 'invited'))..connected();
    await _pump(tester, v, phone);
    await _tapText(tester, 'I have an invitation link');
    await tester.enterText(find.byType(TextField), '$testServerOrigin/join/fixture-invitation');
    await _tapText(tester, 'Connect');
    return phone;
  }),

  Scenario('no-household', everyWay, (tester, v) {
    return _signedIn(tester, v, state: 'no-household', who: 'member');
  }),

  const Scenario('transactions', everyWay, _signedIn),

  Scenario('transactions-empty', bothThemes, (tester, v) {
    return _signedIn(tester, v, file: 'first-run', state: 'empty');
  }),

  Scenario('transactions-viewer', everyWay, (tester, v) {
    return _signedIn(tester, v, state: 'viewer', who: 'viewer');
  }),

  Scenario('transactions-account', bothThemes, (tester, v) async {
    final phone = await _signedIn(tester, v);
    await _tapText(tester, 'Everyday Checking •4417');
    return phone;
  }),

  Scenario('transactions-mixed-currencies', bothThemes, (tester, v) {
    return _signedIn(tester, v, state: 'crossed');
  }),

  Scenario('transactions-offline-stale', everyWay, (tester, v) async {
    final phone = await _signedIn(tester, v);
    (phone.server as FixtureServer).offline = true;
    phone.now = demoNow.add(const Duration(hours: 2));
    await tester.fling(find.byType(ListView).first, const Offset(0, 400), 1000);
    await settle(tester);
    return phone;
  }),

  Scenario('transactions-error', bothThemes, (tester, v) {
    final server = FixtureServer.load('demo', state: 'demo')..offlineAfter = 'GET /api/auth/me';
    final phone = TestPhone(server: server)..signedIn('owner');
    return _pump(tester, v, phone);
  }),

  Scenario('transactions-row-actions', bothThemes, (tester, v) async {
    final phone = await _signedIn(tester, v);
    await tapVisible(tester, find.text('Corner Kitchen').first);
    return phone;
  }),

  Scenario('transactions-stale-edit', bothThemes, (tester, v) async {
    final phone = await _signedIn(tester, v);
    await tapVisible(tester, find.text('Greenfield Market').first);
    await _tapText(tester, 'Edit');
    await revealIn(tester, find.widgetWithText(TextFormField, 'Note'));
    await tester.enterText(find.widgetWithText(TextFormField, 'Note'), 'weekly shop');
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));
    // Back to the top of the sheet, where the refusal is.
    await tester.drag(find.byType(Scrollable).last, const Offset(0, 3000));
    await settle(tester);
    return phone;
  }),

  Scenario('transaction-add', everyWay, (tester, v) async {
    final phone = await _signedIn(tester, v);
    await tapVisible(tester, find.text('Record a transaction'));
    return phone;
  }),

  Scenario('transfer-add', bothThemes, (tester, v) async {
    final phone = await _signedIn(tester, v);
    await _tapText(tester, 'Move money');
    return phone;
  }),

  Scenario('transfer-edit-cross-currency', bothThemes, (tester, v) async {
    final phone = await _signedIn(tester, v, state: 'crossed');
    await tapVisible(tester, find.text('Transfer in').first);
    await _tapText(tester, 'Edit');
    return phone;
  }),

  Scenario('pair', bothThemes, (tester, v) async {
    final phone = await _signedIn(tester, v);
    await tapVisible(tester, find.text('Corner Kitchen').first);
    await _tapText(tester, 'This is a transfer…');
    return phone;
  }),

  Scenario('devices', everyWay, (tester, v) async {
    final phone = await _signedIn(tester, v);
    await _tapText(tester, 'More');
    await _tapText(tester, 'Devices');
    return phone;
  }),

  Scenario('more', everyWay, (tester, v) async {
    final phone = await _signedIn(tester, v);
    await _tapText(tester, 'More');
    return phone;
  }),

  Scenario('placeholder', bothThemes, (tester, v) async {
    final phone = await _signedIn(tester, v);
    await _tapText(tester, 'Bills');
    return phone;
  }),
];

/// A server whose certificate this phone does not trust: the handshake fails before HTTP.
final class _UntrustedCertificate implements HttpClientAdapter {
  const _UntrustedCertificate();

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) => throw DioException(
    requestOptions: options,
    error: const HandshakeException(
      'Handshake error in client',
      OSError('CERTIFICATE_VERIFY_FAILED: unable to get local issuer certificate'),
    ),
  );

  @override
  void close({bool force = false}) {}
}
