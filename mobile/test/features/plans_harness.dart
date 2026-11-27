import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/errors/error_text.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';

/// Shared steps for the Bills & income, debt plan and goals widget tests: the whole app against
/// the recorded exchanges of one fixture file, signed in as , opened on a screen.
final class Plans {
  Plans._(this.server, this.phone, this.tester);

  final FixtureServer server;
  final TestPhone phone;
  final WidgetTester tester;

  static Future<Plans> open(
    WidgetTester tester, {
    required String file,
    required Future<void> Function(Plans p) on,
    String state = 'plans',
    String who = 'owner',
  }) async {
    final server = FixtureServer.load(file, state: state);
    final phone = TestPhone(server: server)..signedIn(who);
    await pumpApp(tester, phone);
    final p = Plans._(server, phone, tester);
    await on(p);
    return p;
  }

  ProviderContainer get container =>
      ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));

  /// Taps the last [text] on screen, scrolling a lazily built list until there is one.
  Future<void> tap(String text) async {
    final any = find.text(text);
    if (any.evaluate().isEmpty) {
      // The front-most vertical list: a text field's own horizontal scrollable is not the one.
      await tester.scrollUntilVisible(
        any,
        200,
        scrollable: find
            .byWidgetPredicate((w) => w is Scrollable && w.axisDirection == AxisDirection.down)
            .last,
        maxScrolls: 30,
      );
    }
    await tapVisible(tester, any.last);
  }

  /// Taps the button labelled [label] — not a heading or a card title with the same words.
  Future<void> press(String label) => tapVisible(
    tester,
    find
        .ancestor(
          of: find.text(label),
          matching: find.byWidgetPredicate((w) => w is ButtonStyleButton),
        )
        .first,
  );

  Future<void> tapTooltip(String tooltip) => tapVisible(tester, find.byTooltip(tooltip).last);

  /// Every write the server answered, as `METHOD /path`.
  List<String> get writes => [
    for (final s in server.served)
      if (!s.startsWith('GET ')) s,
  ];

  /// The phone loses its network: the next request fails, and the app has seen it fail.
  Future<void> goOffline() async {
    server.offline = true;
    container.read(reachabilityProvider.notifier).record(Reachability.offline);
    await settle(tester);
  }

  /// The phone has seen a request fail, though the server would answer the next one: only a
  /// writer's own check stands between a tap and the request.
  Future<void> believeOffline() async {
    container.read(reachabilityProvider.notifier).record(Reachability.offline);
    await settle(tester);
  }

  /// Back to a phone that reached a server whose version is confirmed.
  Future<void> reachable() async {
    container.read(reachabilityProvider.notifier)
      ..record(Reachability.reached)
      ..confirmVersion();
    await settle(tester);
  }

  /// The server answers, but its version is not confirmed this launch: writes are off for that
  /// reason, and the screen must say so rather than "offline".
  Future<void> unconfirm() async {
    container.read(reachabilityProvider.notifier).unconfirmVersion();
    await settle(tester);
  }

  /// Runs a write past the UI, as a VIEWER's tampered build would, and returns what it threw.
  Future<AppError?> forced(Future<void> Function(ProviderContainer c) write) =>
      tester.runAsync<AppError?>(() async {
        try {
          await write(container);
          return null;
        } on AppError catch (e) {
          return e;
        }
      });
}

/// The API's translated 403, as the phone says it.
String readOnlyText(AppError error) => errorText(AppLocalizationsEn(), error, 'en_US');

const offlineSentence = "You're offline — this needs a connection";

const unconfirmedSentence =
    'Changes are off until the server confirms it runs a version this app works with.';
