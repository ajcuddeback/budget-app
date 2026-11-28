import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/transactions/domain/requests.dart';
import 'package:budget_owl/features/transactions/domain/transaction.dart';
import 'package:flutter_test/flutter_test.dart';

/// The bodies the ledger sends — the same ones the web sends (transactions.md, transfers.md).
void main() {
  final original = Transaction.fromJson({
    'id': 'row-1',
    'accountId': 'acc-0001',
    'accountName': 'Everyday Checking',
    'date': '2026-08-20',
    'amount': '-92.1500',
    'currency': 'USD',
    'payeeName': 'Greenfield Market',
    'categoryId': null,
    'categoryName': null,
    'categoryParentName': null,
    'note': null,
    'status': 'PENDING',
    'transfer': false,
    'transferGroupId': null,
    'version': 3,
  });

  TransactionForm form({
    String amount = '92.15',
    Direction direction = Direction.out,
    String payee = 'Greenfield Market',
    String? category,
    String note = '',
    TransactionStatus status = TransactionStatus.pending,
    String date = '2026-08-20',
  }) => TransactionForm(
    accountId: 'acc-0001',
    date: CalendarDate.parse(date),
    direction: direction,
    amount: ValidAmount(amount),
    payeeName: payee,
    categoryId: category,
    note: note,
    status: status,
  );

  group('a new transaction', () {
    test('is signed by the direction, with the amount as a string', () {
      final body = createTransactionBody(form(amount: '4.50', status: TransactionStatus.cleared));
      expect(body['amount'], '-4.50');
      expect(body['status'], 'CLEARED');
    });

    test('money in is unsigned', () {
      expect(createTransactionBody(form(direction: Direction.into))['amount'], '92.15');
    });

    test('omits an empty payee, category and note rather than sending them empty', () {
      final body = createTransactionBody(form(payee: '  '));
      expect(body.keys, ['accountId', 'date', 'amount', 'status']);
    });
  });

  group('an edit', () {
    test('of nothing sends nothing', () {
      expect(patchIsEmpty(transactionPatch(original, TransactionForm.of(original))), isTrue);
    });

    test('carries the version and only what changed', () {
      final patch = transactionPatch(original, form(category: 'cat-0006'));
      expect(patch, {'version': 3, 'categoryId': 'cat-0006'});
    });

    test('the same amount written differently is no change', () {
      expect(transactionPatch(original, form(amount: '92.1500')), {'version': 3});
    });

    test('clearing a field sends null; untouched fields are absent', () {
      final patch = transactionPatch(original, form(payee: ''));
      expect(patch, {'version': 3, 'payeeName': null});
    });

    test('a new note, status, date and amount', () {
      final patch = transactionPatch(
        original,
        form(
          note: ' weekly shop ',
          status: TransactionStatus.cleared,
          date: '2026-08-19',
          amount: '90',
        ),
      );
      expect(patch, {
        'version': 3,
        'date': '2026-08-19',
        'amount': '-90',
        'note': 'weekly shop',
        'status': 'CLEARED',
      });
    });

    test('never carries an account', () {
      expect(transactionPatch(original, form(amount: '1')).containsKey('accountId'), isFalse);
    });
  });

  group('a transfer', () {
    final transfer = Transfer.fromJson({
      'id': 'tgr-0003',
      'fromAccountId': 'acc-0001',
      'fromAccountName': 'Everyday Checking',
      'toAccountId': 'acc-0004',
      'toAccountName': 'Berlin Savings',
      'fromDate': '2026-08-20',
      'toDate': '2026-08-20',
      'sent': '100.0000',
      'sentCurrency': 'USD',
      'received': '92.5000',
      'receivedCurrency': 'EUR',
      'crossCurrency': true,
      'impliedRate': '0.925000',
      'fromNote': null,
      'toNote': null,
      'version': 0,
    });

    TransferForm move({String amount = '100', String? received, String note = ''}) => TransferForm(
      fromAccountId: 'acc-0001',
      toAccountId: 'acc-0004',
      date: CalendarDate.parse('2026-08-20'),
      amount: ValidAmount(amount),
      amountReceived: received == null ? null : ValidAmount(received),
      note: note,
    );

    test('sends a second figure across currencies, and only then', () {
      expect(
        createTransferBody(move(received: '92.50'), crossCurrency: true)['amountReceived'],
        '92.50',
      );
      expect(
        createTransferBody(
          move(received: '92.50'),
          crossCurrency: false,
        ).containsKey('amountReceived'),
        isFalse,
      );
    });

    test('an edit sends only what changed, and no accounts', () {
      expect(transferPatch(transfer, move(received: '92.5')), {'version': 0});
      expect(transferPatch(transfer, move(amount: '101', received: '92.5')), {
        'version': 0,
        'amount': '101',
      });
      expect(transferPatch(transfer, move(received: '93', note: 'fees')), {
        'version': 0,
        'amountReceived': '93',
        'note': 'fees',
      });
    });

    test('reads the pair as the API sends it', () {
      expect(transfer.received, Money.fromWire('92.50', 'EUR'));
      expect(transfer.crossCurrency, isTrue);
    });
  });

  group('a ledger row', () {
    test('a transfer offers its group id only when it is marked a transfer', () {
      final leg = Transaction.fromJson({
        ...{
          'id': 'leg',
          'accountId': 'acc-0001',
          'accountName': 'A',
          'date': '2026-08-16',
          'amount': '-500.0000',
          'currency': 'USD',
          'status': 'CLEARED',
          'version': 0,
        },
        'transfer': true,
        'transferGroupId': 'tgr-0001',
      });
      final odd = Transaction.fromJson({
        ...{
          'id': 'odd',
          'accountId': 'acc-0001',
          'accountName': 'A',
          'date': '2026-08-16',
          'amount': '-5.0000',
          'currency': 'USD',
          'status': 'RECONCILED',
          'version': 0,
        },
        'transfer': false,
        'transferGroupId': 'tgr-0001',
      });
      expect(leg.actionableTransferId, 'tgr-0001');
      expect(leg.needsCategory, isFalse);
      expect(odd.actionableTransferId, isNull);
      expect(odd.status.messageKey, 'transactions.status.RECONCILED');
      expect(TransactionStatus.parse('NEW').messageKey, 'transactions.status.unknown');
    });

    test('the query sets a parameter only when its filter is set', () {
      const none = LedgerQuery();
      expect(none.toQuery(), {'page': '0', 'size': '50'});
      expect(none.filtered, isFalse);
      final some = none.copyWith(
        accountId: () => 'acc-0001',
        uncategorised: true,
        search: () => '  market ',
      );
      expect(some.toQuery(), {
        'page': '0',
        'size': '50',
        'accountId': 'acc-0001',
        'uncategorised': 'true',
        'search': 'market',
      });
      expect(some.cacheable, isFalse);
      expect(some.copyWith(page: 2).page, 2);
      expect(some.copyWith(search: () => null).page, 0);
      expect(some == some.copyWith(page: 0), isTrue);
    });
  });
}
