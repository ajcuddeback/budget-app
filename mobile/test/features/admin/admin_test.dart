import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/routing/router.dart';
import 'package:budget_owl/features/admin/data/admin_repository.dart';
import 'package:budget_owl/features/admin/presentation/admin_screen.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';
import '../household/helpers.dart';

/// The admin console, against the fixtures `MobileHouseholdFixtureIT` recorded: shown only to the
/// instance administrator, refused by the server to anybody else (a household `OWNER` included),
/// and every rule the web console keeps — the secret never shown, the lockout explained, the
/// provider's switch-off confirmed, and a stale save told rather than overwritten.
void main() {
  Future<(FixtureServer, TestPhone)> openConsole(
    WidgetTester tester, {
    String state = 'household',
  }) => openFromMore(tester, 'Admin', state: state);

  Finder switchFor(String title) => find.widgetWithText(SwitchListTile, title);

  Future<void> save(WidgetTester tester) =>
      tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

  /// What the console sent, recorded by `MobileHouseholdFixtureIT` as [path] in [state].
  Map<String, Object?> recorded(String state, {String? then}) =>
      fixtureRequest('household', state: state, path: '/api/admin/settings', then: then);

  group('who sees it', () {
    testWidgets('More lists Admin for the administrator, last', (tester) async {
      await signInAs(tester, 'owner');
      await tapVisible(tester, find.text('More').last);
      expect(find.text('Admin'), findsOneWidget);
    });

    testWidgets('a household OWNER who is not the administrator is not offered it', (tester) async {
      await signInAs(tester, 'member', state: 'grace-owner');
      await tapVisible(tester, find.text('More').last);
      expect(find.text('Household'), findsOneWidget);
      expect(find.text('Admin'), findsNothing);
    });

    testWidgets('a deep link to the console takes anybody else back to More, unread', (
      tester,
    ) async {
      final server = await signInAs(tester, 'member', state: 'grace-owner');
      containerOf(tester).read(routerProvider).go('/more/admin');
      await settle(tester);

      expect(find.byType(AdminScreen), findsNothing);
      expect(find.text('Debt plan'), findsOneWidget);
      expect(server.served.where((s) => s.contains('/api/admin/')), isEmpty);
    });

    testWidgets('the server refuses anybody else regardless, and the refusal is translated', (
      tester,
    ) async {
      await signInAs(tester, 'member', state: 'grace-owner');
      final container = containerOf(tester);
      final settings = container.listen(settingsProvider, (_, _) {});
      addTearDown(settings.close);
      await settle(tester);

      final read = container.read(settingsProvider).error;
      final write = await tester.runAsync(
        () => refusal(
          () => container.read(adminWriterProvider).saveSettings({
            'registrationOpen': true,
            'version': 0,
          }),
        ),
      );

      for (final refused in [read, write]) {
        expect(refused?.status, 403);
        expect(refused?.code, 'not-instance-admin');
        expect(
          english(refused!),
          'Only the person who administers this instance can do that. On a self-hosted Budget '
          'Owl that is whoever set it up.',
        );
      }
    });
  });

  group('the console', () {
    testWidgets('shows the instance, the rules beside their switches, and who can sign in', (
      tester,
    ) async {
      final (server, _) = await openConsole(tester);

      expect(find.text('unknown'), findsOneWidget);
      expect(find.text('ada@example.com'), findsWidgets);
      expect(find.text('3'), findsOneWidget);
      expect(find.textContaining('anybody who can reach this server'), findsOneWidget);
      // Locked: the rule, and no switch that would answer 409.
      expect(find.text('Email and password sign-in is on.'), findsOneWidget);
      expect(switchFor('Offer sign-in with an email address and a password'), findsNothing);
      // The provider is off: none of its fields, and so no secret field at all.
      expect(find.widgetWithText(TextFormField, 'Client secret'), findsNothing);
      await revealIn(tester, find.text('Administrator').last);
      expect(find.text('Active'), findsNWidgets(3));
      expect(writes(server), isEmpty);
    });

    testWidgets('opening registration says so first, then sends only it, with the version', (
      tester,
    ) async {
      final (server, _) = await openConsole(tester);

      await tapVisible(tester, switchFor('Let anyone create an account'));
      expect(find.textContaining('Saving now will open registration.'), findsOneWidget);
      await save(tester);

      expect(writes(server), ['PATCH /api/admin/settings']);
      expect(server.state, 'registration-open');
      expect(recorded('household'), {'registrationOpen': true, 'version': 0});
      expect(find.text('Saved.'), findsOneWidget);
    });

    testWidgets('an untouched form sends nothing, and says so', (tester) async {
      final (server, _) = await openConsole(tester);
      await save(tester);

      expect(find.text('Nothing had changed, so nothing was saved.'), findsOneWidget);
      expect(writes(server), isEmpty);
    });

    testWidgets('a provider: the issuer must be https, the secret is sent once and never shown', (
      tester,
    ) async {
      final (server, _) = await openConsole(tester);
      await tapVisible(tester, switchFor('Let anyone create an account'));
      await save(tester);
      final body = recorded('registration-open', then: 'oidc-on');

      await tapVisible(tester, switchFor('Offer sign-in through my provider'));
      expect(find.text('No secret set.'), findsOneWidget);
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Issuer URL'),
        'http://id.example.org',
      );
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Client ID'),
        body['oidcClientId']! as String,
      );
      final secret = body['oidcClientSecret']! as String;
      await tester.enterText(find.widgetWithText(TextFormField, 'Client secret'), secret);
      await save(tester);
      // Refused here, before anything is sent, and for the issuer alone: over http:// the client
      // secret would cross the network in clear.
      expect(
        find.textContaining('or http:// only if it runs on this same machine'),
        findsOneWidget,
      );
      expect(find.text('This field is required.'), findsNothing);
      expect(writes(server), ['PATCH /api/admin/settings']);

      await tester.enterText(
        find.widgetWithText(TextFormField, 'Issuer URL'),
        body['oidcIssuerUri']! as String,
      );
      final field = tester.widget<EditableText>(
        find.descendant(
          of: find.widgetWithText(TextFormField, 'Client secret'),
          matching: find.byType(EditableText),
        ),
      );
      expect(field.obscureText, isTrue);
      expect(field.enableSuggestions, isFalse);
      // Not visiblePassword: on Android that variation tells the keyboard the text is shown in
      // clear, and some keyboards then offer it in their suggestion strip.
      expect(field.keyboardType, isNot(TextInputType.visiblePassword));
      expect(drawn(secret), findsNothing);
      await save(tester);

      expect(writes(server), ['PATCH /api/admin/settings', 'PATCH /api/admin/settings']);
      expect(server.state, 'oidc-on');
      expect(find.text('Saved.'), findsOneWidget);
      expect(find.text('A secret is set.'), findsOneWidget);
      // Empty after the save, as on every load: there is no value to put in it.
      final after = tester.widget<TextFormField>(
        find.widgetWithText(TextFormField, 'Client secret'),
      );
      expect(after.controller?.text, isEmpty);
    });

    testWidgets('turning the provider off is confirmed; cancelling puts the switch back', (
      tester,
    ) async {
      final (server, _) = await openConsole(tester, state: 'oidc-on');

      await tapVisible(tester, switchFor('Offer sign-in through my provider'));
      expect(find.textContaining('also switches email and password sign-in back on'), findsOne);
      await save(tester);
      expect(find.text('Turn off single sign-on?'), findsOneWidget);
      await tapVisible(tester, find.text('Cancel'));

      expect(writes(server), isEmpty);
      expect(
        tester.widget<SwitchListTile>(switchFor('Offer sign-in through my provider')).value,
        isTrue,
      );

      await tapVisible(tester, switchFor('Offer sign-in through my provider'));
      await save(tester);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Turn it off'));

      expect(writes(server), ['PATCH /api/admin/settings']);
      expect(recorded('oidc-on', then: 'oidc-off'), {'oidcEnabled': false, 'version': 2});
      expect(server.state, 'oidc-off');
    });

    testWidgets('somebody saved first: a 409 names what moved and keeps what was typed', (
      tester,
    ) async {
      final (server, _) = await openConsole(tester, state: 'admin-stale');
      final body = recorded('admin-stale', then: 'admin-conflict');

      await tapVisible(tester, switchFor('Offer sign-in through my provider'));
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Client secret'),
        body['oidcClientSecret']! as String,
      );
      await save(tester);

      expect(server.state, 'admin-conflict');
      expect(find.textContaining('Somebody else saved first'), findsOneWidget);
      // Names only — one of these fields is a secret.
      expect(find.textContaining('•  Registration'), findsOneWidget);
      expect(drawn('placeholder'), findsNothing);
      expect(
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Save')).onPressed,
        isNull,
      );
      expect(
        tester.widget<SwitchListTile>(switchFor('Offer sign-in through my provider')).value,
        isTrue,
      );

      await tapVisible(tester, find.text('Save my changes anyway'));

      expect(writes(server), ['PATCH /api/admin/settings', 'PATCH /api/admin/settings']);
      expect(server.state, 'conflict-saved');
      expect(recorded('admin-conflict')['version'], 4);
      expect(find.text('Saved.'), findsOneWidget);
    });

    testWidgets('"take the latest" drops what was typed and sends nothing', (tester) async {
      final (server, _) = await openConsole(tester, state: 'admin-stale');
      final body = recorded('admin-stale', then: 'admin-conflict');

      await tapVisible(tester, switchFor('Offer sign-in through my provider'));
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Client secret'),
        body['oidcClientSecret']! as String,
      );
      await save(tester);
      await tapVisible(tester, find.text('Take the latest'));

      expect(writes(server), ['PATCH /api/admin/settings']);
      expect(
        tester.widget<SwitchListTile>(switchFor('Offer sign-in through my provider')).value,
        isFalse,
      );
      expect(find.textContaining('Somebody else saved first'), findsNothing);
    });

    testWidgets(
      'once an owner has proved the provider, password sign-in can go — the way back named first',
      (tester) async {
        final (server, _) = await openConsole(tester, state: 'oidc-proven');
        final password = switchFor('Offer sign-in with an email address and a password');
        expect(password, findsOneWidget);

        await tapVisible(tester, password);
        expect(find.text('deploy/restore-password-login.sh'), findsOneWidget);
        await save(tester);

        expect(writes(server), ['PATCH /api/admin/settings']);
        expect(recorded('oidc-proven'), {'passwordLoginEnabled': false, 'version': 5});
        expect(server.state, 'password-off');
        // Off, the switch stays offered, so turning it back on is never unreachable from here.
        expect(tester.widget<SwitchListTile>(password).value, isFalse);
      },
    );

    testWidgets('proof withdrawn meanwhile: the 409 explains itself and the screen re-reads', (
      tester,
    ) async {
      final (server, _) = await openConsole(tester, state: 'proof-withdrawn');

      await tapVisible(tester, switchFor('Offer sign-in with an email address and a password'));
      await save(tester);

      expect(server.state, 'locked-refused');
      expect(find.textContaining('cannot be turned off yet. An owner has to complete'), findsOne);
      expect(switchFor('Offer sign-in with an email address and a password'), findsNothing);
      expect(find.text('Email and password sign-in is on.'), findsOneWidget);
    });

    testWidgets('offline, saving is disabled and the writer refuses on its own', (tester) async {
      final (server, _) = await openConsole(tester);
      // The server stays reachable, so a write the writer did not refuse would be served.
      final container = containerOf(tester);
      container.read(reachabilityProvider.notifier).record(Reachability.offline);
      await settle(tester);

      expect(
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Save')).onPressed,
        isNull,
      );
      final refused = await tester.runAsync(
        () => refusal(
          () => container.read(adminWriterProvider).saveSettings({
            'registrationOpen': true,
            'version': 0,
          }),
        ),
      );
      expect(refused?.kind, AppErrorKind.offline);
      expect(writes(server), isEmpty);
    });
  });
}

/// Text that is **drawn** containing [value]: a `Text` or `RichText`. An obscured field's
/// `EditableText` holds what was typed and draws dots, so it is deliberately not matched.
Finder drawn(String value) => find.byWidgetPredicate(
  (widget) => switch (widget) {
    Text(:final data?) => data.contains(value),
    RichText(:final text) => text.toPlainText().contains(value),
    _ => false,
  },
);

/// Signed in as [who], on the Transactions tab.
Future<FixtureServer> signInAs(
  WidgetTester tester,
  String who, {
  String state = 'household',
}) async {
  final server = FixtureServer.load('household', state: state);
  await pumpApp(tester, TestPhone(server: server)..signedIn(who));
  return server;
}
