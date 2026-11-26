import 'package:budget_owl/features/devices/presentation/devices_screen.dart';
import 'package:budget_owl/features/transactions/presentation/transactions_screen.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:material_ui/material_ui.dart';

/// One place a member can go: a bottom-navigation tab, or an entry under More.
///
/// **Adding a screen is one edit here**: replace the entry's `screen` placeholder with the
/// feature's screen (mobile/README.md, "Adding a feature"). Entries are kept a blank line apart so
/// two features landing in parallel touch different hunks.
final class Destination {
  const Destination({
    required this.path,
    required this.label,
    required this.icon,
    required this.screen,
    this.adminOnly = false,
  });

  final String path;
  final String Function(AppLocalizations l) label;
  final IconData icon;
  final Widget Function(BuildContext context) screen;

  /// Shown only to an instance administrator. The admin API refuses anyone else regardless.
  final bool adminOnly;
}

/// The bottom navigation: the five most-used destinations (mobile-app.md, Screens).
final List<Destination> tabs = [
  Destination(
    path: '/transactions',
    label: (l) => l.nav_transactions,
    icon: Icons.receipt_long_outlined,
    screen: (_) => const TransactionsScreen(),
  ),

  Destination(
    path: '/check-in',
    label: (l) => l.nav_checkIn,
    icon: Icons.task_alt_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_checkIn),
  ),

  Destination(
    path: '/bills',
    label: (l) => l.nav_bills,
    icon: Icons.event_repeat_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_bills),
  ),

  Destination(
    path: '/accounts',
    label: (l) => l.nav_accounts,
    icon: Icons.account_balance_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_accounts),
  ),
];

/// Everything else, under More, in the web's order.
final List<Destination> moreEntries = [
  Destination(
    path: '/more/debt-plan',
    label: (l) => l.nav_debtPlan,
    icon: Icons.trending_down_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_debtPlan),
  ),

  Destination(
    path: '/more/goals',
    label: (l) => l.nav_goals,
    icon: Icons.flag_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_goals),
  ),

  Destination(
    path: '/more/envelopes',
    label: (l) => l.nav_envelopes,
    icon: Icons.mail_outline,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_envelopes),
  ),

  Destination(
    path: '/more/categories',
    label: (l) => l.nav_categories,
    icon: Icons.category_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_categories),
  ),

  Destination(
    path: '/more/insights',
    label: (l) => l.nav_insights,
    icon: Icons.insights_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_insights),
  ),

  Destination(
    path: '/more/import',
    label: (l) => l.nav_import,
    icon: Icons.file_upload_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_import),
  ),

  Destination(
    path: '/more/household',
    label: (l) => l.nav_household,
    icon: Icons.home_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_household),
  ),

  Destination(
    path: '/more/devices',
    label: (l) => l.nav_devices,
    icon: Icons.devices_outlined,
    screen: (_) => const DevicesScreen(),
  ),

  Destination(
    path: '/more/preferences',
    label: (l) => l.nav_preferences,
    icon: Icons.tune_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_preferences),
  ),

  Destination(
    path: '/more/admin',
    label: (l) => l.nav_admin,
    icon: Icons.admin_panel_settings_outlined,
    screen: (context) => PlaceholderScreen(title: AppLocalizations.of(context).title_admin),
    adminOnly: true,
  ),
];
