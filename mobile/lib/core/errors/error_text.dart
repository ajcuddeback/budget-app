import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/l10n/message_lookup.g.dart';
import 'package:intl/intl.dart';

/// The sentence a person is shown for [error], in their language.
///
/// The web's resolution order (frontend/src/app/core/i18n/error-text.ts): a code-and-reason key
/// (`error.transfer-pair-mismatch.same-sign`), then the code, then the generic sentence. An
/// unknown code — a newer server than this app — gets the generic sentence, **never the raw code
/// and never the server's `title` or `detail`**, which this app does not even keep.
String errorText(AppLocalizations l, AppError error, String locale) {
  switch (error.kind) {
    case AppErrorKind.offline:
    case AppErrorKind.noAnswer:
      return l.error_network;
    case AppErrorKind.unexpected:
      return l.error_generic;
    case AppErrorKind.http:
      break;
  }
  final code = error.code;
  if (code == null) return l.error_generic;
  final numbers = NumberFormat.decimalPattern(locale);
  String number(String name) {
    final value = error.params[name];
    return value is int ? numbers.format(value) : '';
  }

  final args = <String, String>{
    'minimumLength': number('minimumLength'),
    'maximumBytes': number('maximumBytes'),
    'maximumMonths': number('maximumMonths'),
    'minutes': durationText(l, error.retryAfterSeconds ?? 0, locale),
  };
  final reason = error.reason;
  return (reason == null ? null : lookupMessage(l, 'error.$code.$reason', args)) ??
      lookupMessage(l, 'error.$code', args) ??
      l.error_generic;
}

/// "45 seconds", "1 minute", "3 hours" — how long a rate limit asks a person to wait, rounded up so
/// trying again at the stated time never meets the limit a second time.
String durationText(AppLocalizations l, int seconds, String locale) {
  final numbers = NumberFormat.decimalPattern(locale);
  if (seconds < 60) return l.duration_seconds(numbers.format(seconds < 1 ? 1 : seconds));
  final minutes = (seconds + 59) ~/ 60;
  if (minutes == 1) return l.duration_minute;
  if (minutes < 60) return l.duration_minutes(numbers.format(minutes));
  final hours = (minutes + 59) ~/ 60;
  return hours == 1 ? l.duration_hour : l.duration_hours(numbers.format(hours));
}
