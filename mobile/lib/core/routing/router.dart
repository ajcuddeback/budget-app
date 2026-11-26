import 'package:budget_owl/core/routing/destinations.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/auth/presentation/no_household_screen.dart';
import 'package:budget_owl/features/auth/presentation/sign_in_screen.dart';
import 'package:budget_owl/features/connect/presentation/connect_screen.dart';
import 'package:budget_owl/features/connect/presentation/join_screen.dart';
import 'package:budget_owl/features/connect/presentation/setup_screen.dart';
import 'package:budget_owl/features/connect/presentation/starting_screen.dart';
import 'package:budget_owl/features/debt/presentation/debt_detail_screen.dart';
import 'package:budget_owl/features/goals/presentation/goal_detail_screen.dart';
import 'package:budget_owl/features/more/presentation/more_screen.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

/// Route paths the app links to by name.
abstract final class Routes {
  static const String starting = '/starting';
  static const String connect = '/connect';
  static const String signIn = '/sign-in';
  static const String setup = '/setup';
  static const String join = '/join';
  static const String noHousehold = '/no-household';
  static const String transactions = '/transactions';
  static const String more = '/more';

  /// Reachable without signing in, once a server is chosen.
  static const Set<String> signedOut = {connect, signIn, setup, join};
}

/// Where the session says the member must be, or null to stay. Pure, so it has a unit test of its
/// own: this function is the whole of "a signed-out phone cannot reach a household's screens".
String? redirectFor(SessionState session, String location) {
  bool at(String path) => location == path || location.startsWith('$path/');
  switch (session) {
    case SessionStarting():
      return at(Routes.starting) ? null : Routes.starting;
    case SessionNoServer():
      return at(Routes.connect) ? null : Routes.connect;
    case SessionUnsupported():
      return at(Routes.connect) ? null : Routes.connect;
    case SessionSignedOut(:final notice):
      if (Routes.signedOut.any(at)) return null;
      // A session that just ended, or an account just made, goes straight to sign-in with its
      // note; otherwise the connect screen's choices — sign in, join, or set up.
      return notice == null ? Routes.connect : Routes.signIn;
    case SessionSignedIn(:final user):
      if (user.membership == null) {
        return at(Routes.noHousehold) ? null : Routes.noHousehold;
      }
      if (at(Routes.starting) || at(Routes.noHousehold) || Routes.signedOut.any(at)) {
        return Routes.transactions;
      }
      if (at('/more/admin') && !user.instanceAdmin) return Routes.more;
      return null;
  }
}

/// Detail screens, each a child of its More entry so the entry is beneath it and back returns to
/// it. The id in the path is the server's id, never a slug: a slug would put a name in history.
final Map<String, List<RouteBase>> _detailRoutes = {
  '/more/debt-plan': [
    GoRoute(
      path: ':id',
      // Keyed by the id: stepping to a sibling debt is a new page, never the last one's state.
      builder: (_, state) {
        final id = state.pathParameters['id']!;
        return DebtDetailScreen(key: ValueKey(id), id: id);
      },
    ),
  ],
  '/more/goals': [
    GoRoute(
      path: ':id',
      builder: (_, state) {
        final id = state.pathParameters['id']!;
        return GoalDetailScreen(key: ValueKey(id), id: id);
      },
    ),
  ],
};

/// Re-runs [redirectFor] whenever the session changes.
final class _SessionListenable extends ChangeNotifier {
  _SessionListenable(Ref ref) {
    ref.listen(sessionProvider, (_, _) => notifyListeners());
  }
}

final routerProvider = Provider<GoRouter>((ref) {
  final listenable = _SessionListenable(ref);
  final router = GoRouter(
    initialLocation: Routes.starting,
    refreshListenable: listenable,
    redirect: (context, state) => redirectFor(ref.read(sessionProvider), state.matchedLocation),
    routes: [
      GoRoute(path: Routes.starting, builder: (_, _) => const StartingScreen()),
      GoRoute(path: Routes.connect, builder: (_, _) => const ConnectScreen()),
      GoRoute(path: Routes.signIn, builder: (_, _) => const SignInScreen()),
      GoRoute(path: Routes.setup, builder: (_, _) => const SetupScreen()),
      GoRoute(path: Routes.join, builder: (_, _) => const JoinScreen()),
      GoRoute(path: Routes.noHousehold, builder: (_, _) => const NoHouseholdScreen()),
      StatefulShellRoute.indexedStack(
        builder: (context, state, shell) => _TabShell(shell: shell),
        branches: [
          for (final tab in tabs)
            StatefulShellBranch(
              routes: [GoRoute(path: tab.path, builder: (context, _) => tab.screen(context))],
            ),
          StatefulShellBranch(
            routes: [
              GoRoute(
                path: Routes.more,
                builder: (_, _) => const MoreScreen(),
                routes: [
                  for (final entry in moreEntries)
                    GoRoute(
                      path: entry.path.substring('${Routes.more}/'.length),
                      builder: (context, _) => entry.screen(context),
                      routes: _detailRoutes[entry.path] ?? const [],
                    ),
                ],
              ),
            ],
          ),
        ],
      ),
    ],
  );
  ref.onDispose(() {
    router.dispose();
    listenable.dispose();
  });
  return router;
});

/// The bottom navigation: Transactions, Check-in, Bills, Accounts, More.
class _TabShell extends StatelessWidget {
  const _TabShell({required this.shell});

  final StatefulNavigationShell shell;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Scaffold(
      body: shell,
      bottomNavigationBar: NavigationBar(
        selectedIndex: shell.currentIndex,
        onDestinationSelected: (index) =>
            shell.goBranch(index, initialLocation: index == shell.currentIndex),
        destinations: [
          for (final tab in tabs) NavigationDestination(icon: Icon(tab.icon), label: tab.label(l)),
          NavigationDestination(icon: const Icon(Icons.more_horiz), label: l.nav_more),
        ],
      ),
    );
  }
}
