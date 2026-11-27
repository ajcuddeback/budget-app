import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/features/check_in/domain/check_in.dart';

/// Every body the check-in sends, exactly as the web's `AnswerBody` builds it (weekly-check-in.md,
/// API, "Answer bodies"). Each is tested in `test/features/check_in/requests_test.dart`.
///
/// - **No household id, no user, no free text** anywhere: the server resolves the household and
///   refuses an unknown property.
/// - **The only amount is the surplus**, as the string the server sent. It is never typed and
///   never worked out on the phone: the server compares it with its own and refuses another.
/// - **`version` only when replacing**: the answer's own, so two members answering one row is a
///   `409 stale-version` for the second rather than an overwrite.

typedef Body = Map<String, Object?>;

/// `PREPAID`, `LEAVE`, `SKIP` or `NOT_A_BILL`: the choice alone. The bill and *k* are the
/// server's, never sent.
Body choiceOnly(AnswerChoice choice) {
  assert(
    const {
      AnswerChoice.prepaid,
      AnswerChoice.leave,
      AnswerChoice.skip,
      AnswerChoice.notABill,
    }.contains(choice),
    '$choice carries more than its name',
  );
  return {'choice': choice.wire};
}

/// An overdue bill marked paid, with the debt-plan prompt's answer — never assumed.
Body markPaid({required bool recordDebtPayment}) => {
  'choice': AnswerChoice.markPaid.wire,
  'recordDebtPayment': recordDebtPayment,
};

/// A category for a row, and whether to file the payee there from now on (off by default).
Body category(String categoryId, {required bool remember}) => {
  'choice': AnswerChoice.category.wire,
  'categoryId': categoryId,
  'remember': remember,
};

/// A charge attached to one of a bill's occurrences.
Body attach({
  required String billId,
  required CalendarDate dueDate,
  required bool remember,
  required bool recordDebtPayment,
}) => {
  'choice': AnswerChoice.attach.wire,
  'billId': billId,
  'dueDate': dueDate.iso,
  'remember': remember,
  'recordDebtPayment': recordDebtPayment,
};

/// Where the surplus goes, with the server's own surplus sent back as it was sent. Null when the
/// choice names no target the server offered.
Body? allocation(Choice choice, String surplusWire) => switch (choice.choice) {
  AnswerChoice.debt when choice.debtId != null => {
    'choice': AnswerChoice.debt.wire,
    'debtId': choice.debtId,
    'amount': surplusWire,
  },
  AnswerChoice.goal when choice.goalId != null => {
    'choice': AnswerChoice.goal.wire,
    'goalId': choice.goalId,
    'amount': surplusWire,
  },
  AnswerChoice.rollOver => {'choice': AnswerChoice.rollOver.wire, 'amount': surplusWire},
  _ => null,
};

/// [body], with the answer's version when it replaces one.
Body withVersion(Item item, Body body) {
  final answer = item.answer;
  return answer == null ? body : {...body, 'version': answer.version};
}

/// Completion: the draft's version proves the person saw every answer, the basis that the week
/// has not moved underneath them.
Body completion(Week week) => {'version': week.version, 'basis': week.basis};

/// "Map it" or "Attach": the bill and date the server suggested, or null to open the picker.
/// "Map it" creates the mapping; a rule that already exists needs no second one.
Body? attachSuggested(Item item, {required bool recordDebtPayment}) {
  final billId = item.suggestedBillId ?? item.billId;
  final dueDate = item.suggestedDueDate ?? item.dueDate;
  if (billId == null || dueDate == null) return null;
  return attach(
    billId: billId,
    dueDate: dueDate,
    remember: item.reason == ChargeReason.recurring,
    recordDebtPayment: recordDebtPayment,
  );
}

/// The path for [itemKey] in [week]. The week is checked against its pattern first, so nothing but
/// `2026-W34` ever reaches a path; the key is the server's own, encoded.
String answerPath(String week, String itemKey) =>
    '${weekPath(week)}/answers/${Uri.encodeComponent(itemKey)}';

String weekPath(String week) {
  if (!weekPattern.hasMatch(week)) throw ArgumentError.value(week, 'week', 'not an ISO week');
  return '/api/check-ins/$week';
}
