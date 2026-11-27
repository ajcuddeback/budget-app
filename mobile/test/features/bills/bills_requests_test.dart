import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/features/bills/domain/bill.dart';
import 'package:budget_owl/features/bills/domain/requests.dart';
import 'package:flutter_test/flutter_test.dart';

import '../recorded.dart';

/// Every body Bills & income sends, against the body the real API accepted in
/// `bills.generated.json`. A builder that drifts from the API fails here before it fails a phone.
void main() {
  final recorded = Recorded('bills');
  const billsPath = '/api/bills?period=2026-08&includeInactive=true';
  const incomePath = '/api/income?period=2026-08&includeInactive=true';

  Bill bill(String state, String id) =>
      BillsPage.fromJson(recorded.answer(state, billsPath)).content.firstWhere((b) => b.id == id);

  IncomeSource income(String state, String id) =>
      IncomeList.fromJson(recorded.answer(state, incomePath)).content.firstWhere((s) => s.id == id);

  BillForm formOf(Bill b, {String? expectedAmount, String? name}) => BillForm(
    name: name ?? b.name,
    expectedAmount: expectedAmount ?? b.expectedAmount,
    schedule: ScheduleForm.of(b.schedule),
    categoryId: b.categoryId,
    amountVaries: b.amountVaries,
    tolerance: b.tolerance ?? '',
  );

  group('occurrences', () {
    test('Mark paid, Skip and Mark as due send the status alone, with no version', () {
      expect(
        statusBody(OccurrenceStatus.paid),
        recorded.sent('plans', 'PUT', '/api/bills/bil-0003/occurrences/2026-08-18'),
      );
      expect(
        statusBody(OccurrenceStatus.skipped),
        recorded.sent('paid-other', 'PUT', '/api/bills/bil-0004/occurrences/2026-08-25'),
      );
      expect(
        statusBody(OccurrenceStatus.due),
        recorded.sent('skipped', 'PUT', '/api/bills/bil-0001/occurrences/2026-08-01'),
      );
    });

    test('a different amount sends the figure and the date, trimmed', () {
      expect(
        paidBody(actualAmount: ' 14.50 ', paidDate: CalendarDate.parse('2026-08-20')),
        recorded.sent('paid', 'PUT', '/api/bills/bil-0005/occurrences/2026-08-17'),
      );
      expect(
        paidBody(actualAmount: '25', paidDate: CalendarDate.parse('2026-08-20')),
        recorded.sent('forgotten', 'PUT', '/api/bills/bil-0008/occurrences/2026-08-20'),
      );
    });

    test('an empty amount is left out, not sent as zero', () {
      expect(paidBody(actualAmount: '  '), {'status': 'PAID'});
    });
  });

  group('bills', () {
    test('adding sends the kind and only its parameters', () {
      final body = createBillBody(
        const BillForm(
          name: ' Water ',
          expectedAmount: '38.50',
          schedule: ScheduleForm(dayOfMonth: 22, dayOfWeek: DayOfWeek.monday),
        ),
      );
      expect(body, recorded.sent('recorded', 'POST', '/api/bills'));
    });

    test('changing the kind clears what the new kind does not use', () {
      final weekly = const ScheduleForm(dayOfMonth: 22).withKind(ScheduleKind.weekly);
      expect(weekly.dayOfMonth, isNull);
      expect(weekly.missing, {ScheduleParameter.dayOfWeek});
      expect(scheduleBody(weekly), {'kind': 'WEEKLY'});
      final yearly = const ScheduleForm(dayOfMonth: 1).withKind(ScheduleKind.yearly);
      expect(yearly.dayOfMonth, 1, reason: 'a yearly bill keeps its day');
      expect(yearly.missing, {ScheduleParameter.month});
    });

    test('a varying amount and its tolerance travel together', () {
      final body = createBillBody(
        const BillForm(
          name: 'Gas',
          expectedAmount: '80',
          schedule: ScheduleForm(dayOfMonth: 3),
          amountVaries: true,
          tolerance: ' 15 ',
        ),
      );
      expect(body['amountVaries'], true);
      expect(body['tolerance'], '15');
      expect(
        createBillBody(
          const BillForm(
            name: 'Gas',
            expectedAmount: '80',
            schedule: ScheduleForm(dayOfMonth: 3),
            tolerance: '15',
          ),
        ).containsKey('tolerance'),
        isFalse,
      );
    });

    test('an edit sends the version and only what changed', () {
      final phone = bill('bill-added', 'bil-0002');
      expect(
        billPatch(phone, formOf(phone, expectedAmount: '48')),
        recorded.sent('bill-added', 'PATCH', '/api/bills/bil-0002'),
      );
    });

    test('the same figure typed differently is no change', () {
      final phone = bill('plans', 'bil-0002');
      final patch = billPatch(phone, formOf(phone, expectedAmount: '45.00'));
      expect(patchIsEmpty(patch), isTrue);
    });

    test('turning the variation off sends the flag alone', () {
      final electricity = bill('plans', 'bil-0003');
      expect(electricity.amountVaries, isTrue);
      final patch = billPatch(
        electricity,
        BillForm(
          name: electricity.name,
          expectedAmount: electricity.expectedAmount,
          schedule: ScheduleForm.of(electricity.schedule),
          categoryId: electricity.categoryId,
          tolerance: electricity.tolerance ?? '',
        ),
      );
      expect(patch, {'version': electricity.version, 'amountVaries': false});
    });

    test('clearing the category sends null, which the server reads as "clear"', () {
      final rent = bill('plans', 'bil-0001');
      expect(rent.categoryId, isNotNull);
      final patch = billPatch(
        rent,
        BillForm(
          name: rent.name,
          expectedAmount: rent.expectedAmount,
          schedule: ScheduleForm.of(rent.schedule),
        ),
      );
      expect(patch, {'version': rent.version, 'categoryId': null});
    });

    test('retiring and restoring send the version and the flag', () {
      expect(
        activePatch(bill('bill-edited', 'bil-0005').version, active: false),
        recorded.sent('bill-edited', 'PATCH', '/api/bills/bil-0005'),
      );
      expect(
        activePatch(bill('bill-retired', 'bil-0009').version, active: true),
        recorded.sent('bill-retired', 'PATCH', '/api/bills/bil-0009'),
      );
    });
  });

  group('income', () {
    test('adding a weekly source sends its weekday and nothing else', () {
      final body = createIncomeBody(
        const IncomeForm(
          name: 'Tutoring',
          amount: '60',
          schedule: ScheduleForm(kind: ScheduleKind.weekly, dayOfWeek: DayOfWeek.saturday),
        ),
      );
      expect(body, recorded.sent('bill-deleted', 'POST', '/api/income'));
    });

    test('an edit sends the version and only the amount', () {
      final tutoring = income('income-added', 'inc-0004');
      final patch = incomePatch(
        tutoring,
        IncomeForm(
          name: tutoring.name,
          amount: '65',
          schedule: ScheduleForm.of(tutoring.schedule),
          accountId: tutoring.accountId,
        ),
      );
      expect(patch, recorded.sent('income-added', 'PATCH', '/api/income/inc-0004'));
    });

    test('retiring and restoring', () {
      expect(
        activePatch(income('income-edited', 'inc-0002').version, active: false),
        recorded.sent('income-edited', 'PATCH', '/api/income/inc-0002'),
      );
      expect(
        activePatch(income('income-retired', 'inc-0003').version, active: true),
        recorded.sent('income-retired', 'PATCH', '/api/income/inc-0003'),
      );
    });

    test('a fortnightly source needs its anchor date', () {
      final salary = income('plans', 'inc-0001');
      expect(salary.schedule.kind, ScheduleKind.fortnightly);
      final cleared = ScheduleForm.of(salary.schedule).withKind(ScheduleKind.monthly);
      expect(scheduleBody(cleared).containsKey('anchorDate'), isFalse);
    });
  });
}
