import 'package:flutter_test/flutter_test.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// Bills & income, rendered from `bills.generated.json` (MobilePlansFixtureIT): the page in every
/// variant, its states, and its sheets. `tools/mobile-check.sh` renders them; **read them**.
void main() => runGoldens(scenarios);

Future<TestPhone> billsPhone(
  WidgetTester tester,
  Variant v, {
  String state = 'plans',
  String who = 'owner',
}) async {
  final phone = await signedInPhone(tester, v, file: 'bills', state: state, who: who);
  await tapText(tester, 'Bills');
  return phone;
}

final List<Scenario> scenarios = [const Scenario('bills', everyWay, billsPhone)];
