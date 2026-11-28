import 'dart:io';

import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/preferences/data/theme_choice.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';
import 'helpers.dart';

/// Preferences: the member's own display currency and language, saved to the server, and the
/// theme, kept on this phone.
void main() {
  Future<(FixtureServer, TestPhone)> openPreferences(
    WidgetTester tester, {
    String state = 'household',
    String who = 'viewer',
  }) => openFromMore(tester, 'Preferences', state: state, who: who);

  testWidgets('a VIEWER saves their own currency and language: self-scoped, so allowed', (
    tester,
  ) async {
    final (server, _) = await openPreferences(tester);
    expect(find.text('Follow the household (USD)'), findsOneWidget);
    expect(find.text('Follow this device'), findsOneWidget);

    await pickFromMenu(
      tester,
      open: find.text('Follow the household (USD)'),
      item: 'EUR · €',
      up: false,
    );
    await pickFromMenu(
      tester,
      open: find.text('Follow this device'),
      item: 'Deutsch (Deutschland) · de-DE',
      up: false,
    );
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

    expect(writes(server), ['PATCH /api/households/current/members/me']);
    expect(server.state, 'prefs-saved');
    expect(find.text('Preferences saved.'), findsOneWidget);
    // The member is re-read, which carries the preference; every live read follows it.
    final membership = containerOf(tester).read(signedInProvider)!.membership!;
    expect(membership.displayCurrency, 'EUR');
    expect(membership.locale, 'de-DE');
    expect(server.served.where((s) => s.startsWith('GET /api/transactions')).length, 2);
  });

  testWidgets('"follow the default" is sent as null, which is a meaning rather than a gap', (
    tester,
  ) async {
    final (server, _) = await openPreferences(tester, state: 'prefs-saved');
    expect(find.text('EUR · €'), findsOneWidget);

    await pickFromMenu(tester, open: find.text('EUR · €'), item: 'Follow the household (USD)');
    await pickFromMenu(
      tester,
      open: find.text('Deutsch (Deutschland) · de-DE'),
      item: 'Follow this device',
    );
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

    // The fake answers only the recorded body, {"displayCurrency":null,"locale":null}.
    expect(writes(server), ['PATCH /api/households/current/members/me']);
    expect(server.state, 'household');
    expect(containerOf(tester).read(signedInProvider)!.membership!.displayCurrency, isNull);
  });

  testWidgets('says plainly that this app is in English for now', (tester) async {
    await openPreferences(tester);
    expect(find.textContaining('This app is in English for now'), findsOneWidget);
    // Not the web's hint, which promises formatting this app does not do yet.
    expect(find.textContaining('Dates and numbers always follow your choice'), findsNothing);
  });

  testWidgets('offline, saving is disabled and the theme still changes', (tester) async {
    final (server, phone) = await openPreferences(tester);
    server.offline = true;
    containerOf(tester).read(reachabilityProvider.notifier).record(Reachability.offline);
    await settle(tester);

    expect(
      tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Save')).onPressed,
      isNull,
    );
    expect(find.text("You're offline — this needs a connection"), findsOneWidget);

    await tapVisible(tester, find.text('Dark'));
    expect(containerOf(tester).read(themeChoiceProvider), ThemeChoice.dark);
    final stored = File('${phone.supportDirectory.path}/${ThemeChoiceNotifier.fileName}');
    expect(await tester.runAsync(stored.readAsString), 'dark');
    expect(writes(server), isEmpty);
  });

  group('the theme', () {
    test('drives the app, is kept on the phone, and is read back at the next launch', () async {
      final support = Directory.systemTemp.createTempSync('budget-owl-theme');
      addTearDown(() => support.deleteSync(recursive: true));
      ProviderContainer phone() {
        final container = ProviderContainer(
          overrides: [supportDirectoryProvider.overrideWith((ref) async => support)],
          retry: noRetry,
        );
        addTearDown(container.dispose);
        return container;
      }

      final first = phone();
      expect(first.read(themeModeProvider), ThemeMode.system);
      await first.read(themeChoiceProvider.notifier).choose(ThemeChoice.dark);
      expect(first.read(themeModeProvider), ThemeMode.dark);
      expect(File('${support.path}/${ThemeChoiceNotifier.fileName}').readAsStringSync(), 'dark');

      final relaunched = phone()..read(themeChoiceProvider);
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(relaunched.read(themeModeProvider), ThemeMode.dark);
    });

    test('an edited or empty file is no preference', () {
      expect(ThemeChoice.parse('light'), ThemeChoice.light);
      expect(ThemeChoice.parse(' dark\n'), ThemeChoice.dark);
      expect(ThemeChoice.parse('system'), ThemeChoice.system);
      expect(ThemeChoice.parse('Dark'), ThemeChoice.system);
      expect(ThemeChoice.parse('../token'), ThemeChoice.system);
      expect(ThemeChoice.parse(null), ThemeChoice.system);
    });
  });
}
