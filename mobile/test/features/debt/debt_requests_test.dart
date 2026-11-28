import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/features/debt/domain/plan_view.dart';
import 'package:budget_owl/features/debt/domain/requests.dart';
import 'package:flutter_test/flutter_test.dart';

import '../recorded.dart';

/// Every body the debt screens send, against the body the real API accepted in
/// `debt.generated.json`, and the three-way `PATCH` rule on its own.
void main() {
  final recorded = Recorded('debt');
  const debtsPath = '/api/debts?includeArchived=false';
  final f = Formats(
    money: (wire, currency) => '$currency $wire',
    month: (wire) => wire ?? '-',
    rate: (wire) => '$wire%',
    date: (date) => date.iso,
    count: (n) => '$n',
    message: (said) => said.key,
  );

  DebtList debts(String state) => DebtList.fromJson(recorded.answer(state, debtsPath));
  Debt debt(String state, String id) => debts(state).debts.firstWhere((d) => d.id == id);
  Plan plan(String state) => Plan.fromJson(recorded.answer(state, '/api/debt-plan'));

  group('the three-way PATCH', () {
    test('absent keeps, null releases, a value enters — and nothing else is sent', () {
      expect(termsPatch(3), {'version': 3});
      expect(
        termsPatch(
          3,
          apr: const Released(),
          payment: const Entered('445'),
          monthsLeft: const Entered(17),
        ),
        {'version': 3, 'apr': null, 'payment': '445', 'monthsLeft': 17},
      );
      expect(termsPatch(3, payoffMonth: const Entered('2027-12')), {
        'version': 3,
        'payoffMonth': '2027-12',
      });
    });

    test('an edit sends the version and only what changed', () {
      final visa = debt('payment-logged', 'deb-0001');
      final form = DebtForm.of(visa);
      final edited = DebtForm(
        name: form.name,
        kind: form.kind,
        servicer: 'Chase Bank',
        currency: form.currency,
        balance: form.balance,
        balanceDate: form.balanceDate,
        apr: form.apr,
        aprSolved: form.aprSolved,
        minimumPayment: form.minimumPayment,
        originalBalance: form.originalBalance,
        accountId: form.accountId,
        billId: form.billId,
      );
      expect(
        debtPatch(visa, edited),
        recorded.sent('payment-logged', 'PATCH', '/api/debts/deb-0001'),
      );
    });

    test('the form as it opens changes nothing', () {
      for (final d in debts('plans').debts) {
        expect(patchIsEmpty(debtPatch(d, DebtForm.of(d))), isTrue, reason: d.name);
      }
    });

    test('ticking "work the rate out" on an entered rate releases it with an explicit null', () {
      final visa = debt('plans', 'deb-0001');
      expect(visa.aprSource, TermSource.entered);
      final form = DebtForm.of(visa);
      final ticked = DebtForm(
        name: form.name,
        kind: form.kind,
        servicer: form.servicer,
        balance: form.balance,
        balanceDate: form.balanceDate,
        aprSolved: true,
        minimumPayment: form.minimumPayment,
        originalBalance: form.originalBalance,
        accountId: form.accountId,
        billId: form.billId,
      );
      final patch = debtPatch(visa, ticked);
      expect(patch.containsKey('apr'), isTrue);
      expect(patch['apr'], isNull);
      expect(patch.keys, ['version', 'apr']);
    });

    test('the same rate typed differently says nothing', () {
      final visa = debt('plans', 'deb-0001');
      final form = DebtForm.of(visa);
      final retyped = DebtForm(
        name: form.name,
        kind: form.kind,
        servicer: form.servicer,
        balance: form.balance,
        balanceDate: form.balanceDate,
        apr: '22.900',
        minimumPayment: form.minimumPayment,
        originalBalance: form.originalBalance,
        accountId: form.accountId,
        billId: form.billId,
      );
      expect(debtPatch(visa, retyped).containsKey('apr'), isFalse);
    });

    test('emptying the starting balance clears it; the servicer emptied is null', () {
      final visa = debt('plans', 'deb-0001');
      final form = DebtForm.of(visa);
      final emptied = DebtForm(
        name: form.name,
        kind: form.kind,
        balance: form.balance,
        balanceDate: form.balanceDate,
        apr: form.apr,
        minimumPayment: form.minimumPayment,
        accountId: form.accountId,
        billId: form.billId,
      );
      final patch = debtPatch(visa, emptied);
      expect(patch, {'version': visa.version, 'servicer': null, 'originalBalance': null});
      expect(patch.containsKey('balance'), isFalse, reason: 'the balance is never patched');
    });
  });

  group('the other bodies', () {
    test('adding a debt sends the rate and leaves the empty optionals out', () {
      final body = createDebtBody(
        DebtForm(
          name: 'Store card',
          kind: DebtKind.creditCard,
          servicer: 'Argos',
          balance: '1240',
          balanceDate: CalendarDate.parse('2026-05-20'),
          apr: '29.9',
          minimumPayment: '45',
        ),
      );
      expect(body, recorded.sent('terms-edited', 'POST', '/api/debts'));
    });

    test('a payment sends the date, the amount and where it left — never the split', () {
      final body = logPaymentBody(
        PaymentForm(
          paidDate: CalendarDate.parse('2026-08-20'),
          amount: ' 495 ',
          paidFromAccountId: 'acc-0001',
        ),
      );
      expect(body, recorded.sent('plans', 'POST', '/api/debts/deb-0001/payments'));
      expect(body.keys, isNot(contains('interest')));
    });

    test('a preview puts the amount and the date in a body, not a URL', () {
      // The date the sheet will record: which month's interest the payment carries depends on it.
      expect(
        previewBody(' 445 ', CalendarDate.parse('2026-08-20')),
        recorded.sent('plans', 'POST', '/api/debts/deb-0001/payments/preview'),
      );
      expect(recorded.writesIn('plans'), contains('POST /api/debts/deb-0001/payments/preview'));
    });

    test('a statement figure sends the balance and its date', () {
      expect(
        confirmBalanceBody(balance: '1180', balanceDate: CalendarDate.parse('2026-08-20')),
        recorded.sent('debt-added', 'POST', '/api/debts/deb-0004/balance'),
      );
      expect(
        confirmBalanceBody(balance: '860', balanceDate: CalendarDate.parse('2026-08-20')),
        recorded.sent('stale-added', 'POST', '/api/debts/deb-0005/balance'),
      );
    });

    test('choosing a strategy sends the plan version and its name', () {
      expect(
        strategyBody(plan('stale-confirmed').version, Strategy.snowball),
        recorded.sent('stale-confirmed', 'PUT', '/api/debt-plan'),
      );
      expect(
        strategyBody(plan('snowball').version, Strategy.avalanche),
        recorded.sent('snowball', 'PUT', '/api/debt-plan'),
      );
    });

    test('"Your own order" starts from the order on screen, the whole of it', () {
      final p = plan('avalanche');
      expect(
        customOrderBody(p.version, rankedOrder(p, debts('avalanche'), f)),
        recorded.sent('avalanche', 'PUT', '/api/debt-plan'),
      );
    });

    test('moving a debt swaps it with its neighbour and sends every position', () {
      final p = plan('custom');
      final list = debts('custom');
      final moved = movedOrder(p, list, p.currency, 'deb-0005', 1, f);
      expect(customOrderBody(p.version, moved!), recorded.sent('custom', 'PUT', '/api/debt-plan'));
      expect(movedOrder(p, list, p.currency, 'deb-0005', -1, f), isNull, reason: 'top rung');
    });

    test('the monthly amount', () {
      expect(
        monthlyAmountBody(plan('custom-moved').version, ' 2000 '),
        recorded.sent('custom-moved', 'PUT', '/api/debt-plan'),
      );
      expect(
        monthlyAmountBody(plan('amount').version, '600'),
        recorded.sent('amount', 'PUT', '/api/debt-plan'),
      );
    });
  });

  group('what the plan asks and says', () {
    test('the what-if asks for the plan plus fifty, exactly, as recorded', () {
      expect(whatIfAmount(plan('plans')), '955.0000');
      expect(whatIfAmount(plan('below')), isNull, reason: 'a plan below its minimums asks nothing');
      expect(whatIfAmount(plan('empty')), isNull, reason: 'no debt, no question');
    });

    test('a rate nobody can work out is "not available" — never 0%', () {
      expect(rateOf(null, derivable: false, f: f).key, 'debt.rate.unavailable');
      expect(rateOf(null, derivable: true, f: f).key, 'debt.rate.notEntered');
      expect(rateOf('22.9', derivable: true, f: f).params, {'rate': '22.9%'});
    });

    test('a debt that never clears says why, not a blank', () {
      expect(clearsOf(null, 'never-clears', f).key, 'debt.clears.never');
      expect(clearsOf(null, 'plan-below-minimums', f).key, 'debt.clears.noPlan');
      expect(clearsOf('2027-12', null, f).params, {'month': '2027-12'});
    });

    test('a negative extra says what clears it, never "extra −65"', () {
      const owesLess = Rung(
        rung: 3,
        debtId: 'deb-0009',
        minimum: '100.0000',
        extra: '-65.0000',
        monthlyAmount: '35.0000',
      );
      final said = payingOf(owesLess, 'USD', f);
      expect(said.key, 'debt.paying.clearsIt');
      expect(said.params, {'amount': 'USD 35.0000'});
    });

    test('estimated and stale balances each say what they are', () {
      expect(balanceNoteOf(debt('debt-added', 'deb-0004'), f).key, 'debt.balance.estimated');
      expect(balanceNoteOf(debt('stale-added', 'deb-0005'), f).key, 'debt.balance.stale');
      expect(balanceNoteOf(debt('plans', 'deb-0001'), f).key, isNot(startsWith('debt.balance.e')));
    });

    test('the comparison names the cheaper order and invents no difference figure', () {
      final group = plan('plans').planGroup!;
      final said = comparisonOf(group, f)!;
      expect(said.params.keys, isNot(contains('difference')));
      expect(said.params['cheaper'], 'debt.strategy.AVALANCHE');
    });

    test('a line clears the month before its first zero', () {
      expect(
        clearedMonth([
          const SeriesPoint('2027-11', '120.0000'),
          const SeriesPoint('2027-12', '0.0000'),
        ]),
        '2027-11',
      );
      expect(clearedMonth([const SeriesPoint('2027-11', '1.0000')]), isNull);
      expect(monthsBetween('2029-02', '2029-04'), 2);
      expect(monthsBetween(null, '2029-04'), isNull);
    });
  });
}
