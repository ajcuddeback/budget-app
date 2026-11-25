import { compareAmounts, sameAmount } from '../../../core/money';
import { currentPeriod, shiftPeriod } from '../../../core/period';
import { RangeMonths, ReportName } from './reports.models';

/**
 * The small decisions the Insights page makes from the server's figures. **None of them is money
 * arithmetic**: ranges are month labels, and a change's direction is an ordering of two strings
 * through `core/money.ts`, never a parse (docs/features/reporting.md, "The direction of change").
 */

/**
 * The last `months` months, ending with the current one, **by the device's own date**.
 *
 * The device is the only party that knows the member's local "today", which is why the server
 * supplies no default (decision 10).
 */
export function lastMonths(
  months: RangeMonths,
  today: Date = new Date(),
): {
  from: string;
  to: string;
} {
  const to = currentPeriod(today);
  return { from: shiftPeriod(to, -(months - 1)), to };
}

/**
 * The month "Where {month} went" opens on: the last complete one. A half-finished month set
 * against a whole one makes every category look like it fell (decision 6).
 */
export function lastCompleteMonth(today: Date = new Date()): string {
  return shiftPeriod(currentPeriod(today), -1);
}

/** How a category's spending moved, said in words as well as in colour. */
export type Direction = 'up' | 'down' | 'flat' | 'new' | 'none';

/**
 * The direction of a server-computed `change`.
 *
 * - exactly zero is **flat**, compared as a string, never parsed;
 * - no change against nothing, with spending now, is **new**;
 * - no change and no spending now (a refund-heavy row against an empty month) says nothing.
 */
export function directionOf(change: string | null, spending: string): Direction {
  if (change === null) {
    return (compareAmounts(spending, '0') ?? 0) > 0 ? 'new' : 'none';
  }
  if (sameAmount(change, '0')) {
    return 'flat';
  }
  return (compareAmounts(change, '0') ?? 0) > 0 ? 'up' : 'down';
}

/**
 * True when a non-zero change would round to "0%" as a whole percentage, so the page can say
 * "less than 1%" instead of the contradiction "up 0%".
 */
export function isUnderOnePercent(change: string): boolean {
  return (
    !sameAmount(change, '0') &&
    (compareAmounts(change, '0.005') ?? 1) < 0 &&
    (compareAmounts(change, '-0.005') ?? -1) > 0
  );
}

/** The file name the server would give, built from the report and the two periods only. */
export function csvFileName(report: ReportName, from: string, to: string): string {
  return `budget-owl-${report}-${from}-${to}.csv`;
}

/** How many category rows show before "Show all". The canvas draws four; six fit a phone. */
export const VISIBLE_CATEGORIES = 6;

/**
 * The proportion bar's segment tokens, in order (docs/features/reporting.md, "The proportion
 * bar"). The seventh is every row past the sixth. Colour carries nothing the rows do not say in
 * text, so the bar is `aria-hidden`.
 */
export const SEGMENT_TONES = ['s1', 's2', 's3', 's4', 's5', 's6', 'rest'] as const;

export type SegmentTone = (typeof SEGMENT_TONES)[number];

export function segmentTone(index: number): SegmentTone {
  return SEGMENT_TONES[Math.min(index, SEGMENT_TONES.length - 1)];
}
