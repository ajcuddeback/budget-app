import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';
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
  });

  factory Account.fromJson(JsonMap json) {
    final currency = json.string('currency');
    return Account(
      id: json.string('id'),
      name: json.string('name'),
      lastFour: json.stringOrNull('lastFour'),
      type: json.string('type'),
      currency: currency,
      balance: Money.fromWire(json.string('balance'), currency),
      archived: json.boolean('archived'),
      version: json.integer('version'),
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

  /// "Travel Card •8820", as the artboards name a card — display sugar, never an identifier.
  String get label => lastFour == null ? name : '$name •$lastFour';
}

/// `GET /api/accounts`, archived included: the archived ones still name old rows, and are left out
/// of every picker for a new entry (`409 account-archived` otherwise).
final class AccountsResource extends CachedResource<List<Account>> {
  @override
  String get path => '/api/accounts';

  @override
  Map<String, String> get query => const {'includeArchived': 'true', 'page': '0', 'size': '200'};

  @override
  List<Account> decode(JsonMap json) => [
    for (final account in json.objects('content')) Account.fromJson(account),
  ];
}

final accountsProvider = NotifierProvider.autoDispose<AccountsResource, Snapshot<List<Account>>>(
  AccountsResource.new,
);
