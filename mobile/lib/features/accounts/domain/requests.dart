import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';

/// The closed set the server enforces (accounts.md, Type and currency).
const List<String> accountTypes = ['CHECKING', 'SAVINGS', 'CREDIT_CARD', 'CASH', 'INVESTMENT'];

/// The web's message key for a type. A type this client has never heard of is a newer server: it
/// gets a neutral word rather than a `SCREAMING_SNAKE` code.
String accountTypeKey(String type) =>
    accountTypes.contains(type) ? 'accounts.type.$type' : 'accounts.type.unknown';

/// What the add and edit sheet holds. Every value is a string, the amount included: the form is
/// the one place a number would be easiest and the one place it would be worst (ADR-0006).
///
/// [lastFour] is `''` when the box is empty, which on its own cannot tell "cleared" from "never
/// had any"; [accountPatch] resolves that against the saved account.
final class AccountForm {
  const AccountForm({
    required this.name,
    required this.type,
    required this.currency,
    required this.openingBalance,
    this.lastFour = '',
  });

  /// The form reopened on [account]: the opening balance is the server's string, unparsed.
  factory AccountForm.of(Account account) => AccountForm(
    name: account.name,
    lastFour: account.lastFour ?? '',
    type: account.type,
    currency: account.currency,
    openingBalance: account.openingBalanceWire,
  );

  final String name;
  final String lastFour;
  final String type;
  final String currency;
  final String openingBalance;
}

/// `POST /api/accounts`, exactly as the web builds it: `lastFour` **absent**, not empty, when there
/// are no digits — the server's pattern refuses `""`, and cash in a tin has no number.
Map<String, Object?> createAccountBody(AccountForm form) {
  final digits = form.lastFour.trim();
  return {
    'name': form.name.trim(),
    if (digits.isNotEmpty) 'lastFour': digits,
    'type': form.type,
    'currency': form.currency.trim().toUpperCase(),
    'openingBalance': form.openingBalance.trim(),
  };
}

/// The smallest `PATCH` that says what changed, with the version that was read.
///
/// - A currency nobody touched is never sent, so an account with history is not refused for a
///   change nobody made.
/// - `lastFour` is three-way (ADR-0028): absent keeps the digits, `null` clears them, a value sets
///   them. An empty box is a clear only when the account had digits — otherwise a rename would
///   silently erase what tells two cards at one bank apart.
/// - Amounts compare as decimals: `-400` and the server's `-400.0000` are no change.
Map<String, Object?> accountPatch(Account previous, AccountForm form) {
  final patch = <String, Object?>{'version': previous.version};
  final name = form.name.trim();
  if (name != previous.name) patch['name'] = name;
  final digits = form.lastFour.trim();
  if (digits != (previous.lastFour ?? '')) patch['lastFour'] = digits.isEmpty ? null : digits;
  if (form.type != previous.type) patch['type'] = form.type;
  final currency = form.currency.trim().toUpperCase();
  if (currency != previous.currency) patch['currency'] = currency;
  final opening = form.openingBalance.trim();
  if (!sameAmount(opening, previous.openingBalanceWire)) patch['openingBalance'] = opening;
  return patch;
}

/// Whether a patch would change anything beyond the version it carries. An untouched sheet sends
/// nothing: a `PATCH` would bump the version and make somebody else's open edit stale.
bool patchIsEmpty(Map<String, Object?> patch) => patch.length == 1;

/// The fields a conflict can be about. `balance` is derived, so it is never one of them.
enum AccountField { name, lastFour, type, currency, openingBalance, archived }

/// Which fields differ between the copy a sheet was opened with and the copy the server holds now:
/// what turns `409 stale-version` into a sentence naming what somebody else changed.
List<AccountField> differingFields(Account mine, Account theirs) => [
  if (mine.name != theirs.name) AccountField.name,
  if (mine.lastFour != theirs.lastFour) AccountField.lastFour,
  if (mine.type != theirs.type) AccountField.type,
  if (mine.currency != theirs.currency) AccountField.currency,
  if (!sameAmount(mine.openingBalanceWire, theirs.openingBalanceWire)) AccountField.openingBalance,
  if (mine.archived != theirs.archived) AccountField.archived,
];

final RegExp _signedAmount = RegExp(r'^-?[0-9]{1,15}(\.[0-9]{1,4})?$');

/// A signed decimal that fits `NUMERIC(19,4)`, the server's `AccountRequests.AMOUNT`. Zero is a
/// valid opening balance and negative is normal (a credit card you owe on). Never parsed.
bool isOpeningBalance(String typed) => _signedAmount.hasMatch(typed.trim());

final RegExp _fourDigits = RegExp(r'^[0-9]{4}$');

/// Empty, or exactly four ASCII digits — `[0-9]`, never `\d`, which can widen to Arabic-Indic
/// digits the server refuses (ADR-0028).
bool isLastFour(String typed) => typed.trim().isEmpty || _fourDigits.hasMatch(typed.trim());
