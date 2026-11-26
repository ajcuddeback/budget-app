import 'package:flutter_test/flutter_test.dart';

import '../support/harness.dart';
import 'golden_harness.dart';

/// The debt plan and debt detail, rendered from `debt.generated.json` (MobilePlansFixtureIT).
/// `tools/mobile-check.sh` renders them; **read them**.
void main() => runGoldens(scenarios);

Future<TestPhone> planPhone(
  WidgetTester tester,
  Variant v, {
  String state = 'plans',
  String who = 'owner',
}) async {
  final phone = await signedInPhone(tester, v, file: 'debt', state: state, who: who);
  await tapText(tester, 'More');
  await tapText(tester, 'Debt plan');
  return phone;
}

final List<Scenario> scenarios = [const Scenario('debt-plan', everyWay, planPhone)];
