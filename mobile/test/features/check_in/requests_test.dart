import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/features/check_in/domain/check_in.dart';
import 'package:budget_owl/features/check_in/domain/requests.dart';
import 'package:flutter_test/flutter_test.dart' hide completion;

/// Every body the check-in sends, as the web's `check-in-data.spec.ts` pins them.
void main() {
  Item item({Answer? answer, ChargeReason? reason, String? suggested, String? billId}) => Item(
    itemKey: 'chg.row',
    kind: ItemKind.billCharge,
    question: true,
    reason: reason,
    billId: billId,
    dueDate: billId == null ? null : CalendarDate.parse('2026-08-25'),
    suggestedBillId: suggested,
    suggestedDueDate: suggested == null ? null : CalendarDate.parse('2026-08-25'),
    answer: answer,
  );

  const given = Answer(
    choice: AnswerChoice.category,
    remember: false,
    recordDebtPayment: false,
    byRule: false,
    version: 3,
  );

  test('the plain choices carry their name and nothing else', () {
    for (final choice in [
      AnswerChoice.prepaid,
      AnswerChoice.leave,
      AnswerChoice.skip,
      AnswerChoice.notABill,
    ]) {
      expect(choiceOnly(choice), {'choice': choice.wire});
    }
  });

  test('mark paid carries the debt-plan prompt, never assumed', () {
    expect(markPaid(recordDebtPayment: false), {'choice': 'MARK_PAID', 'recordDebtPayment': false});
  });

  test('a category, with "always file" off unless ticked', () {
    expect(category('cat-1', remember: false), {
      'choice': 'CATEGORY',
      'categoryId': 'cat-1',
      'remember': false,
    });
  });

  test('the version only when replacing an answer', () {
    expect(withVersion(item(), choiceOnly(AnswerChoice.leave)), {'choice': 'LEAVE'});
    expect(withVersion(item(answer: given), choiceOnly(AnswerChoice.leave)), {
      'choice': 'LEAVE',
      'version': 3,
    });
  });

  test('"Map it" attaches to the suggestion and remembers; "Attach" on a rule does not', () {
    expect(
      attachSuggested(
        item(reason: ChargeReason.recurring, suggested: 'bill-internet'),
        recordDebtPayment: false,
      ),
      {
        'choice': 'ATTACH',
        'billId': 'bill-internet',
        'dueDate': '2026-08-25',
        'remember': true,
        'recordDebtPayment': false,
      },
    );
    expect(
      attachSuggested(
        item(reason: ChargeReason.ruleOutsideTolerance, billId: 'bill-gym'),
        recordDebtPayment: true,
      )?['remember'],
      isFalse,
    );
    expect(
      attachSuggested(item(reason: ChargeReason.recurring), recordDebtPayment: false),
      isNull,
      reason: 'no suggestion: the picker opens instead',
    );
  });

  test("the allocation sends the server's surplus string back, untouched", () {
    const goal = Choice(choice: AnswerChoice.goal, goalId: 'goal-1');
    const debt = Choice(choice: AnswerChoice.debt, debtId: 'debt-1');
    const roll = Choice(choice: AnswerChoice.rollOver);
    expect(allocation(goal, '63.0000'), {
      'choice': 'GOAL',
      'goalId': 'goal-1',
      'amount': '63.0000',
    });
    expect(allocation(debt, '63.0000'), {
      'choice': 'DEBT',
      'debtId': 'debt-1',
      'amount': '63.0000',
    });
    expect(allocation(roll, '63.0000'), {'choice': 'ROLL_OVER', 'amount': '63.0000'});
    expect(allocation(const Choice(choice: AnswerChoice.goal), '63.0000'), isNull);
  });

  test('completion sends the version and the basis the person last saw, and the phone date', () {
    final week = Week(
      week: '2026-W34',
      weekStart: CalendarDate.parse('2026-08-17'),
      weekEnd: CalendarDate.parse('2026-08-23'),
      status: CheckInStatus.draft,
      otherCurrencyRows: 0,
      steps: const [],
      outcome: const [],
      version: 9,
      basis: 'b' * 64,
    );
    expect(completion(week, CalendarDate.parse('2026-09-01')), {
      'version': 9,
      'basis': 'b' * 64,
      'today': '2026-09-01',
    });
  });

  test('only a week ever reaches a path, and the item key is encoded', () {
    expect(answerPath('2026-W34', 'cat.a/b'), '/api/check-ins/2026-W34/answers/cat.a%2Fb');
    expect(() => weekPath('../admin'), throwsArgumentError);
    expect(() => weekPath('2026-W34/answers'), throwsArgumentError);
  });

  test('a stale refusal is read defensively', () {
    expect(
      staleItems({
        'items': [
          {'itemKey': 'cat.1', 'reason': 'subject-changed'},
          {'reason': 'basis-changed'},
          {'itemKey': 'cat.2'},
          'nonsense',
        ],
      }).map((i) => '${i.itemKey}:${i.reason}'),
      ['cat.1:subject-changed', 'null:basis-changed', 'cat.2:unknown'],
    );
    expect(staleItems(const {}), isEmpty);
  });
}
