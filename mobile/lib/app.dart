import 'package:budget_owl/core/privacy/privacy_cover.dart';
import 'package:budget_owl/core/routing/router.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/features/preferences/data/theme_choice.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Light, dark or the system's choice: the system by default, or the web's same override as
/// Preferences keeps it on this phone (features/preferences/data/theme_choice.dart).
final themeModeProvider = Provider<ThemeMode>((ref) => ref.watch(themeChoiceProvider).mode);

/// Null follows the language, as it should. Set only by the right-to-left golden tests, since
/// English is the only language that ships and every layout must still work mirrored.
final textDirectionOverrideProvider = Provider<TextDirection?>((ref) => null);

/// No automatic retries, anywhere. Riverpod 3 retries a failing provider up to ten times by
/// default; for an app whose every provider is an API call, that is the retry storm on a `401`
/// the style guide forbids. A failed read stays failed until the person, or a write, asks again.
Duration? noRetry(int retryCount, Object error) => null;

class BudgetOwlApp extends ConsumerWidget {
  const BudgetOwlApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return MaterialApp.router(
      onGenerateTitle: (context) => AppLocalizations.of(context).app_name,
      debugShowCheckedModeBanner: false,
      routerConfig: ref.watch(routerProvider),
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      themeMode: ref.watch(themeModeProvider),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      builder: (context, child) => Directionality(
        textDirection: ref.watch(textDirectionOverrideProvider) ?? Directionality.of(context),
        child: PrivacyCover(child: child ?? const SizedBox.shrink()),
      ),
    );
  }
}
