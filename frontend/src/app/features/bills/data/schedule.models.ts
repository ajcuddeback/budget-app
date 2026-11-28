import { MessageKey } from '../../../core/i18n/messages';

/**
 * How often something recurs — a **small closed set, not a recurrence rule** (ADR-0032).
 *
 * Bills and income schedule the same way, so one set of shapes serves both: money leaving on a
 * date and money arriving on one are the same arithmetic. The server shares them too
 * (`common/schedule/web/ScheduleResponse`).
 */
export const SCHEDULE_KINDS = ['MONTHLY', 'YEARLY', 'WEEKLY', 'FORTNIGHTLY', 'IRREGULAR'] as const;

export type ScheduleKind = (typeof SCHEDULE_KINDS)[number];

/** ISO order, Monday first — the order the weekday picker shows and `formatWeekday` numbers. */
export const DAYS_OF_WEEK = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const;

export type DayOfWeek = (typeof DAYS_OF_WEEK)[number];

export const MONTHS_OF_YEAR: readonly number[] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];

/** Day of the month a monthly or yearly schedule may name. 29–31 are clamped by the server. */
export const DAYS_OF_MONTH: readonly number[] = Array.from({ length: 31 }, (_, index) => index + 1);

const KIND_KEY: Readonly<Record<ScheduleKind, MessageKey>> = {
  MONTHLY: 'bills.kind.MONTHLY',
  YEARLY: 'bills.kind.YEARLY',
  WEEKLY: 'bills.kind.WEEKLY',
  FORTNIGHTLY: 'bills.kind.FORTNIGHTLY',
  IRREGULAR: 'bills.kind.IRREGULAR',
};

const SAID_KEY: Readonly<Record<ScheduleKind, MessageKey>> = {
  MONTHLY: 'bills.said.MONTHLY',
  YEARLY: 'bills.said.YEARLY',
  WEEKLY: 'bills.said.WEEKLY',
  FORTNIGHTLY: 'bills.said.FORTNIGHTLY',
  IRREGULAR: 'bills.said.IRREGULAR',
};

export function isScheduleKind(value: string): value is ScheduleKind {
  return (SCHEDULE_KINDS as readonly string[]).includes(value);
}

export function isDayOfWeek(value: string): value is DayOfWeek {
  return (DAYS_OF_WEEK as readonly string[]).includes(value);
}

/**
 * The name of a kind, typed so a template cannot ask for a key that does not exist.
 *
 * A kind this client has never heard of is a newer server, not a bug: it gets a neutral word
 * rather than a `SCREAMING_SNAKE` code.
 */
export function scheduleKindKey(kind: string): MessageKey {
  return isScheduleKind(kind) ? KIND_KEY[kind] : 'bills.kind.unknown';
}

/** The sentence that describes a schedule, before its parameters are filled in. */
export function scheduleSaidKey(kind: string): MessageKey {
  return isScheduleKind(kind) ? SAID_KEY[kind] : 'bills.kind.unknown';
}

/** `'WEDNESDAY'` → `3`, the ISO weekday number `I18nService.formatWeekday` takes. */
export function isoWeekday(day: DayOfWeek): number {
  return DAYS_OF_WEEK.indexOf(day) + 1;
}

/**
 * The parameters each kind uses — **and no others**.
 *
 * The server refuses a parameter the kind has no use for rather than ignoring it, because
 * ignoring it loses an edit (`ScheduleRequest.refuse`). So this table is what the form shows and
 * what the request carries; both read it, so they cannot disagree.
 */
export const SCHEDULE_PARAMETERS: Readonly<Record<ScheduleKind, readonly ScheduleParameter[]>> = {
  MONTHLY: ['dayOfMonth'],
  YEARLY: ['month', 'dayOfMonth'],
  WEEKLY: ['dayOfWeek'],
  FORTNIGHTLY: ['dayOfWeek', 'anchorDate'],
  IRREGULAR: [],
};

export type ScheduleParameter = 'dayOfMonth' | 'month' | 'dayOfWeek' | 'anchorDate';

export function usesParameter(kind: ScheduleKind, parameter: ScheduleParameter): boolean {
  return SCHEDULE_PARAMETERS[kind].includes(parameter);
}

/** A schedule as the server reports one: the kind, and only the parameters that kind uses. */
export interface Schedule {
  kind: ScheduleKind;
  dayOfMonth: number | null;
  month: number | null;
  dayOfWeek: DayOfWeek | null;
  /** `YYYY-MM-DD`. The date a fortnightly schedule is counted from. */
  anchorDate: string | null;
}

/**
 * A schedule as a request body carries one.
 *
 * Every parameter is optional and a kind sends only its own: an absent property and a `null` are
 * the same thing to the server, but a *present* property the kind does not use is a `400`.
 */
export interface ScheduleRequest {
  kind: ScheduleKind;
  dayOfMonth?: number;
  month?: number;
  dayOfWeek?: DayOfWeek;
  anchorDate?: string;
}
