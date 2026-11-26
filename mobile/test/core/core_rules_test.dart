import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/errors/error_text.dart';
import 'package:budget_owl/core/privacy/privacy_cover.dart';
import 'package:budget_owl/core/routing/router.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:budget_owl/core/session/current_user.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

void main() {
  final AppLocalizations l = AppLocalizationsEn();

  group("errorText: the sentence is ARB text for the code, never the server's words", () {
    AppError problem(Map<String, Object?> body, {int status = 409, String? retry}) =>
        AppError.fromProblem(status, body, retryAfterHeader: retry);

    test('a code', () {
      expect(
        errorText(l, problem({'code': 'stale-version', 'title': 'Server words'}), 'en_US'),
        l.error_stale_version,
      );
    });

    test('a code and its reason', () {
      final error = problem({
        'code': 'transfer-pair-mismatch',
        'params': {'reason': 'same-sign'},
      }, status: 422);
      expect(errorText(l, error, 'en_US'), l.error_transfer_pair_mismatch_same_sign);
    });

    test('a reason this client does not know falls back to the code', () {
      final error = problem({
        'code': 'transfer-pair-mismatch',
        'params': {'reason': 'new-reason'},
      }, status: 422);
      expect(errorText(l, error, 'en_US'), l.error_transfer_pair_mismatch);
    });

    test('a code this client does not know is the generic sentence, not the code', () {
      final text = errorText(
        l,
        problem({'code': 'from-a-newer-server', 'detail': 'Server prose'}),
        'en_US',
      );
      expect(text, l.error_generic);
      expect(text, isNot(contains('from-a-newer-server')));
      expect(text, isNot(contains('Server prose')));
    });

    test('a rate limit says how long to wait, rounded up', () {
      final error = problem({'code': 'rate-limited'}, status: 429, retry: '61');
      expect(errorText(l, error, 'en_US'), l.error_rate_limited('2 minutes'));
    });

    test('a password refusal carries its number', () {
      final error = problem({
        'code': 'password-unacceptable',
        'params': {'reason': 'too-short', 'minimumLength': 12},
      }, status: 400);
      expect(errorText(l, error, 'en_US'), contains('12'));
    });

    test('no code, offline, or unreadable each have their own sentence', () {
      expect(errorText(l, problem({}), 'en_US'), l.error_generic);
      expect(errorText(l, const AppError.offline(), 'en_US'), l.error_network);
      expect(errorText(l, const AppError.noAnswer(), 'en_US'), l.error_network);
      expect(errorText(l, const AppError.unexpected(), 'en_US'), l.error_generic);
    });

    test('durations', () {
      expect(durationText(l, 0, 'en_US'), l.duration_seconds('1'));
      expect(durationText(l, 45, 'en_US'), l.duration_seconds('45'));
      expect(durationText(l, 60, 'en_US'), l.duration_minute);
      expect(durationText(l, 3540, 'en_US'), l.duration_minutes('59'));
      expect(durationText(l, 3600, 'en_US'), l.duration_hour);
      expect(durationText(l, 3601, 'en_US'), l.duration_hours('2'));
      expect(durationText(l, 7300, 'en_US'), l.duration_hours('3'));
    });

    test('field names come from fields, field and errors', () {
      final error = problem({
        'code': 'validation-failed',
        'params': {
          'fields': ['amount'],
          'field': 'date',
        },
        'errors': [
          {'field': 'note'},
        ],
      }, status: 400);
      expect(error.fields, ['amount', 'date', 'note']);
      expect(error.isConnectivity, isFalse);
      expect(const AppError.offline().isConnectivity, isTrue);
    });
  });

  group('redirectFor: a signed-out phone cannot reach a household screen', () {
    final server = (ServerAddress.parse(
      'owl.example.test',
      allowLocalCleartext: false,
    ) as AddressAccepted).address;
    const member = CurrentUser(
      id: 'u',
      email: 'a@example.com',
      displayName: 'A',
      instanceAdmin: false,
      membership: Membership(
        householdId: 'h',
        householdName: 'H',
        baseCurrency: 'USD',
        role: Role.member,
        membershipId: 'm',
      ),
    );
    const homeless = CurrentUser(
      id: 'u',
      email: 'a@example.com',
      displayName: 'A',
      instanceAdmin: false,
    );

    test('while starting, only the starting screen', () {
      expect(redirectFor(const SessionStarting(), '/transactions'), Routes.starting);
      expect(redirectFor(const SessionStarting(), Routes.starting), isNull);
    });

    test('with no server, only the connect screen', () {
      expect(redirectFor(const SessionNoServer(), '/more/devices'), Routes.connect);
      expect(redirectFor(const SessionNoServer(), Routes.connect), isNull);
    });

    test('signed out, only connect, sign-in, setup and join', () {
      final out = SessionSignedOut(server);
      expect(redirectFor(out, '/transactions'), Routes.connect);
      expect(redirectFor(out, '/more/admin'), Routes.connect);
      for (final allowed in Routes.signedOut) {
        expect(redirectFor(out, allowed), isNull);
      }
      expect(
        redirectFor(SessionSignedOut(server, notice: SignInNotice.expired), '/transactions'),
        Routes.signIn,
      );
    });

    test('a version mismatch keeps the phone on the connect screen', () {
      final state = SessionUnsupported(
        server,
        const ConnectFailure(ConnectProblem.versionMismatch, serverApiVersion: 2),
      );
      expect(redirectFor(state, '/transactions'), Routes.connect);
    });

    test('signed in, the household — and not the signed-out screens', () {
      final inside = SessionSignedIn(server, member, 1);
      expect(redirectFor(inside, Routes.signIn), Routes.transactions);
      expect(redirectFor(inside, Routes.starting), Routes.transactions);
      expect(redirectFor(inside, '/transactions'), isNull);
      expect(redirectFor(inside, '/more/devices'), isNull);
    });

    test('the admin screen only for an instance administrator', () {
      expect(redirectFor(SessionSignedIn(server, member, 1), '/more/admin'), Routes.more);
    });

    test('a member of no household sees only that screen', () {
      final none = SessionSignedIn(server, homeless, 1);
      expect(redirectFor(none, '/transactions'), Routes.noHousehold);
      expect(redirectFor(none, Routes.noHousehold), isNull);
    });
  });

  group('roles decide what is offered, never what is allowed', () {
    test('owner and member are offered writes, a viewer is not', () {
      expect(Role.parse('OWNER').canWrite, isTrue);
      expect(Role.parse('MEMBER').canWrite, isTrue);
      expect(Role.parse('VIEWER').canWrite, isFalse);
      expect(Role.viewer.wire, 'VIEWER');
    });

    test('an unknown role is a server this client cannot read', () {
      expect(() => Role.parse('ADMIN'), throwsA(anything));
    });
  });

  test('no provider retries on its own (Riverpod 3 would, ten times)', () async {
    var calls = 0;
    final failing = FutureProvider<int>((ref) async {
      calls++;
      throw const AppError.noAnswer();
    });
    final container = ProviderContainer(retry: noRetry);
    addTearDown(container.dispose);
    container.listen(failing, (_, _) {});

    await Future<void>.delayed(const Duration(seconds: 1));

    expect(calls, 1);
  });

  testWidgets('the privacy cover hides the app from the task switcher', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildTheme(Brightness.light),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const PrivacyCover(child: Text(r'$3,603.40')),
      ),
    );
    expect(find.text(r'$3,603.40'), findsOneWidget);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    await tester.pump();
    expect(find.text('Budget Owl'), findsOneWidget);
    expect(
      tester.getSemantics(find.byType(PrivacyCover)),
      isNot(matchesSemantics(label: r'$3,603.40')),
    );

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(find.text('Budget Owl'), findsNothing);
  });

  test('the theme carries the design tokens in both brightnesses', () {
    final light = buildTheme(Brightness.light);
    final dark = buildTheme(Brightness.dark);
    expect(light.extension<Palette>(), Palette.light);
    expect(dark.extension<Palette>(), Palette.dark);
    expect(light.scaffoldBackgroundColor, isNot(dark.scaffoldBackgroundColor));
    expect(Palette.light.lerp(Palette.dark, 0.7), Palette.dark);
    expect(Palette.light.copyWith(), Palette.light);
    expect(SystemChrome.latestStyle, anything);
    expect(textDirectionOverrideProvider, isNotNull);
  });
}
