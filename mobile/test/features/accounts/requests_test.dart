import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/accounts/domain/requests.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fixture_server.dart';

/// The bodies the accounts screen sends — the web's, byte for byte (accounts.md, API; the web's
/// `account-patch.ts`).
void main() {
  final travel = Account.fromJson({
    'id': 'acc-travel-card',
    'name': 'Travel Card',
    'lastFour': '8820',
    'type': 'CREDIT_CARD',
    'currency': 'USD',
    'openingBalance': '-215.2400',
    'balance': '-256.4400',
    'empty': false,
    'archived': false,
    'version': 3,
  });

  AccountForm form({
    String name = 'Travel Card',
    String lastFour = '8820',
    String type = 'CREDIT_CARD',
    String currency = 'USD',
    String opening = '-215.24',
  }) => AccountForm(
    name: name,
    lastFour: lastFour,
    type: type,
    currency: currency,
    openingBalance: opening,
  );

  group('a new account', () {
    test('is the five fields, trimmed, the currency upper-cased, the amount a string', () {
      expect(
        createAccountBody(
          form(
            name: '  Cash Tin ',
            lastFour: '',
            type: 'CASH',
            currency: 'usd ',
            opening: '40.00 ',
          ),
        ),
        {'name': 'Cash Tin', 'type': 'CASH', 'currency': 'USD', 'openingBalance': '40.00'},
      );
    });

    test('omits lastFour when there are no digits — absent, never empty', () {
      expect(createAccountBody(form(lastFour: '  ')).containsKey('lastFour'), isFalse);
      expect(createAccountBody(form(lastFour: '0000'))['lastFour'], '0000');
    });

    test('keeps a negative and a zero opening balance exactly as typed', () {
      expect(createAccountBody(form(opening: '-400'))['openingBalance'], '-400');
      expect(createAccountBody(form(opening: '0'))['openingBalance'], '0');
    });
  });

  group('an edit', () {
    test('an untouched form is the version alone, and is not sent', () {
      final patch = accountPatch(travel, AccountForm.of(travel));
      expect(patch, {'version': 3});
      expect(patchIsEmpty(patch), isTrue);
    });

    test('carries the version that was read, and only what changed', () {
      expect(accountPatch(travel, form(name: 'Travel Rewards')), {
        'version': 3,
        'name': 'Travel Rewards',
      });
    });

    test('an emptied digits box clears them with an explicit null', () {
      final patch = accountPatch(travel, form(lastFour: ''));
      expect(patch.containsKey('lastFour'), isTrue);
      expect(patch['lastFour'], isNull);
    });

    test('an empty box on an account that had no digits says nothing about them', () {
      final cash = Account.fromJson({...fixtureAccount(), 'lastFour': null});
      expect(accountPatch(cash, AccountForm.of(cash)).containsKey('lastFour'), isFalse);
    });

    test('the same amount written differently is no change', () {
      expect(accountPatch(travel, form(opening: '-215.2400')), {'version': 3});
      expect(accountPatch(travel, form(opening: '-215.240')), {'version': 3});
      expect(accountPatch(travel, form(opening: '-216')), {'version': 3, 'openingBalance': '-216'});
    });

    test('a currency nobody touched is never sent; lower case is the same code', () {
      expect(accountPatch(travel, form(currency: 'usd')).containsKey('currency'), isFalse);
      expect(accountPatch(travel, form(currency: 'EUR'))['currency'], 'EUR');
    });

    test('there is never a household id, a balance or an archived flag in a body', () {
      final everything = accountPatch(
        travel,
        form(name: 'X', lastFour: '', type: 'CASH', currency: 'EUR', opening: '1'),
      );
      expect(everything.keys, {
        'version',
        'name',
        'lastFour',
        'type',
        'currency',
        'openingBalance',
      });
      expect(createAccountBody(form()).keys, isNot(contains('householdId')));
    });
  });

  group('a conflict names what moved', () {
    test('field by field, comparing amounts as decimals', () {
      final theirs = Account.fromJson({
        ...fixtureAccount(),
        'name': 'Everyday Joint',
        'openingBalance': '1641.75',
        'version': 1,
      });
      final mine = Account.fromJson(fixtureAccount());
      expect(differingFields(mine, theirs), [AccountField.name]);
    });
  });

  group('the form rules', () {
    test('last four: empty or exactly four ASCII digits', () {
      for (final ok in ['', '0000', '4417', ' 1234 ']) {
        expect(isLastFour(ok), isTrue, reason: ok);
      }
      for (final bad in ['123', '12345', 'abcd', '١٢٣٤', '+123', '12 4']) {
        expect(isLastFour(bad), isFalse, reason: bad);
      }
    });

    test('an opening balance is a signed NUMERIC(19,4), never parsed', () {
      for (final ok in ['0', '-400', '1641.75', '-1234.5678', '999999999999999.9999']) {
        expect(isOpeningBalance(ok), isTrue, reason: ok);
      }
      for (final bad in ['', '1e3', '+5', '1,000', '1.23456', '1234567890123456', '--1']) {
        expect(isOpeningBalance(bad), isFalse, reason: bad);
      }
    });

    test('an unknown type is a neutral word, not a code', () {
      expect(accountTypeKey('CREDIT_CARD'), 'accounts.type.CREDIT_CARD');
      expect(accountTypeKey('CRYPTO'), 'accounts.type.unknown');
    });
  });

  group('reading an account', () {
    test("emptiness is the server's fact, never the amounts'", () {
      // Its history nets to zero: balance 0, still not empty.
      final joint = Account.fromJson({
        ...fixtureAccount(),
        'balance': '0.0000',
        'openingBalance': '310.0000',
        'empty': false,
      });
      expect(joint.knownToHaveHistory, isTrue);
      // An older server that does not say: not known, so Delete is offered and the server decides.
      final older = Map<String, Object?>.of(fixtureAccount())..remove('empty');
      expect(Account.fromJson(older).knownToHaveHistory, isFalse);
    });

    test('a malformed opening balance is unreadable, not a zero', () {
      expect(
        () => Account.fromJson({...fixtureAccount(), 'openingBalance': '1e9'}),
        throwsA(isA<UnexpectedJson>()),
      );
      expect(
        () => Account.fromJson({...fixtureAccount(), 'balance': '12.34567'}),
        throwsA(isA<UnexpectedJson>()),
      );
      expect(
        () => Account.fromJson({...fixtureAccount(), 'openingBalance': 12}),
        throwsA(isA<UnexpectedJson>()),
      );
    });
  });
}

/// Everyday Checking as the API sent it in the `accounts` state.
JsonMap fixtureAccount() {
  final page =
      fixtureBody(
            'accounts',
            state: 'accounts',
            path: '/api/accounts?includeArchived=true&page=0&size=200',
          )!
          as Map<String, Object?>;
  return (page['content']! as List<Object?>).cast<Map<String, Object?>>().firstWhere(
    (a) => a['name'] == 'Everyday Checking',
  );
}
