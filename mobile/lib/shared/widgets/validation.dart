import 'dart:convert';

import 'package:budget_owl/l10n/gen/app_localizations.dart';

/// The web's form rules, the same on the phone (frontend setup and join forms). The server checks
/// every one of them again; these exist so a person hears about a mistake before a round trip.
abstract final class Rules {
  static const int minimumPasswordLength = 12;

  /// BCrypt's limit, which the server refuses past (authentication-and-households.md).
  static const int maximumPasswordBytes = 72;

  static String? required(AppLocalizations l, String value) =>
      value.trim().isEmpty ? l.validation_required : null;

  static String? email(AppLocalizations l, String value) {
    final text = value.trim();
    if (text.isEmpty) return l.validation_required;
    return RegExp(r'^[^@\s]+@[^@\s]+\.[^@\s]+$').hasMatch(text) ? null : l.validation_email;
  }

  static String? password(AppLocalizations l, String value) {
    if (value.isEmpty) return l.validation_required;
    if (value.length < minimumPasswordLength) {
      return l.validation_minLength('$minimumPasswordLength');
    }
    if (utf8.encode(value).length > maximumPasswordBytes) {
      return l.validation_maxBytes('$maximumPasswordBytes');
    }
    return null;
  }

  static String? currency(AppLocalizations l, String value) =>
      RegExp(r'^[A-Za-z]{3}$').hasMatch(value.trim()) ? null : l.validation_currency;
}
