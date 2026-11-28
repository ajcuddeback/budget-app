import 'package:intl/intl.dart';

/// ISO 4217 codes offered wherever a currency is chosen — the household's base currency and a
/// member's display currency. A currency is **chosen from a list, never typed**, as on the web: a
/// three-letter code a person has to remember is a validation error waiting to happen.
///
/// The web takes its list from `Intl.supportedValuesOf('currency')`; Dart's `intl` has no such
/// list, so it is written here. It is data, not a rule: the server re-validates every code against
/// its own currency table, so a code it refuses comes back as an ordinary validation error.
const List<String> currencyCodes = [
  'AED', 'AFN', 'ALL', 'AMD', 'ANG', 'AOA', 'ARS', 'AUD', 'AWG', 'AZN', 'BAM', 'BBD', 'BDT', //
  'BGN', 'BHD', 'BIF', 'BMD', 'BND', 'BOB', 'BRL', 'BSD', 'BTN', 'BWP', 'BYN', 'BZD', 'CAD', //
  'CDF', 'CHF', 'CLP', 'CNY', 'COP', 'CRC', 'CUP', 'CVE', 'CZK', 'DJF', 'DKK', 'DOP', 'DZD', //
  'EGP', 'ERN', 'ETB', 'EUR', 'FJD', 'FKP', 'GBP', 'GEL', 'GHS', 'GIP', 'GMD', 'GNF', 'GTQ', //
  'GYD', 'HKD', 'HNL', 'HTG', 'HUF', 'IDR', 'ILS', 'INR', 'IQD', 'IRR', 'ISK', 'JMD', 'JOD', //
  'JPY', 'KES', 'KGS', 'KHR', 'KMF', 'KPW', 'KRW', 'KWD', 'KYD', 'KZT', 'LAK', 'LBP', 'LKR', //
  'LRD', 'LSL', 'LYD', 'MAD', 'MDL', 'MGA', 'MKD', 'MMK', 'MNT', 'MOP', 'MRU', 'MUR', 'MVR', //
  'MWK', 'MXN', 'MYR', 'MZN', 'NAD', 'NGN', 'NIO', 'NOK', 'NPR', 'NZD', 'OMR', 'PAB', 'PEN', //
  'PGK', 'PHP', 'PKR', 'PLN', 'PYG', 'QAR', 'RON', 'RSD', 'RUB', 'RWF', 'SAR', 'SBD', 'SCR', //
  'SDG', 'SEK', 'SGD', 'SHP', 'SOS', 'SRD', 'SSP', 'STN', 'SVC', 'SYP', 'SZL', 'THB', 'TJS', //
  'TMT', 'TND', 'TOP', 'TRY', 'TTD', 'TWD', 'TZS', 'UAH', 'UGX', 'USD', 'UYU', 'UZS', 'VES', //
  'VND', 'VUV', 'WST', 'XAF', 'XCD', 'XOF', 'XPF', 'YER', 'ZAR', 'ZMW', //
];

/// The list with [current] kept in it, first, when it is a code this list does not have — a
/// saved value is shown, never silently replaced.
List<String> currencyChoices(String? current) {
  final code = current?.trim().toUpperCase() ?? '';
  return code.isNotEmpty && !currencyCodes.contains(code)
      ? [code, ...currencyCodes]
      : currencyCodes;
}

/// "EUR · €", or just "CHF" where intl knows no symbol other than the code itself.
String currencyLabel(String code, String locale) {
  final symbol = NumberFormat.simpleCurrency(locale: locale, name: code).currencySymbol;
  return symbol == code ? code : '$code · $symbol';
}
