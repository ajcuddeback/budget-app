import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/session/current_user.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/household/data/household_repository.dart';
import 'package:budget_owl/features/household/domain/household.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';
import 'helpers.dart';

/// The Household screen and the invite sheet, through the widget tree, against the fixtures
/// `MobileHouseholdFixtureIT` recorded from the real API: what each role is offered, what is sent,
/// what the server moved to, and what is refused — by the client first where it should be, and by
/// the server, translated, where the client was not asked.
void main() {
  Future<(FixtureServer, TestPhone)> openHousehold(
    WidgetTester tester, {
    String state = 'household',
    String who = 'owner',
  }) => openFromMore(tester, 'Household', state: state, who: who);

  group('an owner', () {
    testWidgets('sees the household, every member and every control but their own', (tester) async {
      final (server, _) = await openHousehold(tester);

      expect(find.text('Rivera Household'), findsOneWidget);
      expect(find.text('Invite someone'), findsOneWidget);
      expect(find.text('Save'), findsOneWidget);
      for (final name in ['Ada', 'Grace', 'Alan']) {
        expect(find.text(name), findsOneWidget);
      }
      expect(find.text('You'), findsOneWidget);
      // Grace and Alan each get a role picker and Remove; Ada, the only owner, gets neither.
      expect(find.byType(DropdownButton<Role>), findsNWidgets(2));
      expect(find.widgetWithText(OutlinedButton, 'Remove'), findsNWidgets(2));
      expect(find.widgetWithText(OutlinedButton, 'Leave'), findsNothing);
      expect(
        find.text('You are the only owner. Make someone else an owner before you can leave.'),
        findsOneWidget,
      );
      expect(server.served, contains('GET /api/households/current/members?page=0&size=200'));
      expect(writes(server), isEmpty);
    });

    testWidgets('renames the household and changes its currency, then everything re-reads', (
      tester,
    ) async {
      final (server, _) = await openHousehold(tester);
      final body = fixtureRequest(
        'household',
        state: 'household',
        path: '/api/households/current',
        then: 'renamed',
      );

      await tester.enterText(find.widgetWithText(TextFormField, 'Household name'), 'x');
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Household name'),
        body['name']! as String,
      );
      await pickFromMenu(tester, open: find.text(r'USD · $').last, item: 'EUR · €');
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

      expect(writes(server), ['PUT /api/households/current']);
      expect(server.state, 'renamed');
      expect(find.text('Household saved.'), findsOneWidget);
      // The member is re-read for the new name, and every live read follows the new currency.
      expect(server.served.where((s) => s == 'GET /api/auth/me').length, 2);
      expect(server.served.where((s) => s.startsWith('GET /api/transactions')).length, 2);
      final container = containerOf(tester);
      expect(container.read(signedInProvider)?.membership?.householdName, 'Rivera Family');
    });

    testWidgets('a blank name is refused before anything is sent', (tester) async {
      final (server, _) = await openHousehold(tester);

      await tester.enterText(find.widgetWithText(TextFormField, 'Household name'), '   ');
      await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));

      expect(find.text('This field is required.'), findsOneWidget);
      expect(writes(server), isEmpty);
    });

    testWidgets('changes a role by choosing it, sending only the role', (tester) async {
      final (server, _) = await openHousehold(tester);

      await tapVisible(tester, find.byType(DropdownButton<Role>).first);
      await tapVisible(tester, find.text('Owner').last);

      expect(writes(server), ['PATCH /api/households/current/members/mem-grace']);
      expect(server.state, 'grace-owner');
      // The list re-reads: Grace is an owner now, so Ada is no longer the only one and may leave.
      expect(find.widgetWithText(OutlinedButton, 'Leave'), findsOneWidget);
      expect(find.textContaining('You are the only owner'), findsNothing);
    });

    testWidgets('removing asks first, in the web words, then removes', (tester) async {
      final (server, _) = await openHousehold(tester);

      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Remove').first);
      expect(find.text('Remove Grace?'), findsOneWidget);
      expect(
        find.text(
          'Grace will lose access to this household straight away and be signed out '
          'everywhere. What they recorded stays in the household.',
        ),
        findsOneWidget,
      );
      await tapVisible(tester, find.text('Cancel'));
      expect(writes(server), isEmpty);

      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Remove').first);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Remove').last);

      expect(writes(server), ['DELETE /api/households/current/members/mem-grace']);
      expect(server.state, 'grace-removed');
      expect(find.text('Grace'), findsNothing);
      expect(find.text('Alan'), findsOneWidget);
    });

    testWidgets("an owner's own role and removal, if ever sent, are the server's refusals", (
      tester,
    ) async {
      await openHousehold(tester);
      final container = containerOf(tester);
      final writer = container.read(householdWriterProvider);
      final me = container.read(membersProvider).data!.firstWhere((m) => m.id == 'mem-ada');

      final role = await tester.runAsync(() => refusal(() => writer.changeRole(me, Role.member)));
      final leave = await tester.runAsync(() => refusal(() => writer.leave(me.id)));

      expect(role?.code, 'own-role-unchangeable');
      expect(english(role!), 'Nobody can change their own role. Ask another owner.');
      expect(leave?.code, 'last-owner');
      expect(
        english(leave!),
        'A household must keep at least one owner. Make someone else an owner first.',
      );
    });
  });

  group('the invite sheet', () {
    testWidgets('makes a link for this server, shows it once, and copies it only when asked', (
      tester,
    ) async {
      final (server, phone) = await openHousehold(tester);
      final clipboard = phone.clipboard;

      final cache = await tester.runAsync(
        () => containerOf(tester).read(cacheHolderProvider).obtain(),
      );
      final entriesBefore = await tester.runAsync(cache!.count);
      await tapVisible(tester, find.text('Invite someone'));
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Their email address'),
        'edsger@example.com',
      );
      await tapVisible(tester, find.text('Viewer'));
      await tapVisible(tester, find.text('Create link'));

      expect(writes(server), ['POST /api/households/current/invitations']);
      const link = '$testServerOrigin/join/fixture-invitation-edsger';
      expect(find.text(link), findsOneWidget);
      expect(
        find.text('Share this link with edsger@example.com. This is the only time it is shown.'),
        findsOneWidget,
      );
      expect(find.textContaining('anyone who has it can join this household as Viewer'), findsOne);
      // Nothing reached the clipboard until the owner asked.
      expect(clipboard.text, isNull);
      await tapVisible(tester, find.text('Copy link'));
      expect(clipboard.text, link);
      expect(clipboard.sensitive, isTrue, reason: 'L2: marked sensitive');
      expect(find.text('Link copied.'), findsOneWidget);

      // Never kept: not in secure storage, and nothing new in the cache — which is encrypted, so
      // its bytes would say nothing; what was written to it does.
      expect(phone.store.values.values.where((v) => v.contains('fixture-invitation')), isEmpty);
      expect(await tester.runAsync(cache.count), entriesBefore);

      // Closing drops it: the sheet opens empty the next time, and the clipboard is taken back.
      await tapVisible(tester, find.text('Done'));
      expect(clipboard.text, isNull, reason: 'L2: still held the link, so cleared');
      await tapVisible(tester, find.text('Invite someone'));
      expect(find.text(link), findsNothing);
      expect(find.text('Create link'), findsOneWidget);
    });

    Future<String> makeLink(WidgetTester tester) async {
      await tapVisible(tester, find.text('Invite someone'));
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Their email address'),
        'edsger@example.com',
      );
      await tapVisible(tester, find.text('Viewer'));
      await tapVisible(tester, find.text('Create link'));
      await tapVisible(tester, find.text('Copy link'));
      return '$testServerOrigin/join/fixture-invitation-edsger';
    }

    testWidgets('L2: a revoked link is taken back from the clipboard at once', (tester) async {
      final (_, phone) = await openHousehold(tester);
      final link = await makeLink(tester);
      expect(phone.clipboard.text, link);

      await tapVisible(tester, find.text('Revoke link'));

      expect(find.text('This link has been revoked and can no longer be used.'), findsOneWidget);
      expect(phone.clipboard.text, isNull);
    });

    testWidgets('L2: what the person copied since is never cleared', (tester) async {
      final (_, phone) = await openHousehold(tester);
      await makeLink(tester);
      phone.clipboard.copiedElsewhere('flat 4, 12 Mill Lane');

      await tapVisible(tester, find.text('Revoke link'));
      await tapVisible(tester, find.text('Done'));

      expect(phone.clipboard.text, 'flat 4, 12 Mill Lane');
      expect(phone.clipboard.clears, 0);
    });

    testWidgets('L2: closed without copying, the clipboard is not touched', (tester) async {
      final (_, phone) = await openHousehold(tester);
      phone.clipboard.copiedElsewhere('earlier');
      await tapVisible(tester, find.text('Invite someone'));
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Their email address'),
        'edsger@example.com',
      );
      await tapVisible(tester, find.text('Viewer'));
      await tapVisible(tester, find.text('Create link'));
      await tapVisible(tester, find.text('Done'));

      expect(phone.clipboard.text, 'earlier');
      expect(phone.clipboard.clears, 0);
    });

    testWidgets('revokes a link before anybody uses it', (tester) async {
      final (server, _) = await openHousehold(tester);

      await tapVisible(tester, find.text('Invite someone'));
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Their email address'),
        'edsger@example.com',
      );
      await tapVisible(tester, find.text('Viewer'));
      await tapVisible(tester, find.text('Create link'));
      await tapVisible(tester, find.text('Revoke link'));

      expect(writes(server), [
        'POST /api/households/current/invitations',
        'DELETE /api/households/current/invitations/gen-0023',
      ]);
      expect(find.text('This link has been revoked and can no longer be used.'), findsOneWidget);
      expect(find.textContaining('fixture-invitation'), findsNothing);
      expect(find.text('Copy link'), findsNothing);
    });

    testWidgets("an address that is already a member is refused in the web's words", (
      tester,
    ) async {
      final (server, _) = await openHousehold(tester);

      await tapVisible(tester, find.text('Invite someone'));
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Their email address'),
        'grace@example.com',
      );
      await tapVisible(tester, find.text('Create link'));

      expect(writes(server), ['POST /api/households/current/invitations']);
      expect(
        find.text('An invitation could not be created for that email address.'),
        findsOneWidget,
      );
      expect(find.text('Create link'), findsOneWidget);
    });

    testWidgets('an address that is not one is refused before anything is sent', (tester) async {
      final (server, _) = await openHousehold(tester);

      await tapVisible(tester, find.text('Invite someone'));
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Their email address'),
        'not an address',
      );
      await tapVisible(tester, find.text('Create link'));

      expect(find.text('Enter a valid email address.'), findsOneWidget);
      expect(writes(server), isEmpty);
    });
  });

  group('a member and a viewer', () {
    testWidgets('a VIEWER sees everyone and no control but Leave', (tester) async {
      final (server, _) = await openHousehold(tester, who: 'viewer');

      expect(find.text('Only an owner can change these.'), findsOneWidget);
      expect(find.text('Invite someone'), findsNothing);
      expect(find.text('Save'), findsNothing);
      expect(find.byType(TextFormField), findsNothing);
      expect(find.byType(DropdownButton<Role>), findsNothing);
      expect(find.text('Remove'), findsNothing);
      expect(find.widgetWithText(OutlinedButton, 'Leave'), findsOneWidget);
      expect(writes(server), isEmpty);
    });

    testWidgets("a VIEWER's and a MEMBER's forced writes are the API's translated 403", (
      tester,
    ) async {
      await openHousehold(tester, who: 'viewer');
      final container = containerOf(tester);
      final writer = container.read(householdWriterProvider);
      final grace = container.read(membersProvider).data!.firstWhere((m) => m.id == 'mem-grace');

      final refusals = await tester.runAsync(
        () async => [
          await refusal(() => writer.changeRole(grace, Role.viewer)),
          await refusal(() => writer.remove(grace)),
          await refusal(() => writer.rename(name: 'Rivera Household', baseCurrency: 'USD')),
          await refusal(() => writer.invite(email: 'edsger@example.com', role: Role.member)),
        ],
      );

      for (final refusal in refusals!) {
        expect(refusal?.status, 403);
        expect(english(refusal!), 'Only an owner of the household can do that.');
      }
    });

    testWidgets('demoted on the web, an owner phone offers what the server then refuses', (
      tester,
    ) async {
      // Grace's phone read the household while she was an owner; she was demoted since.
      final (server, _) = await openHousehold(tester, state: 'grace-demoted', who: 'member');
      expect(find.byType(DropdownButton<Role>), findsNWidgets(2));

      await tapVisible(tester, find.byType(DropdownButton<Role>).last);
      await tapVisible(tester, find.text('Member').last);

      expect(writes(server), ['PATCH /api/households/current/members/mem-alan']);
      expect(find.text('Only an owner of the household can do that.'), findsOneWidget);
      // The list is the truth and is left as it was: Alan is still shown a viewer.
      expect(find.text('Viewer'), findsWidgets);
    });

    testWidgets('leaving asks first, and the revoked token then wipes the phone', (tester) async {
      final (server, phone) = await openHousehold(tester, who: 'viewer');

      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Leave'));
      expect(find.text('Leave this household?'), findsOneWidget);
      await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Leave').last);

      expect(writes(server), ['DELETE /api/households/current/members/mem-alan']);
      expect(server.state, 'alan-left');
      // Leaving revoked every token Alan had: the re-read's 401 ended the session, and nothing of
      // the household is left on the phone.
      expect(find.text('Your session ended. Sign in again to continue.'), findsOneWidget);
      expect(phone.store.values.keys, ['budget_owl.server']);
      expect(phone.cacheFile.existsSync(), isFalse);
    });
  });

  group('the version gate', () {
    testWidgets('until the server version is confirmed, writes are off and say why', (
      tester,
    ) async {
      final (server, _) = await openHousehold(tester);
      final container = containerOf(tester);
      container.read(reachabilityProvider.notifier).unconfirmVersion();
      await settle(tester);

      expect(container.read(reachabilityProvider), Reachability.unconfirmed);
      expect(find.textContaining('Changes are off until the server confirms'), findsOneWidget);
      expect(find.text("You're offline — this needs a connection"), findsNothing);
      expect(
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Save')).onPressed,
        isNull,
      );
      final refused = await tester.runAsync(
        () => refusal(() => container.read(householdWriterProvider).leave('mem-ada')),
      );
      expect(refused?.kind, AppErrorKind.offline);
      expect(writes(server), isEmpty);
    });
  });

  group('offline', () {
    testWidgets('every write is disabled with the offline note, and nothing is sent', (
      tester,
    ) async {
      final (server, _) = await openHousehold(tester);
      // The server stays reachable: the last request failing is what the phone knows, and a
      // write the writer did not refuse itself would be served and counted below.
      containerOf(tester).read(reachabilityProvider.notifier).record(Reachability.offline);
      await settle(tester);

      expect(find.text("You're offline — this needs a connection"), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Save')).onPressed,
        isNull,
      );
      expect(
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Invite someone')).onPressed,
        isNull,
      );
      for (final remove in tester.widgetList<OutlinedButton>(
        find.widgetWithText(OutlinedButton, 'Remove'),
      )) {
        expect(remove.onPressed, isNull);
      }
      for (final picker in tester.widgetList<DropdownButton<Role>>(
        find.byType(DropdownButton<Role>),
      )) {
        expect(picker.onChanged, isNull);
      }

      // The writer refuses on its own as well: the disabled control is not the only guard.
      final container = containerOf(tester);
      final refused = await tester.runAsync(
        () => refusal(
          () => container
              .read(householdWriterProvider)
              .rename(name: 'Rivera Household', baseCurrency: 'USD'),
        ),
      );
      expect(refused?.kind, AppErrorKind.offline);
      expect(writes(server), isEmpty);
    });
  });

  test('a member list gives each row the controls the server allows', () {
    Member m(String id, Role role) => Member(
      id: id,
      userId: 'u-$id',
      email: '$id@example.com',
      displayName: id,
      role: role,
      joinedAt: DateTime.utc(2026),
    );
    final ada = m('ada', Role.owner);
    final grace = m('grace', Role.member);
    final all = [ada, grace];

    expect(
      controlsFor(ada, myMembershipId: 'ada', iAmOwner: true, all: all),
      MemberControls.soleOwner,
    );
    expect(
      controlsFor(grace, myMembershipId: 'ada', iAmOwner: true, all: all),
      MemberControls.manage,
    );
    expect(
      controlsFor(grace, myMembershipId: 'grace', iAmOwner: false, all: all),
      MemberControls.leave,
    );
    expect(
      controlsFor(ada, myMembershipId: 'grace', iAmOwner: false, all: all),
      MemberControls.none,
    );
    final twoOwners = [ada, m('grace', Role.owner)];
    expect(
      controlsFor(ada, myMembershipId: 'ada', iAmOwner: true, all: twoOwners),
      MemberControls.leave,
    );
  });
}
