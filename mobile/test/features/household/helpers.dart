import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/errors/error_text.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';

/// Signs [who] in against `household.generated.json` in [state], then opens More → [entry].
Future<(FixtureServer, TestPhone)> openFromMore(
  WidgetTester tester,
  String entry, {
  String state = 'household',
  String who = 'owner',
}) async {
  final server = FixtureServer.load('household', state: state);
  final phone = TestPhone(server: server)..signedIn(who);
  await pumpApp(tester, phone);
  await tapVisible(tester, find.text('More').last);
  await tapVisible(tester, find.text(entry).last);
  return (server, phone);
}

ProviderContainer containerOf(WidgetTester tester) =>
    ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));

/// Everything sent that was not a read.
List<String> writes(FixtureServer server) =>
    server.served.where((s) => !s.startsWith('GET')).toList();

/// Opens a dropdown and chooses [item], scrolling its menu, which builds its rows lazily and opens
/// at the current choice — so [up] when the item sorts before it.
Future<void> pickFromMenu(
  WidgetTester tester, {
  required Finder open,
  required String item,
  bool up = true,
}) async {
  await tapVisible(tester, open);
  final choice = find.text(item);
  for (var i = 0; i < 60 && choice.evaluate().isEmpty; i++) {
    await tester.drag(find.byType(Scrollable).last, Offset(0, up ? 300 : -300));
    await tester.pumpAndSettle();
  }
  // A row can be built in the menu's cache extent and still be out of sight; a tap there lands
  // outside the menu and closes it without choosing anything.
  await tester.ensureVisible(choice.last);
  await tester.pumpAndSettle();
  await tester.tap(choice.last);
  await settle(tester);
}

/// What a write refused with, or null if it was not refused. Bounded, so a write that should have
/// been refused and instead hangs fails the test rather than the run.
Future<AppError?> refusal(Future<Object?> Function() write) async {
  try {
    await write().timeout(const Duration(seconds: 10));
    return null;
  } on AppError catch (error) {
    return error;
  }
}

/// The sentence a person is shown for [error], in English.
String english(AppError error) => errorText(AppLocalizationsEn(), error, 'en_US');
