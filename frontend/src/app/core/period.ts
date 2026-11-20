/**
 * Period helpers. A period is a LABEL — `2026-08` — not a date range (ADR-0031).
 *
 * Everything here is string arithmetic on `YYYY-MM`. Going through `Date` would drag a timezone
 * into a question that has none: "which month am I budgeting" is a calendar question, and a
 * household near a date line should not get a different answer from the same click.
 *
 * In `core/` rather than in a feature because three screens now ask the same question — envelopes,
 * bills and income all list a month at a time — and a second copy of month arithmetic is a second
 * place for December to be wrong.
 */

/** The current month, in the viewer's own calendar. */
export function currentPeriod(now: Date = new Date()): string {
  return `${now.getFullYear()}-${`${now.getMonth() + 1}`.padStart(2, '0')}`;
}

/** The month before or after, by `step` months. */
export function shiftPeriod(period: string, step: number): string {
  const [year, month] = period.split('-').map(Number);
  // Months counted from zero so the arithmetic wraps without a special case for December.
  const total = year * 12 + (month - 1) + step;
  const shiftedYear = Math.floor(total / 12);
  const shiftedMonth = total - shiftedYear * 12 + 1;
  return `${shiftedYear}-${`${shiftedMonth}`.padStart(2, '0')}`;
}

/** `2026-08` → a `Date` on the first of that month, for formatting through the i18n pipes. */
export function periodAsDate(period: string): Date {
  const [year, month] = period.split('-').map(Number);
  return new Date(year, month - 1, 1);
}

/** Whether a period string is the shape the server accepts. */
export function isPeriod(value: string): boolean {
  return /^\d{4}-(0[1-9]|1[0-2])$/.test(value);
}
