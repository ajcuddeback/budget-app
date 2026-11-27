import 'dart:collection';

import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/accounts/domain/requests.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// One account, as `AccountResponse` sends it. Balances are the server's sum — this client never
/// adds a transaction to a balance itself (mobile-app.md, Writes).
final class Account {
  const Account({
    required this.id,
    required this.name,
    required this.type,
    required this.currency,
    required this.balance,
    required this.archived,
    required this.version,
    this.lastFour,
    this.openingBalanceWire = '0',
    this.knownToHaveHistory = false,
  });

  factory Account.fromJson(JsonMap json) {
    final currency = json.string('currency');
    final opening = json.string('openingBalance');
    // Checked as an amount here, so a malformed one is the generic error, not a broken form.
    wireMoney(opening, currency, 'openingBalance');
    return Account(
      id: json.string('id'),
      name: json.string('name'),
      lastFour: json.stringOrNull('lastFour'),
      type: json.string('type'),
      currency: currency,
      balance: wireMoney(json.string('balance'), currency, 'balance'),
      archived: json.boolean('archived'),
      version: json.integer('version'),
      openingBalanceWire: opening,
      // Only the server's own `false` is history. Absent is "not known" (an older server), which
      // offers Delete and leaves the refusal to the server (accounts.md, What was built, 1).
      knownToHaveHistory: !json.booleanOr('empty', fallback: true),
    );
  }

  final String id;
  final String name;
  final String? lastFour;
  final String type;
  final String currency;
  final Money balance;
  final bool archived;
  final int version;

  /// What the account held when tracking started, as the server sent it (`-400.0000`). Kept as
  /// the wire string, because the edit form is reopened with it and compares against it.
  final String openingBalanceWire;

  /// The server said this account has transactions: no Delete, and its currency is locked.
  /// **Never** derived from `balance` and `openingBalance` — an account whose history nets to zero
  /// still has history, and deleting it would destroy records.
  final bool knownToHaveHistory;

  Money get openingBalance => Money.fromWire(openingBalanceWire, currency);

  /// "Travel Card •8820", as the artboards name a card — display sugar, never an identifier.
  String get label => lastFour == null ? name : '$name •$lastFour';
}

/// A page of accounts: the rows, and how many the server holds in all, so a screen can say how
/// many it is not showing rather than quietly showing part of a list of money.
final class AccountList extends UnmodifiableListView<Account> {
  AccountList(super.source, {required this.totalElements});

  final int totalElements;
}

/// `GET /api/accounts`, archived included: the archived ones still name old rows, and are left out
/// of every picker for a new entry (`409 account-archived` otherwise).
final class AccountsResource extends CachedResource<AccountList> {
  @override
  String get path => '/api/accounts';

  @override
  Map<String, String> get query => const {'includeArchived': 'true', 'page': '0', 'size': '200'};

  @override
  AccountList decode(JsonMap json) => AccountList([
    for (final account in json.objects('content')) Account.fromJson(account),
  ], totalElements: json.integer('totalElements'));
}

final accountsProvider = NotifierProvider.autoDispose<AccountsResource, Snapshot<AccountList>>(
  AccountsResource.new,
);

/// Every write the accounts screen makes, each sent only when the last request reached the
/// server — `TransactionsWriter`'s pattern.
///
/// **Nothing is queued** (mobile-app.md decision 3): offline, a write throws [AppError.offline]
/// without being sent. After a success the data revision is bumped, so every screen showing a
/// balance asks the server again. No household id anywhere: the server resolves it (ADR-0017).
final class AccountsWriter {
  AccountsWriter(this._ref);

  final Ref _ref;

  ApiClient get _api {
    final api = _ref.read(apiClientProvider);
    if (api == null) throw const AppError.unexpected();
    return api;
  }

  Future<JsonMap?> _write(String method, String path, [Object? body]) async {
    if (!_ref.read(reachabilityProvider).canWrite) throw const AppError.offline();
    final result = await _api.write(method, path, body: body);
    _ref.read(dataRevisionProvider.notifier).bump();
    return result;
  }

  static String _one(String id) => '/api/accounts/${Uri.encodeComponent(id)}';

  Future<void> create(AccountForm form) => _write('POST', '/api/accounts', createAccountBody(form));

  /// Sends the patch, or nothing when nothing changed. Returns whether anything was sent.
  Future<bool> update(Account original, AccountForm form) async {
    final patch = accountPatch(original, form);
    if (patchIsEmpty(patch)) return false;
    await _write('PATCH', _one(original.id), patch);
    return true;
  }

  /// The server's copy now, read after a `409 stale-version` so the sheet can say what changed.
  Future<Account> latest(String id) async => Account.fromJson(await _api.getJson(_one(id)));

  /// Sub-resources, not a field on `PATCH`: retiring an account is a different act.
  Future<void> archive(Account account) => _write('POST', '${_one(account.id)}/archive');

  Future<void> unarchive(Account account) => _write('POST', '${_one(account.id)}/unarchive');

  /// Offered only while the account is empty; otherwise `409 account-not-empty`.
  Future<void> delete(Account account) => _write('DELETE', _one(account.id));
}

final accountsWriterProvider = Provider<AccountsWriter>(AccountsWriter.new);
