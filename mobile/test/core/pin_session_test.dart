import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/http/user_trust.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fake_trust.dart';
import '../support/fixture_server.dart';
import '../support/harness.dart';

/// PIN, the session's half: the fingerprint `checkAndPin` chose is stored **with the server
/// address**, given to the transport before anything is sent at every start, kept by a sign-out
/// (so signing back in never re-pins), and cleared — in storage and in the transport — whenever the
/// address is forgotten. The sockets half is user_trust_test.dart.
void main() {
  const pinKey = 'budget_owl.server_ca_pin';
  const nameKey = 'budget_owl.server_ca_name';
  final pinned = 'c1' * 32;
  final pin = CaPin(pinned, subject: 'Rivera Household CA');

  ProviderContainer containerFor(TestPhone phone) {
    final container = ProviderContainer(overrides: phone.overrides, retry: noRetry);
    addTearDown(container.dispose);
    addTearDown(() {
      if (phone.cacheDirectory.existsSync()) phone.cacheDirectory.deleteSync(recursive: true);
    });
    return container;
  }

  Future<T> until<T extends SessionState>(ProviderContainer c) async {
    for (var i = 0; i < 400; i++) {
      final state = c.read(sessionProvider);
      if (state is T) return state;
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    fail('the session never became $T; it is ${c.read(sessionProvider)}');
  }

  ServerAddress testServer() => (ServerAddress.parse(
    testServerOrigin,
    allowLocalCleartext: false,
  ) as AddressAccepted).address;

  test('connecting stores the pin the check chose, with the address, and applies it', () async {
    final trust = FakeTrust(FixtureServer.load('demo', state: 'connect'), pinOnConnect: pin);
    final phone = TestPhone(server: trust);
    final c = containerFor(phone);
    await until<SessionNoServer>(c);
    trust.events.clear();

    await c.read(sessionProvider.notifier).connect(testServer());

    expect(phone.store.values, {
      'budget_owl.server': testServerOrigin,
      pinKey: pinned,
      nameKey: 'Rivera Household CA',
    });
    expect(trust.events, ['checkAndPin', 'pinTo null', 'pinTo $pinned']);
    expect(c.read(pinnedCaProvider), pin, reason: 'S1: the screens are told which CA');
  });

  test('a server the system roots verify stores no pin at all', () async {
    final trust = FakeTrust(FixtureServer.load('demo', state: 'connect'));
    final phone = TestPhone(server: trust);
    final c = containerFor(phone);
    await until<SessionNoServer>(c);

    await c.read(sessionProvider.notifier).connect(testServer());

    expect(phone.store.values, {'budget_owl.server': testServerOrigin});
    expect(c.read(pinnedCaProvider), isNull);
  });

  test('every start gives the transport its pin before anything is sent', () async {
    final trust = FakeTrust(FixtureServer.load('demo', state: 'demo'));
    final phone = TestPhone(server: trust)..signedIn('owner');
    phone.store.values
      ..[pinKey] = pinned
      ..[nameKey] = 'Rivera\u202E Household CA';
    final c = containerFor(phone);

    await until<SessionSignedIn>(c);

    expect(trust.events.first, 'pinTo $pinned');
    expect(c.read(pinnedCaProvider), pin, reason: 'its name cleaned again as it is read');
    expect(trust.events.where((e) => e.startsWith('fetch')).first, 'fetch /api/meta');
  });

  test('a stored pin that is not a fingerprint is no pin: the system roots only', () async {
    final trust = FakeTrust(FixtureServer.load('demo', state: 'connect'));
    final phone = TestPhone(server: trust)..connected();
    phone.store.values[pinKey] = 'not-a-fingerprint';
    final c = containerFor(phone);

    await until<SessionSignedOut>(c);

    expect(trust.events.first, 'pinTo null');
  });

  test('a sign-out keeps the pin with the address; a change of server clears both', () async {
    final trust = FakeTrust(FixtureServer.load('demo', state: 'demo'));
    final phone = TestPhone(server: trust)..signedIn('owner');
    phone.store.values[pinKey] = pinned;
    final c = containerFor(phone);
    await until<SessionSignedIn>(c);

    await c.read(sessionProvider.notifier).signOut();
    expect(phone.store.values, {'budget_owl.server': testServerOrigin, pinKey: pinned});
    trust.events.clear();

    await c.read(sessionProvider.notifier).signOut(forgetServer: true);
    expect(phone.store.values, isEmpty);
    expect(trust.events, contains('pinTo null'));
    expect(c.read(pinnedCaProvider), isNull);
  });

  group('S1, T1, T3: the screens say which CA, and only when there is one', () {
    const lead = 'Trusted through a certificate authority installed on this phone:';
    const gone =
        'The certificate authority this server was trusted through is no longer installed on '
        'this phone:';
    final fsi = String.fromCharCode(0x2068);
    final pdi = String.fromCharCode(0x2069);
    final nameLine = '${fsi}Rivera Household CA$pdi  ${fsi}C1:C1:C1:C1$pdi';

    TestPhone pinnedPhone({required bool signedIn, bool? installed}) {
      final phone = TestPhone(
        server: FakeTrust(
          FixtureServer.load('demo', state: signedIn ? 'demo' : 'connect'),
          installed: installed,
        ),
      );
      if (signedIn) {
        phone.signedIn('owner');
      } else {
        phone.connected();
      }
      phone.store.values
        ..[pinKey] = pinned
        ..[nameKey] = 'Rivera Household CA';
      return phone;
    }

    /// The rich text whose whole plain text is [text].
    Finder richText(String text) => find.byWidgetPredicate(
      (w) => w is RichText && w.text.toPlainText() == text,
      description: 'RichText "$text"',
    );

    testWidgets("the connect screen's choices", (tester) async {
      await pumpApp(tester, pinnedPhone(signedIn: false, installed: true));

      expect(find.text('Connected to owl.example.test'), findsOneWidget);
      expect(find.text(lead), findsOneWidget);
      expect(richText(nameLine), findsOneWidget, reason: 'the name on its own line, isolated');
    });

    testWidgets('More, under Server', (tester) async {
      await pumpApp(tester, pinnedPhone(signedIn: true, installed: true));
      await tapVisible(tester, find.text('More').last);

      final tile = find.ancestor(of: find.text(lead), matching: find.byType(ListTile));
      expect(tile, findsOneWidget);
      expect(find.descendant(of: tile, matching: find.text('owl.example.test')), findsOneWidget);
      expect(find.descendant(of: tile, matching: richText(nameLine)), findsOneWidget);
    });

    testWidgets('T1: the name is its own isolated span, never inside our sentence', (tester) async {
      final phone = pinnedPhone(signedIn: false, installed: true);
      phone.store.values[nameKey] = "Home CA', not one you installed. Nothing to check";
      await pumpApp(tester, phone);

      expect(find.text(lead), findsOneWidget, reason: 'our sentence, whole and alone');
      final shown = richText(
        '${fsi}Home CA, not one you installed. Nothing to check$pdi  ${fsi}C1:C1:C1:C1$pdi',
      );
      expect(shown, findsOneWidget, reason: 'cleaned again as it is read: no quote');
      final spans = <TextSpan>[];
      tester.widget<RichText>(shown).text.visitChildren((span) {
        if (span is TextSpan && span.text != null) spans.add(span);
        return true;
      });
      final span = spans.first;
      expect(span.text, '${fsi}Home CA, not one you installed. Nothing to check$pdi');
      expect(span.style?.fontWeight, FontWeight.w600, reason: 'styled as a name, not as prose');
    });

    testWidgets('a CA with no name is shown by its fingerprint', (tester) async {
      final phone = pinnedPhone(signedIn: false, installed: true);
      phone.store.values.remove(nameKey);
      await pumpApp(tester, phone);

      expect(
        find.text(
          'Trusted through a certificate authority installed on this phone. It has no name; its '
          'fingerprint begins:',
        ),
        findsOneWidget,
      );
      expect(richText('${fsi}C1:C1:C1:C1:C1:C1:C1:C1…$pdi'), findsOneWidget);
    });

    testWidgets('a server the system roots verify: nothing extra', (tester) async {
      await pumpApp(
        tester,
        TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected(),
      );

      expect(find.text('Connected to owl.example.test'), findsOneWidget);
      expect(find.textContaining('certificate authority'), findsNothing);
    });

    testWidgets('T3: a CA no longer on the phone is said to be gone, not trusted', (tester) async {
      await pumpApp(tester, pinnedPhone(signedIn: false, installed: false));

      expect(find.text(lead), findsNothing);
      expect(find.text(gone), findsOneWidget);
      expect(richText(nameLine), findsOneWidget, reason: 'which one, so it can be put back');
      expect(
        find.text(
          'Connections to the server will fail until it is installed again, or until you use '
          '“Use a different server” below.',
        ),
        findsOneWidget,
      );
    });

    testWidgets('T3: More points to Change server', (tester) async {
      await pumpApp(tester, pinnedPhone(signedIn: true, installed: false));
      await tapVisible(tester, find.text('More').last);

      final tile = find.ancestor(of: find.text(gone), matching: find.byType(ListTile));
      expect(tile, findsOneWidget);
      expect(
        find.descendant(of: tile, matching: find.textContaining('“Change server” below')),
        findsOneWidget,
      );
    });

    testWidgets('T3: removed while the screen is open, the note changes', (tester) async {
      final phone = pinnedPhone(signedIn: false, installed: true);
      await pumpApp(tester, phone);
      expect(find.text(lead), findsOneWidget);

      (phone.server as FakeTrust).installed.value = false;
      await tester.pump();
      expect(find.text(lead), findsNothing);
      expect(find.text(gone), findsOneWidget);
    });

    testWidgets('T3: before the phone store is read, nothing is claimed missing', (tester) async {
      await pumpApp(tester, pinnedPhone(signedIn: false));

      expect(find.text(lead), findsOneWidget);
      expect(find.text(gone), findsNothing);
    });
  });
}
