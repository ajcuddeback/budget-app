import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/errors/error_text.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/l10n/message_lookup.g.dart';
import 'package:flutter/widgets.dart';

/// Every string, number, date and amount a widget shows goes through here (ADR-0023).
extension L10nRead on BuildContext {
  AppLocalizations get l10n => AppLocalizations.of(this);

  /// The locale for intl: `en_US`, `de_DE`.
  String get localeName => Localizations.localeOf(this).toString();

  /// A message by its dotted web key, for keys known only at run time (a status, a role).
  String message(String key, [Map<String, String> args = const {}]) =>
      lookupMessage(l10n, key, args) ?? l10n.error_generic;

  String money(Money value) => formatMoney(value, localeName);

  String date(CalendarDate value) => value.medium(localeName);

  String errorMessage(AppError error) => errorText(l10n, error, localeName);
}
