/**
 * The two reports, in the shape the API sends them (docs/features/reporting.md, API).
 *
 * **Every amount is a string at scale 4** (ADR-0006), and so is every ratio. Nothing here is a
 * `number`, and nothing on the page turns one into a number: figures are formatted with `Intl`
 * from the string, and `change` and `share` are the server's own.
 *
 * **Every figure sits inside an object that names its currency.** There is never a combined or a
 * converted figure (ADR-0022, ADR-0039).
 */

/** One month of one currency. `kept = income − spending + uncategorisedIn − uncategorisedOut`. */
export interface MonthFigures {
  readonly period: string;
  readonly income: string;
  /** Positive when money left. Negative, never clamped, when refunds beat spending. */
  readonly spending: string;
  readonly uncategorisedIn: string;
  /** Positive: the money that left with no category. */
  readonly uncategorisedOut: string;
  /** Signed. Negative means more left than arrived. */
  readonly kept: string;
  /** False for the current month and later ones, by the server's clock. */
  readonly complete: boolean;
}

/** The range's figures, summed by the server so the page never adds money up. */
export interface Totals {
  readonly income: string;
  readonly spending: string;
  readonly uncategorisedIn: string;
  readonly uncategorisedOut: string;
  readonly kept: string;
}

export interface BestMonth {
  readonly period: string;
  readonly kept: string;
}

export interface CurrencyIncomeAndSpending {
  readonly currency: string;
  /** Dense: every month in the range, zeros where nothing happened. */
  readonly months: readonly MonthFigures[];
  readonly totals: Totals;
  /** `null` when no month in the range is complete — never shown as zero. */
  readonly averageKept: string | null;
  readonly bestMonth: BestMonth | null;
  readonly completeMonths: number;
  readonly uncategorisedCount: number;
  /** Zero is the empty state: figures that net to zero are not "nothing recorded". */
  readonly transactionCount: number;
}

export interface IncomeAndSpending {
  readonly from: string;
  readonly to: string;
  readonly baseCurrency: string;
  /** The base currency first, always present; then the others in ISO code order. */
  readonly currencies: readonly CurrencyIncomeAndSpending[];
}

export interface CategorySpending {
  readonly categoryId: string;
  readonly name: string;
  readonly archived: boolean;
  readonly spending: string;
  readonly comparedSpending: string;
  /** `(spending − compared) ÷ |compared|` at scale 4, by the server. `null` against nothing. */
  readonly change: string | null;
  /** This row's fraction of the currency's positive spending. `null` for a non-positive row. */
  readonly share: string | null;
  /** What was filed on this category itself. Children plus `direct` equal `spending`. */
  readonly direct: string;
  readonly children: readonly CategorySpending[];
}

export interface CurrencySpending {
  readonly currency: string;
  readonly spending: string;
  readonly comparedSpending: string;
  /** Top-level rows, spending descending, then name, then id. */
  readonly categories: readonly CategorySpending[];
  readonly uncategorised: { readonly spending: string; readonly count: number };
}

export interface SpendingByCategory {
  readonly from: string;
  readonly to: string;
  readonly comparedFrom: string;
  readonly comparedTo: string;
  readonly complete: boolean;
  readonly baseCurrency: string;
  readonly currencies: readonly CurrencySpending[];
}

/** The three ranges the designs offer. The server accepts up to 24. */
export type RangeMonths = 12 | 6 | 3;

export const RANGES: readonly RangeMonths[] = [12, 6, 3];

/** Which report a CSV is of: the two resource names, which are also the file's name. */
export type ReportName = 'income-and-spending' | 'spending-by-category';
