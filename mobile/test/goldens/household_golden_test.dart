import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/session/current_user.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// Household, Preferences and Admin (slice 15, phase 2), drawn at 390×844 from the fixtures
/// `MobileHouseholdFixtureIT` recorded from the real API. `tools/mobile-check.sh` renders them;
/// **read every PNG** — a golden nobody looked at is not a check.
void main() => runGoldens(scenarios);

/// Signed in as [who] against the household fixtures, then More → [entry].
Future<TestPhone> openFromMore(
  WidgetTester tester,
  Variant v,
  String entry, {
  String state = 'household',
  String who = 'owner',
}) async {
  final phone = await signedInPhone(tester, v, file: 'household', state: state, who: who);
  await tapText(tester, 'More');
  await tapText(tester, entry);
  return phone;
}

/// The phone loses its connection, two hours pass, and the screen is pulled to refresh: what it
/// shows then is the cached copy with its stale label, and every write disabled.
Future<void> goStale(WidgetTester tester, TestPhone phone) async {
  (phone.server as FixtureServer).offline = true;
  phone.now = demoNow.add(const Duration(hours: 2));
  await tester.fling(find.byType(ListView).first, const Offset(0, 400), 1000);
  await settle(tester);
}

/// Scrolls the screen until [text] is in view, for a state further down a long screen.
Future<void> scrollTo(WidgetTester tester, String text) async {
  await revealIn(tester, find.text(text).last);
  await settle(tester);
}

/// Taps [text] in the invite sheet, scrolling **the sheet's list** to it. Not the harness's
/// "last Scrollable": a text field and the link's SelectableText each have one inside them.
Future<void> tapInSheet(WidgetTester tester, String text) async {
  final target = find.text(text);
  // Dragged on plain text that is on screen: a drag that starts on the selectable link or a text
  // field selects text instead of scrolling, and the list builds a little past its edge.
  Finder plain() => find
      .descendant(
        of: find.byType(ListView).last,
        matching: find.byWidgetPredicate((w) => w is Text && w.data != null),
      )
      .hitTestable();
  if (target.evaluate().isEmpty) {
    // The sheet keeps its scroll offset when its content changes: start from the top.
    await tester.drag(plain().first, const Offset(0, 3000));
    await tester.pumpAndSettle();
  }
  for (var i = 0; i < 30 && target.evaluate().isEmpty; i++) {
    await tester.drag(plain().last, const Offset(0, -200));
    await tester.pumpAndSettle();
  }
  await tester.ensureVisible(target.last);
  await tester.pumpAndSettle();
  await tester.tap(target.last);
  await settle(tester);
}

Future<void> inviteEdsger(WidgetTester tester) async {
  await tapText(tester, 'Invite someone');
  await tester.enterText(
    find.widgetWithText(TextFormField, 'Their email address'),
    'edsger@example.com',
  );
  await tapInSheet(tester, 'Viewer');
  await tapInSheet(tester, 'Create link');
}

final List<Scenario> scenarios = [
  // ---- Household
  Scenario('household', everyWay, (tester, v) => openFromMore(tester, v, 'Household')),

  Scenario('household-members', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Household');
    await scrollTo(tester, 'Alan');
    return phone;
  }),

  Scenario('household-viewer', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Household', who: 'viewer');
    await scrollTo(tester, 'Leave');
    return phone;
  }),

  Scenario('household-refused', bothThemes, (tester, v) async {
    // Grace's phone read the household while she was an owner; demoted since, the server refuses.
    final phone = await openFromMore(tester, v, 'Household', state: 'grace-demoted', who: 'member');
    await scrollTo(tester, 'Alan');
    await tapVisible(tester, find.byType(DropdownButton<Role>).last);
    await tapText(tester, 'Member');
    await scrollTo(tester, 'Members');
    return phone;
  }),

  Scenario('household-offline-stale', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Household');
    await goStale(tester, phone);
    return phone;
  }),

  Scenario('household-error', bothThemes, (tester, v) async {
    final phone = await signedInPhone(tester, v, file: 'household', state: 'household');
    final server = phone.server as FixtureServer;
    await tapText(tester, 'More');
    server.offline = true;
    await tapText(tester, 'Household');
    return phone;
  }),

  Scenario('household-remove-confirm', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Household');
    await tapVisible(tester, find.widgetWithText(OutlinedButton, 'Remove').first);
    return phone;
  }),

  Scenario('invite-form', [light, dark, large], (tester, v) async {
    final phone = await openFromMore(tester, v, 'Household');
    await tapText(tester, 'Invite someone');
    return phone;
  }),

  Scenario('invite-link', [light, dark, rtl], (tester, v) async {
    final phone = await openFromMore(tester, v, 'Household');
    await inviteEdsger(tester);
    await tapInSheet(tester, 'Copy link');
    return phone;
  }),

  Scenario('invite-revoked', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Household');
    await inviteEdsger(tester);
    await tapInSheet(tester, 'Revoke link');
    return phone;
  }),

  Scenario('invite-refused', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Household');
    await tapText(tester, 'Invite someone');
    await tester.enterText(
      find.widgetWithText(TextFormField, 'Their email address'),
      'grace@example.com',
    );
    await tapInSheet(tester, 'Create link');
    return phone;
  }),

  // ---- Preferences
  Scenario('preferences', everyWay, (tester, v) {
    return openFromMore(tester, v, 'Preferences', who: 'viewer');
  }),

  Scenario('preferences-saved', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Preferences', state: 'prefs-saved', who: 'viewer');
    return phone;
  }),

  Scenario('preferences-offline', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Preferences', who: 'viewer');
    (phone.server as FixtureServer).offline = true;
    ProviderScope.containerOf(tester.element(find.byType(MaterialApp)))
        .read(reachabilityProvider.notifier)
        .record(Reachability.offline);
    await settle(tester);
    return phone;
  }),

  // ---- Admin
  Scenario('admin', everyWay, (tester, v) => openFromMore(tester, v, 'Admin')),

  Scenario('admin-signing-in', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Admin');
    await tapVisible(tester, find.widgetWithText(SwitchListTile, 'Let anyone create an account'));
    await scrollTo(tester, 'Registration');
    return phone;
  }),

  Scenario('admin-accounts', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Admin');
    await scrollTo(tester, 'Alan');
    return phone;
  }),

  Scenario('admin-provider', [light, dark, large], (tester, v) async {
    final phone = await openFromMore(tester, v, 'Admin', state: 'oidc-on');
    await scrollTo(tester, 'Issuer URL');
    return phone;
  }),

  Scenario('admin-provider-off-confirm', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Admin', state: 'oidc-on');
    await tapVisible(
      tester,
      find.widgetWithText(SwitchListTile, 'Offer sign-in through my provider'),
    );
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));
    return phone;
  }),

  Scenario('admin-conflict', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Admin', state: 'admin-stale');
    await tapVisible(
      tester,
      find.widgetWithText(SwitchListTile, 'Offer sign-in through my provider'),
    );
    final body = fixtureRequest(
      'household',
      state: 'admin-stale',
      path: '/api/admin/settings',
      then: 'admin-conflict',
    );
    await tester.enterText(
      find.widgetWithText(TextFormField, 'Client secret'),
      body['oidcClientSecret']! as String,
    );
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));
    await scrollTo(tester, 'Signing in');
    return phone;
  }),

  Scenario('admin-password-off', [light, dark, large], (tester, v) async {
    final phone = await openFromMore(tester, v, 'Admin', state: 'oidc-proven');
    await tapVisible(
      tester,
      find.widgetWithText(SwitchListTile, 'Offer sign-in with an email address and a password'),
    );
    // At large text the command is what has to be seen: on one line, scrolling, with its bar.
    await scrollTo(
      tester,
      v.textScale > 1 ? 'deploy/restore-password-login.sh' : 'Email and password',
    );
    return phone;
  }),

  Scenario('admin-locked-refused', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Admin', state: 'proof-withdrawn');
    await tapVisible(
      tester,
      find.widgetWithText(SwitchListTile, 'Offer sign-in with an email address and a password'),
    );
    await tapVisible(tester, find.widgetWithText(FilledButton, 'Save'));
    await scrollTo(tester, 'Signing in');
    return phone;
  }),

  Scenario('admin-offline-stale', bothThemes, (tester, v) async {
    final phone = await openFromMore(tester, v, 'Admin');
    await goStale(tester, phone);
    return phone;
  }),

  // ---- Who sees Admin
  Scenario('more-non-admin-owner', bothThemes, (tester, v) async {
    final phone = await signedInPhone(
      tester,
      v,
      file: 'household',
      state: 'grace-owner',
      who: 'member',
    );
    await tapText(tester, 'More');
    return phone;
  }),
];
