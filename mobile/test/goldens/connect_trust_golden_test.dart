import 'package:flutter_test/flutter_test.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// S1: a server pinned to a CA the person installed says so — on the Connect screen's choices,
/// and under More → Server beside the address. A server the system roots verify says nothing
/// extra (connect-choices and more in screens_golden_test.dart).
void main() => runGoldens(scenarios);

/// A household server whose certificate a CA installed on the phone vouches for.
TestPhone _pinned(TestPhone phone) {
  phone.store.values
    ..['budget_owl.server_ca_pin'] = 'c1' * 32
    ..['budget_owl.server_ca_name'] = 'Rivera Household CA';
  return phone;
}

final List<Scenario> scenarios = [
  Scenario(
    'connect-choices-pinned',
    everyWay,
    (tester, v) => pumpPhone(
      tester,
      v,
      _pinned(TestPhone(server: FixtureServer.load('demo', state: 'connect'))..connected()),
    ),
  ),
  Scenario('more-pinned', [light, dark, large], (tester, v) async {
    final phone = await pumpPhone(
      tester,
      v,
      _pinned(TestPhone(server: FixtureServer.load('demo', state: 'demo'))..signedIn('owner')),
    );
    await tapText(tester, 'More');
    // The Server section is at the end of the list: shown, not cut off by the tab bar.
    await revealIn(tester, find.text('Sign out'));
    return phone;
  }),
];
