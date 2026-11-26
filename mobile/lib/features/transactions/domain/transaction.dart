import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:meta/meta.dart';

/// How settled a transaction is. `RECONCILED` is displayed but never chosen: it is
/// reconciliation's to say, and the server refuses it from a person (transactions.md rule 9).
enum TransactionStatus {
  pending,
  cleared,
  reconciled,
  other;

  static TransactionStatus parse(String wire) => switch (wire) {
    'PENDING' => pending,
    'CLEARED' => cleared,
    'RECONCILED' => reconciled,
    _ => other,
  };

  String get wire => switch (this) {
    pending => 'PENDING',
    cleared => 'CLEARED',
    reconciled => 'RECONCILED',
    other => 'OTHER',
  };

  /// The web's message key for the status.
  String get messageKey => this == other ? 'transactions.status.unknown' : 'transactions.status.$wire';

  static const List<TransactionStatus> settable = [cleared, pending];
}

/// One transaction, as `TransactionResponse` sends it.
///
/// **The amount is signed** (negative is money leaving) and **in the account's currency**; there
/// is no income/expense flag because the sign already says it (transactions.md rules 1–2).
/// A `null` category is "Needs a category" — a real state, not missing data.
final class Transaction {
  const Transaction({
    required this.id,
    required this.accountId,
    required this.accountName,
    required this.date,
    required this.amount,
    required this.wireAmount,
    required this.status,
    required this.transfer,
    required this.version,
    this.payeeName,
    this.categoryId,
    this.categoryName,
    this.categoryParentName,
    this.note,
    this.transferGroupId,
  });

  factory Transaction.fromJson(JsonMap json) {
    final wire = json.string('amount');
    return Transaction(
      id: json.string('id'),
      accountId: json.string('accountId'),
      accountName: json.string('accountName'),
      date: CalendarDate.parse(json.string('date')),
      amount: Money.fromWire(wire, json.string('currency')),
      wireAmount: wire,
      payeeName: json.stringOrNull('payeeName'),
      categoryId: json.stringOrNull('categoryId'),
      categoryName: json.stringOrNull('categoryName'),
      categoryParentName: json.stringOrNull('categoryParentName'),
      note: json.stringOrNull('note'),
      status: TransactionStatus.parse(json.string('status')),
      transfer: json.boolean('transfer'),
      transferGroupId: json.stringOrNull('transferGroupId'),
      version: json.integer('version'),
    );
  }

  final String id;
  final String accountId;
  final String accountName;
  final CalendarDate date;
  final Money amount;

  /// The amount exactly as sent, for comparing an edit against without a `double`.
  final String wireAmount;
  final String? payeeName;
  final String? categoryId;
  final String? categoryName;
  final String? categoryParentName;
  final String? note;
  final TransactionStatus status;

  /// One half of a transfer: neither income nor spending (ADR-0033).
  final bool transfer;
  final String? transferGroupId;
  final int version;

  bool get outgoing => amount.isNegative;

  bool get needsCategory => categoryId == null && !transfer;

  /// The transfer this row is half of, or null when there is none **to act on**. A group id on a
  /// row not marked as a transfer is a server this client does not take the word of, and a missing
  /// one is never guessed — a guess would edit somebody else's pair (transfers.md, UI).
  String? get actionableTransferId => transfer ? transferGroupId : null;
}

/// A page of the ledger. `pageTotal` is the server's, and null when the page mixes currencies —
/// never a converted figure (ADR-0022).
final class Ledger {
  const Ledger({
    required this.content,
    required this.page,
    required this.totalElements,
    required this.totalPages,
    this.pageTotal,
  });

  factory Ledger.fromJson(JsonMap json) {
    final total = json.stringOrNull('pageTotal');
    final currency = json.stringOrNull('pageTotalCurrency');
    return Ledger(
      content: [for (final row in json.objects('content')) Transaction.fromJson(row)],
      pageTotal: total == null || currency == null ? null : Money.fromWire(total, currency),
      page: json.integer('page'),
      totalElements: json.integer('totalElements'),
      totalPages: json.integer('totalPages'),
    );
  }

  final List<Transaction> content;
  final Money? pageTotal;
  final int page;
  final int totalElements;
  final int totalPages;
}

/// The ledger's filters, as the web's `LedgerQuery`. Every field optional; none set is
/// "everything, newest first".
@immutable
final class LedgerQuery {
  const LedgerQuery({
    this.accountId,
    this.categoryId,
    this.uncategorised = false,
    this.from,
    this.to,
    this.search,
    this.page = 0,
  });

  /// The web's page size: large enough to feel like a list.
  static const int pageSize = 50;

  final String? accountId;
  final String? categoryId;

  /// "Needs a category" only. Separate from [categoryId], since an absent category already means
  /// "any category" (transactions.md, API).
  final bool uncategorised;
  final CalendarDate? from;
  final CalendarDate? to;
  final String? search;
  final int page;

  bool get filtered =>
      accountId != null ||
      categoryId != null ||
      uncategorised ||
      from != null ||
      to != null ||
      (search?.trim().isNotEmpty ?? false);

  /// Only the first page of a query with no free-text search is cached: a search's every variant
  /// would be another entry (mobile-app.md, The cache).
  bool get cacheable => page == 0 && (search?.trim().isEmpty ?? true);

  /// Query parameters, set **only when set** — an empty string would be a filter that matches
  /// nothing rather than no filter.
  Map<String, String> toQuery() => {
    'page': '$page',
    'size': '$pageSize',
    'accountId': ?accountId,
    'categoryId': ?categoryId,
    if (uncategorised) 'uncategorised': 'true',
    if (from != null) 'from': from!.iso,
    if (to != null) 'to': to!.iso,
    if (search != null && search!.trim().isNotEmpty) 'search': search!.trim(),
  };

  LedgerQuery copyWith({
    String? Function()? accountId,
    String? Function()? categoryId,
    bool? uncategorised,
    CalendarDate? Function()? from,
    CalendarDate? Function()? to,
    String? Function()? search,
    int? page,
  }) => LedgerQuery(
    accountId: accountId == null ? this.accountId : accountId(),
    categoryId: categoryId == null ? this.categoryId : categoryId(),
    uncategorised: uncategorised ?? this.uncategorised,
    from: from == null ? this.from : from(),
    to: to == null ? this.to : to(),
    search: search == null ? this.search : search(),
    // Any change to the filters goes back to page 0: page 3 of a different query is no page.
    page: page ?? 0,
  );

  @override
  bool operator ==(Object other) =>
      other is LedgerQuery &&
      other.accountId == accountId &&
      other.categoryId == categoryId &&
      other.uncategorised == uncategorised &&
      other.from == from &&
      other.to == to &&
      other.search == search &&
      other.page == page;

  @override
  int get hashCode => Object.hash(accountId, categoryId, uncategorised, from, to, search, page);
}

/// One transfer, as `TransferResponse` sends it: the **pair**, addressed by its group id.
/// `sent` and `received` are unsigned and differ across currencies (ADR-0033).
final class Transfer {
  const Transfer({
    required this.id,
    required this.fromAccountId,
    required this.fromAccountName,
    required this.toAccountId,
    required this.toAccountName,
    required this.fromDate,
    required this.toDate,
    required this.sent,
    required this.sentWire,
    required this.received,
    required this.receivedWire,
    required this.crossCurrency,
    required this.version,
    this.impliedRate,
    this.fromNote,
    this.toNote,
  });

  factory Transfer.fromJson(JsonMap json) {
    final sent = json.string('sent');
    final received = json.string('received');
    return Transfer(
      id: json.string('id'),
      fromAccountId: json.string('fromAccountId'),
      fromAccountName: json.string('fromAccountName'),
      toAccountId: json.string('toAccountId'),
      toAccountName: json.string('toAccountName'),
      fromDate: CalendarDate.parse(json.string('fromDate')),
      toDate: CalendarDate.parse(json.string('toDate')),
      sent: Money.fromWire(sent, json.string('sentCurrency')),
      sentWire: sent,
      received: Money.fromWire(received, json.string('receivedCurrency')),
      receivedWire: received,
      crossCurrency: json.boolean('crossCurrency'),
      impliedRate: json.stringOrNull('impliedRate'),
      fromNote: json.stringOrNull('fromNote'),
      toNote: json.stringOrNull('toNote'),
      version: json.integer('version'),
    );
  }

  final String id;
  final String fromAccountId;
  final String fromAccountName;
  final String toAccountId;
  final String toAccountName;
  final CalendarDate fromDate;
  final CalendarDate toDate;
  final Money sent;
  final String sentWire;
  final Money received;
  final String receivedWire;
  final bool crossCurrency;

  /// `received / sent`, derived for display and read by nothing (ADR-0022). Not the bank's rate.
  final String? impliedRate;
  final String? fromNote;
  final String? toNote;

  /// The pair's version: the sum of the legs'. What a `PATCH` must send back.
  final int version;
}
