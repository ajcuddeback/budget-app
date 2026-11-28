import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/bills/domain/bill.dart';

/// The request bodies Bills & income sends, built exactly as the web's `bill-patch.ts` builds them.
///
/// Two rules carry the weight, and each has its own test:
///
/// - **A schedule sends only its own parameters.** The server refuses a parameter the kind has no
///   use for, so changing the kind clears what stopped applying and the body is built from the same
///   table the editor reads ([ScheduleKind.parameters]).
/// - **A patch sends only what changed**, `version` always. `categoryId` and `accountId` are
///   three-way (absent keeps, null clears); `amountVaries` and `tolerance` travel together.

/// What the schedule editor holds. A parameter is null until chosen.
final class ScheduleForm {
  const ScheduleForm({
    this.kind = ScheduleKind.monthly,
    this.dayOfMonth,
    this.month,
    this.dayOfWeek,
    this.anchorDate,
  });

  /// The server's schedule, for an edit.
  factory ScheduleForm.of(Schedule schedule) => ScheduleForm(
    kind: schedule.kind,
    dayOfMonth: schedule.dayOfMonth,
    month: schedule.month,
    dayOfWeek: schedule.dayOfWeek,
    anchorDate: schedule.anchorDate,
  );

  final ScheduleKind kind;
  final int? dayOfMonth;
  final int? month;
  final DayOfWeek? dayOfWeek;
  final CalendarDate? anchorDate;

  /// The same form pointed at [next], with **the parameters [next] does not use cleared**: a weekly
  /// bill does not have a day of the month that is merely hidden.
  ScheduleForm withKind(ScheduleKind next) => ScheduleForm(
    kind: next,
    dayOfMonth: next.parameters.contains(ScheduleParameter.dayOfMonth) ? dayOfMonth : null,
    month: next.parameters.contains(ScheduleParameter.month) ? month : null,
    dayOfWeek: next.parameters.contains(ScheduleParameter.dayOfWeek) ? dayOfWeek : null,
    anchorDate: next.parameters.contains(ScheduleParameter.anchorDate) ? anchorDate : null,
  );

  ScheduleForm copyWith({
    int? Function()? dayOfMonth,
    int? Function()? month,
    DayOfWeek? Function()? dayOfWeek,
    CalendarDate? Function()? anchorDate,
  }) => ScheduleForm(
    kind: kind,
    dayOfMonth: dayOfMonth == null ? this.dayOfMonth : dayOfMonth(),
    month: month == null ? this.month : month(),
    dayOfWeek: dayOfWeek == null ? this.dayOfWeek : dayOfWeek(),
    anchorDate: anchorDate == null ? this.anchorDate : anchorDate(),
  );

  /// The parameters the kind needs and the form does not have yet. Empty when it can be sent.
  Set<ScheduleParameter> get missing => {
    for (final p in kind.parameters)
      if (switch (p) {
        ScheduleParameter.dayOfMonth => dayOfMonth == null,
        ScheduleParameter.month => month == null,
        ScheduleParameter.dayOfWeek => dayOfWeek == null,
        ScheduleParameter.anchorDate => anchorDate == null,
      })
        p,
  };
}

/// The schedule as a body: the kind, and only the parameters that kind uses. A blank one is left
/// out rather than sent as zero, so a half-filled form fails the form's own check, not the server.
Map<String, Object?> scheduleBody(ScheduleForm form) {
  final uses = form.kind.parameters;
  return {
    'kind': form.kind.wire,
    if (uses.contains(ScheduleParameter.dayOfMonth) && form.dayOfMonth != null)
      'dayOfMonth': form.dayOfMonth,
    if (uses.contains(ScheduleParameter.month) && form.month != null) 'month': form.month,
    if (uses.contains(ScheduleParameter.dayOfWeek) && form.dayOfWeek != null)
      'dayOfWeek': form.dayOfWeek!.wire,
    if (uses.contains(ScheduleParameter.anchorDate) && form.anchorDate != null)
      'anchorDate': form.anchorDate!.iso,
  };
}

/// Whether [body] says the same schedule the server already has.
bool sameSchedule(Schedule schedule, Map<String, Object?> body) =>
    schedule.kind.wire == body['kind'] &&
    schedule.dayOfMonth == body['dayOfMonth'] &&
    schedule.month == body['month'] &&
    schedule.dayOfWeek?.wire == body['dayOfWeek'] &&
    schedule.anchorDate?.iso == body['anchorDate'];

/// What the bill sheet holds. Amounts are the text typed, already checked.
final class BillForm {
  const BillForm({
    required this.name,
    required this.expectedAmount,
    required this.schedule,
    this.categoryId,
    this.amountVaries = false,
    this.tolerance = '',
  });

  final String name;
  final String expectedAmount;
  final ScheduleForm schedule;
  final String? categoryId;
  final bool amountVaries;

  /// Only meaningful when [amountVaries].
  final String tolerance;
}

/// `POST /api/bills`. Optional fields are left out rather than sent empty; the amount varying and
/// its tolerance are sent together or not at all.
Map<String, Object?> createBillBody(BillForm form) => {
  'name': form.name.trim(),
  'expectedAmount': form.expectedAmount.trim(),
  'schedule': scheduleBody(form.schedule),
  if (form.categoryId != null) 'categoryId': form.categoryId,
  if (form.amountVaries) ...{'amountVaries': true, 'tolerance': form.tolerance.trim()},
};

/// `PATCH /api/bills/{id}`: the version and only what changed.
Map<String, Object?> billPatch(Bill original, BillForm form) {
  final patch = <String, Object?>{'version': original.version};
  final name = form.name.trim();
  if (name != original.name) patch['name'] = name;
  final amount = form.expectedAmount.trim();
  if (!sameAmount(amount, original.expectedAmount)) patch['expectedAmount'] = amount;
  final schedule = scheduleBody(form.schedule);
  if (!sameSchedule(original.schedule, schedule)) patch['schedule'] = schedule;
  if (form.categoryId != original.categoryId) patch['categoryId'] = form.categoryId;
  // Together in both directions: a tolerance without the flag is refused, and so is the flag
  // without a tolerance.
  final tolerance = form.tolerance.trim();
  final toleranceChanged = form.amountVaries && !sameAmount(tolerance, original.tolerance ?? '');
  if (form.amountVaries != original.amountVaries || toleranceChanged) {
    patch['amountVaries'] = form.amountVaries;
    if (form.amountVaries) patch['tolerance'] = tolerance;
  }
  return patch;
}

/// What the income sheet holds.
final class IncomeForm {
  const IncomeForm({
    required this.name,
    required this.amount,
    required this.schedule,
    this.accountId,
  });

  final String name;

  /// What arrives each time; the month's total is the server's arithmetic.
  final String amount;
  final ScheduleForm schedule;
  final String? accountId;
}

Map<String, Object?> createIncomeBody(IncomeForm form) => {
  'name': form.name.trim(),
  'amount': form.amount.trim(),
  'schedule': scheduleBody(form.schedule),
  if (form.accountId != null) 'accountId': form.accountId,
};

Map<String, Object?> incomePatch(IncomeSource original, IncomeForm form) {
  final patch = <String, Object?>{'version': original.version};
  final name = form.name.trim();
  if (name != original.name) patch['name'] = name;
  final amount = form.amount.trim();
  if (!sameAmount(amount, original.amount)) patch['amount'] = amount;
  final schedule = scheduleBody(form.schedule);
  if (!sameSchedule(original.schedule, schedule)) patch['schedule'] = schedule;
  if (form.accountId != original.accountId) patch['accountId'] = form.accountId;
  return patch;
}

/// Retiring or restoring: the version and the flag, nothing else.
Map<String, Object?> activePatch(int version, {required bool active}) => {
  'version': version,
  'active': active,
};

/// One-tap Skip and Mark as due: the status alone. **No version**: the URL is valid before anything
/// exists at it, and recording the same status twice is the same request twice.
Map<String, Object?> statusBody(OccurrenceStatus status) => {'status': status.wire};

/// One-tap Mark paid: what was budgeted, on [today], the phone's own date. No version, as
/// [statusBody]. The date is sent because the server's clock is UTC: on the morning of the 1st east
/// of UTC its today is still the month before (bills-and-income.md, The paid date).
Map<String, Object?> markPaidBody(CalendarDate today) => {
  'status': OccurrenceStatus.paid.wire,
  'paidDate': today.iso,
};

/// A payment that was not simply "what was budgeted, today". Both figures are left out when empty.
Map<String, Object?> paidBody({required String actualAmount, CalendarDate? paidDate}) => {
  'status': OccurrenceStatus.paid.wire,
  if (actualAmount.trim().isNotEmpty) 'actualAmount': actualAmount.trim(),
  if (paidDate != null) 'paidDate': paidDate.iso,
};

/// Whether a patch would change anything beyond the version it carries.
bool patchIsEmpty(Map<String, Object?> patch) => patch.length == 1;
