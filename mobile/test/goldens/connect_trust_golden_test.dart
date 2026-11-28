import 'package:flutter_test/flutter_test.dart';

import '../support/fake_trust.dart';
import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// S1, T1, T3: a server pinned to a CA the person installed says so — on the Connect screen's
/// choices, and under More → Server beside the address: our sentence, then the CA's name on its own
/// line with the start of its fingerprint. When that CA is no longer on the phone, it says so
/// instead, and points to changing server. A server the system roots verify says nothing extra
/// (connect-choices and more in screens_golden_test.dart).
void main() => runGoldens(scenarios);

/// A household server whose certificate a CA installed on the phone vouches for — or, when
/// [installed] is false, vouched for until it was removed in Settings.
TestPhone _pinned({required bool signedIn, required bool installed}) {
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
    ..['budget_owl.server_ca_pin'] = 'c1' * 32
    ..['budget_owl.server_ca_name'] = 'Rivera Household CA';
  return phone;
}

Future<TestPhone> _more(WidgetTester tester, Variant v, {required bool installed}) async {
  final phone = await pumpPhone(tester, v, _pinned(signedIn: true, installed: installed));
  await tapText(tester, 'More');
  // The Server section is at the end of the list: shown, not cut off by the tab bar.
  await revealIn(tester, find.text('Sign out'));
  // At double text size the missing-CA tile is taller than the screen: its top, the address, wins.
  await tester.ensureVisible(find.text('owl.example.test'));
  await tester.pumpAndSettle();
  return phone;
}

final List<Scenario> scenarios = [
  Scenario(
    'connect-choices-pinned',
    everyWay,
    (tester, v) => pumpPhone(tester, v, _pinned(signedIn: false, installed: true)),
  ),
  Scenario('more-pinned', [light, dark, large], (t, v) => _more(t, v, installed: true)),
  Scenario(
    'connect-choices-pin-missing',
    everyWay,
    (tester, v) => pumpPhone(tester, v, _pinned(signedIn: false, installed: false)),
  ),
  Scenario('more-pin-missing', [light, dark, large], (t, v) => _more(t, v, installed: false)),
];
