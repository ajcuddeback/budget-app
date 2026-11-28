import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';

/// Registers a golden test for every variant of every scenario.
///
/// Each area of the app keeps its scenarios in its own `<area>_golden_test.dart`, whose `main`
/// is `runGoldens(scenarios)`, so features can add goldens without editing one shared list.
void runGoldens(List<Scenario> scenarios) {
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

Future<TestPhone> pumpPhone(WidgetTester tester, Variant v, TestPhone phone) async {
  await pumpApp(
    tester,
    phone,
    themeMode: v.dark ? ThemeMode.dark : ThemeMode.light,
    direction: v.rtl ? TextDirection.rtl : null,
    textScale: v.textScale,
  );
  return phone;
}

Future<TestPhone> signedInPhone(
  WidgetTester tester,
  Variant v, {
  String state = 'demo',
  String who = 'owner',
  String file = 'demo',
}) {
  final phone = TestPhone(server: FixtureServer.load(file, state: state))..signedIn(who);
  return pumpPhone(tester, v, phone);
}

Future<void> tapText(WidgetTester tester, String text) => tapVisible(tester, find.text(text).last);
