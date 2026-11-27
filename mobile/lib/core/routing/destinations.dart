import 'package:budget_owl/features/accounts/presentation/accounts_screen.dart';
import 'package:budget_owl/features/admin/presentation/admin_screen.dart';
import 'package:budget_owl/features/bills/presentation/bills_screen.dart';
import 'package:budget_owl/features/categories/presentation/categories_screen.dart';
import 'package:budget_owl/features/check_in/presentation/check_in_screen.dart';
import 'package:budget_owl/features/debt/presentation/debt_plan_screen.dart';
import 'package:budget_owl/features/devices/presentation/devices_screen.dart';
import 'package:budget_owl/features/envelopes/presentation/envelopes_screen.dart';
import 'package:budget_owl/features/goals/presentation/goals_screen.dart';
import 'package:budget_owl/features/household/presentation/household_screen.dart';
import 'package:budget_owl/features/import/presentation/import_screen.dart';
import 'package:budget_owl/features/insights/presentation/insights_screen.dart';
import 'package:budget_owl/features/preferences/presentation/preferences_screen.dart';
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
    screen: (_) => const CheckInScreen(),
  ),

  Destination(
    path: '/bills',
    label: (l) => l.nav_bills,
    icon: Icons.event_repeat_outlined,
    screen: (_) => const BillsScreen(),
  ),

  Destination(
    path: '/accounts',
    label: (l) => l.nav_accounts,
    icon: Icons.account_balance_outlined,
    screen: (_) => const AccountsScreen(),
  ),
];

/// Everything else, under More, in the web's order.
final List<Destination> moreEntries = [
  Destination(
    path: '/more/debt-plan',
    label: (l) => l.nav_debtPlan,
    icon: Icons.trending_down_outlined,
    screen: (_) => const DebtPlanScreen(),
  ),

  Destination(
    path: '/more/goals',
    label: (l) => l.nav_goals,
    icon: Icons.flag_outlined,
    screen: (_) => const GoalsScreen(),
  ),

  Destination(
    path: '/more/envelopes',
    label: (l) => l.nav_envelopes,
    icon: Icons.mail_outline,
    screen: (_) => const EnvelopesScreen(),
  ),

  Destination(
    path: '/more/categories',
    label: (l) => l.nav_categories,
    icon: Icons.category_outlined,
    screen: (_) => const CategoriesScreen(),
  ),

  Destination(
    path: '/more/insights',
    label: (l) => l.nav_insights,
    icon: Icons.insights_outlined,
    screen: (_) => const InsightsScreen(),
  ),

  Destination(
    path: '/more/import',
    label: (l) => l.nav_import,
    icon: Icons.file_upload_outlined,
    screen: (_) => const ImportScreen(),
  ),

  Destination(
    path: '/more/household',
    label: (l) => l.nav_household,
    icon: Icons.home_outlined,
    screen: (_) => const HouseholdScreen(),
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
    screen: (_) => const PreferencesScreen(),
  ),

  Destination(
    path: '/more/admin',
    label: (l) => l.nav_admin,
    icon: Icons.admin_panel_settings_outlined,
    screen: (_) => const AdminScreen(),
    adminOnly: true,
  ),
];
