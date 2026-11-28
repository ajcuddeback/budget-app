import { currentDate, currentPeriod, isPeriod, periodAsDate, shiftPeriod } from './period';

describe('period helpers', () => {
  it('reads the current month from the viewer’s own calendar', () => {
    expect(currentPeriod(new Date(2026, 7, 20))).toBe('2026-08');
    // Zero-padded, because the server matches on the string.
    expect(currentPeriod(new Date(2026, 0, 1))).toBe('2026-01');
  });

  it('reads today from the viewer’s own calendar, zero-padded', () => {
    expect(currentDate(new Date(2026, 8, 1, 9, 0))).toBe('2026-09-01');
    expect(currentDate(new Date(2026, 11, 31, 23, 59))).toBe('2026-12-31');
  });

  it('gives the local date, not the UTC one, just after local midnight', () => {
    // 00:30 on the 1st where the viewer is. Anywhere east of UTC this instant is still the 31st in
    // UTC, which `toISOString()` would answer. The household's day is the 1st.
    const justAfterMidnight = new Date(2026, 8, 1, 0, 30);
    expect(currentDate(justAfterMidnight)).toBe('2026-09-01');
    expect(currentDate(justAfterMidnight).slice(0, 7)).toBe(currentPeriod(justAfterMidnight));
  });

  it('steps forward and back across a year boundary', () => {
    expect(shiftPeriod('2026-08', 1)).toBe('2026-09');
    expect(shiftPeriod('2026-12', 1)).toBe('2027-01');
    expect(shiftPeriod('2026-01', -1)).toBe('2025-12');
    expect(shiftPeriod('2026-08', -14)).toBe('2025-06');
  });

  it('does the arithmetic on strings, so no timezone can change the answer', () => {
    // "Which month am I budgeting" is a calendar question. Going through Date would let a
    // household near a date line get a different answer from the same click.
    expect(shiftPeriod('2026-03', 1)).toBe('2026-04');
    expect(shiftPeriod('2026-10', 1)).toBe('2026-11');
  });

  it('turns a period into the first of that month for formatting', () => {
    const date = periodAsDate('2026-08');

    expect(date.getFullYear()).toBe(2026);
    expect(date.getMonth()).toBe(7);
    expect(date.getDate()).toBe(1);
  });

  it('recognises the shape the server accepts and rejects the rest', () => {
    expect(isPeriod('2026-08')).toBe(true);
    expect(isPeriod('2026-13')).toBe(false);
    expect(isPeriod('2026-00')).toBe(false);
    expect(isPeriod('2026-8')).toBe(false);
    expect(isPeriod('2026-08-01')).toBe(false);
  });
});
