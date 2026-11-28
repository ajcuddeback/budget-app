import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/household/data/household_repository.dart';
import 'package:budget_owl/features/household/domain/currencies.dart';
import 'package:budget_owl/features/household/presentation/section_card.dart';
import 'package:budget_owl/features/preferences/data/theme_choice.dart';
import 'package:budget_owl/features/preferences/domain/locales.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// The member's own preferences (authentication-and-households.md, `/preferences`).
///
/// - **Display currency and language** are the member's, saved with `PATCH …/members/me`, so they
///   follow the member to the web. They change what *this member* sees, never what is recorded.
///   "Follow the household" and "Follow this device" are sent as `null`. A `VIEWER` may save them:
///   they are self-scoped.
/// - **The theme** is the web's light/dark override, kept on this phone only and applied at once.
///   It needs no connection and is never sent anywhere.
class PreferencesScreen extends ConsumerStatefulWidget {
  const PreferencesScreen({super.key});

  @override
  ConsumerState<PreferencesScreen> createState() => _PreferencesScreenState();
}

class _PreferencesScreenState extends ConsumerState<PreferencesScreen> {
  String _currency = '';
  String _locale = '';
  int _formRevision = 0;
  bool _saving = false;
  bool _saved = false;
  AppError? _error;

  @override
  void initState() {
    super.initState();
    // Filled from the member as the server last said, and again after a save re-reads it.
    (String, String) saved(SessionSignedIn? s) =>
        (s?.membership?.displayCurrency ?? '', s?.membership?.locale ?? '');
    final (currency, locale) = saved(ref.read(signedInProvider));
    _currency = currency;
    _locale = locale;
    ref.listenManual(
      signedInProvider.select(saved),
      (_, next) => setState(() {
        _currency = next.$1;
        _locale = next.$2;
        _formRevision++;
      }),
    );
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _saved = false;
      _error = null;
    });
    try {
      await ref
          .read(householdWriterProvider)
          .savePreferences(displayCurrency: _currency, locale: _locale);
      if (mounted) setState(() => _saved = true);
    } on AppError catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final membership = ref.watch(signedInProvider)?.membership;
    final online = canWriteNow(ref);
    final theme = ref.watch(themeChoiceProvider);
    final error = _error;
    const gap = SizedBox(height: SpaceTokens.s3);
    return Screen(
      title: l.preferences_title,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.preferences_lede),
          if (membership != null)
            SectionCard(
              title: l.preferences_heading,
              children: [
                if (error != null) ...[
                  NoticeBanner(text: writeErrorText(context, ref, error)),
                  gap,
                ],
                if (_saved) ...[NoticeBanner(text: l.preferences_saved, success: true), gap],
                ChoiceField(
                  key: ValueKey('currency-$_formRevision'),
                  value: _currency,
                  label: l.preferences_currency,
                  hint: l.preferences_currencyHint,
                  choices: [
                    (
                      value: '',
                      label: l.preferences_followHousehold(membership.baseCurrency),
                      ltr: false,
                    ),
                    for (final code in currencyChoices(_currency))
                      (value: code, label: currencyLabel(code, context.localeName), ltr: true),
                  ],
                  onChanged: (value) => setState(() => _currency = value ?? ''),
                ),
                gap,
                ChoiceField(
                  key: ValueKey('locale-$_formRevision'),
                  value: _locale,
                  label: l.preferences_locale,
                  hint: l.preferences_localeHintPhone,
                  choices: [
                    (value: '', label: l.preferences_followDevice, ltr: false),
                    for (final tag in localeChoices(_locale))
                      (value: tag, label: localeLabel(tag), ltr: false),
                  ],
                  onChanged: (value) => setState(() => _locale = value ?? ''),
                ),
                gap,
                Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: FilledButton(
                    onPressed: _saving || !online ? null : _save,
                    child: Text(_saving ? l.common_saving : l.common_save),
                  ),
                ),
                const OfflineNote(),
              ],
            ),
          SectionCard(
            title: l.preferences_themeHeading,
            children: [
              Semantics(
                label: l.preferences_theme,
                child: SegmentedButton<ThemeChoice>(
                  segments: [
                    ButtonSegment(
                      value: ThemeChoice.system,
                      label: Text(l.preferences_themeSystem),
                    ),
                    ButtonSegment(value: ThemeChoice.light, label: Text(l.preferences_themeLight)),
                    ButtonSegment(value: ThemeChoice.dark, label: Text(l.preferences_themeDark)),
                  ],
                  selected: {theme},
                  showSelectedIcon: false,
                  onSelectionChanged: (chosen) =>
                      ref.read(themeChoiceProvider.notifier).choose(chosen.first),
                ),
              ),
              Hint(l.preferences_themeHint),
            ],
          ),
        ],
      ),
    );
  }
}
