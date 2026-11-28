import { sameAmount } from '../../../core/money';
import {
  Bill,
  CreateBillRequest,
  CreateIncomeRequest,
  IncomeSource,
  RecordStatusRequest,
  UpdateBillRequest,
  UpdateIncomeRequest,
} from './bills.models';
import {
  DayOfWeek,
  Schedule,
  ScheduleKind,
  ScheduleRequest,
  isDayOfWeek,
  usesParameter,
} from './schedule.models';

/**
 * What the schedule editor holds.
 *
 * Every parameter is a string, because every one of them is a `<select>` or an `<input>` and that
 * is what those give back. They are turned into the numbers and the enum the wire wants in one
 * place — {@link buildScheduleRequest} — rather than in a template.
 */
export interface ScheduleFormValue {
  kind: ScheduleKind;
  dayOfMonth: string;
  month: string;
  dayOfWeek: string;
  /** `YYYY-MM-DD`. */
  anchorDate: string;
}

/** What the bill dialog holds. `categoryId` of `''` means no category. */
export interface BillFormValue {
  name: string;
  expectedAmount: string;
  categoryId: string;
  amountVaries: boolean;
  /** Only meaningful when `amountVaries`; `''` otherwise. */
  tolerance: string;
  schedule: ScheduleFormValue;
}

/** What the income dialog holds. `accountId` of `''` means no account named. */
export interface IncomeFormValue {
  name: string;
  amount: string;
  accountId: string;
  schedule: ScheduleFormValue;
}

/**
 * What the "record a payment" sheet holds.
 *
 * `dueDate` is the occurrence being recorded against. It is chosen only for an `IRREGULAR` bill,
 * which projects nothing — there, recording against a date is what brings the occurrence into
 * being (ADR-0032). For every other bill the date comes from the row that was tapped.
 */
export interface OccurrenceFormValue {
  dueDate: string;
  actualAmount: string;
  paidDate: string;
}

const BLANK_SCHEDULE: Omit<ScheduleFormValue, 'kind'> = {
  dayOfMonth: '',
  month: '',
  dayOfWeek: '',
  anchorDate: '',
};

/** A schedule form for a brand new thing: monthly, which is what most bills are. */
export function newScheduleForm(): ScheduleFormValue {
  return { kind: 'MONTHLY', ...BLANK_SCHEDULE };
}

/** The server's schedule as the form holds it, for an edit. */
export function scheduleFormFrom(schedule: Schedule): ScheduleFormValue {
  return {
    kind: schedule.kind,
    dayOfMonth: schedule.dayOfMonth === null ? '' : String(schedule.dayOfMonth),
    month: schedule.month === null ? '' : String(schedule.month),
    dayOfWeek: schedule.dayOfWeek ?? '',
    anchorDate: schedule.anchorDate ?? '',
  };
}

/**
 * The same form with the parameters the new kind does not use **cleared**.
 *
 * Sending a parameter a kind has no use for is a `400` — the server refuses it rather than
 * ignoring it, because ignoring it loses an edit. So changing the kind empties the fields that
 * have just stopped applying, which is also the honest thing to show: a weekly bill does not have
 * a day of the month that is merely hidden.
 */
export function clearUnusedParameters(
  form: ScheduleFormValue,
  kind: ScheduleKind,
): ScheduleFormValue {
  return {
    kind,
    dayOfMonth: usesParameter(kind, 'dayOfMonth') ? form.dayOfMonth : '',
    month: usesParameter(kind, 'month') ? form.month : '',
    dayOfWeek: usesParameter(kind, 'dayOfWeek') ? form.dayOfWeek : '',
    anchorDate: usesParameter(kind, 'anchorDate') ? form.anchorDate : '',
  };
}

/**
 * The request body for a schedule: the kind, and **only** the parameters that kind uses.
 *
 * Belt and braces with {@link clearUnusedParameters}: the form clears what stopped applying, and
 * this refuses to send it even if something was left behind. A blank parameter is omitted rather
 * than sent as `0` or `''`, so a half-filled form fails the form's own validation instead of
 * reaching the server as a different schedule.
 */
export function buildScheduleRequest(form: ScheduleFormValue): ScheduleRequest {
  const request: ScheduleRequest = { kind: form.kind };
  if (usesParameter(form.kind, 'dayOfMonth') && form.dayOfMonth !== '') {
    request.dayOfMonth = Number(form.dayOfMonth);
  }
  if (usesParameter(form.kind, 'month') && form.month !== '') {
    request.month = Number(form.month);
  }
  if (usesParameter(form.kind, 'dayOfWeek') && isDayOfWeek(form.dayOfWeek)) {
    request.dayOfWeek = form.dayOfWeek;
  }
  if (usesParameter(form.kind, 'anchorDate') && form.anchorDate !== '') {
    request.anchorDate = form.anchorDate;
  }
  return request;
}

/** Whether a built request says the same thing the server already has. */
export function sameSchedule(schedule: Schedule, request: ScheduleRequest): boolean {
  return (
    schedule.kind === request.kind &&
    (schedule.dayOfMonth ?? null) === (request.dayOfMonth ?? null) &&
    (schedule.month ?? null) === (request.month ?? null) &&
    (schedule.dayOfWeek ?? null) === ((request.dayOfWeek as DayOfWeek | undefined) ?? null) &&
    (schedule.anchorDate ?? null) === (request.anchorDate ?? null)
  );
}

export function buildBillCreate(form: BillFormValue): CreateBillRequest {
  const request: CreateBillRequest = {
    name: form.name.trim(),
    expectedAmount: form.expectedAmount.trim(),
    schedule: buildScheduleRequest(form.schedule),
  };
  if (form.categoryId !== '') {
    request.categoryId = form.categoryId;
  }
  // Sent together or not at all: a bill whose amount varies needs a tolerance, and a fixed one
  // refuses to carry a number that would mean nothing.
  if (form.amountVaries) {
    request.amountVaries = true;
    request.tolerance = form.tolerance.trim();
  }
  return request;
}

/**
 * The patch for an edit: only what changed.
 *
 * `categoryId` is three-way, so the absent case has to be built by *not adding the key*. A client
 * that sent the whole form would clear the category of every bill it edited.
 */
export function buildBillPatch(original: Bill, form: BillFormValue): UpdateBillRequest {
  const patch: UpdateBillRequest = { version: original.version };

  const name = form.name.trim();
  if (name !== original.name) {
    patch.name = name;
  }

  const amount = form.expectedAmount.trim();
  if (!sameAmount(amount, original.expectedAmount)) {
    patch.expectedAmount = amount;
  }

  const schedule = buildScheduleRequest(form.schedule);
  if (!sameSchedule(original.schedule, schedule)) {
    patch.schedule = schedule;
  }

  const category = form.categoryId === '' ? null : form.categoryId;
  if (category !== original.categoryId) {
    patch.categoryId = category;
  }

  // `amountVaries` and `tolerance` move together in both directions: the server refuses a
  // tolerance without the flag, and a flag without a tolerance.
  const tolerance = form.tolerance.trim();
  const toleranceChanged = form.amountVaries && !sameAmount(tolerance, original.tolerance ?? '');
  if (form.amountVaries !== original.amountVaries || toleranceChanged) {
    patch.amountVaries = form.amountVaries;
    if (form.amountVaries) {
      patch.tolerance = tolerance;
    }
  }

  return patch;
}

export function buildIncomeCreate(form: IncomeFormValue): CreateIncomeRequest {
  const request: CreateIncomeRequest = {
    name: form.name.trim(),
    amount: form.amount.trim(),
    schedule: buildScheduleRequest(form.schedule),
  };
  if (form.accountId !== '') {
    request.accountId = form.accountId;
  }
  return request;
}

export function buildIncomePatch(
  original: IncomeSource,
  form: IncomeFormValue,
): UpdateIncomeRequest {
  const patch: UpdateIncomeRequest = { version: original.version };

  const name = form.name.trim();
  if (name !== original.name) {
    patch.name = name;
  }

  const amount = form.amount.trim();
  if (!sameAmount(amount, original.amount)) {
    patch.amount = amount;
  }

  const schedule = buildScheduleRequest(form.schedule);
  if (!sameSchedule(original.schedule, schedule)) {
    patch.schedule = schedule;
  }

  const account = form.accountId === '' ? null : form.accountId;
  if (account !== original.accountId) {
    patch.accountId = account;
  }

  return patch;
}

/**
 * Recording a payment that was not what was budgeted.
 *
 * Both fields are optional to the server and are omitted when empty rather than sent blank: the
 * one-tap case is "I paid it", which means what was budgeted, today. An actual amount is a string
 * from the keystroke to the database (ADR-0006) — nothing here parses it.
 */
export function buildPaidRecord(form: OccurrenceFormValue): RecordStatusRequest {
  const request: RecordStatusRequest = { status: 'PAID' };
  const actual = form.actualAmount.trim();
  if (actual !== '') {
    request.actualAmount = actual;
  }
  if (form.paidDate !== '') {
    request.paidDate = form.paidDate;
  }
  return request;
}

/** Whether a patch would change anything beyond the version it has to carry. */
export function patchIsEmpty(patch: { version: number }): boolean {
  return Object.keys(patch).length === 1;
}
