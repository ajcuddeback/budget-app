/**
 * Decimal amounts, exactly, as strings.
 *
 * Amounts arrive from the API as strings and stay strings (ADR-0006). **Nothing here converts one
 * to a `number`**: `Number('0.1') + Number('0.2')` is `0.30000000000000004`, and the figure that
 * gets rounded will be somebody's money. Arithmetic is done on `bigint` minor units at the
 * server's scale, which is exact for every value `NUMERIC(19,4)` can hold.
 *
 * The only arithmetic the client is allowed to do is **display arithmetic**: a per-currency
 * subtotal of figures the server already computed (ADR-0022). A result from here is never sent to
 * the server as truth — the server computes, the client displays (docs/guides/angular-style.md).
 */

/** `NUMERIC(19,4)` — the scale the server stores and reports. */
const SCALE = 4;

/** The server's own shape for an amount: signed, at most 15 integer digits and 4 decimals. */
const AMOUNT = /^-?[0-9]{1,15}(\.[0-9]{1,4})?$/;

/**
 * A string of digits that is a decimal number — the shape `Intl.NumberFormat` will format without
 * a `number` ever existing. Narrowing to it is how {@link isAmount} hands that proof to `Intl`
 * instead of a cast.
 */
export type Amount = `${number}`;

/** True for a string the API or a form may legitimately carry as an amount. */
export function isAmount(value: string | null | undefined): value is Amount {
  return typeof value === 'string' && AMOUNT.test(value);
}

/**
 * The exact sum of a list of amounts, at the server's scale.
 *
 * `null` when any input is not an amount — a figure we cannot add is a visible state, never a
 * silent zero (ADR-0022). An empty list sums to zero, which is the right answer for an empty
 * currency group and is not the same thing as "unknown".
 */
export function addAmounts(amounts: readonly string[]): string | null {
  let total = 0n;
  for (const amount of amounts) {
    const minor = toMinorUnits(amount);
    if (minor === null) {
      return null;
    }
    total += minor;
  }
  return fromMinorUnits(total);
}

/**
 * Whether two amounts are the same figure, whatever their scale: `-400` and `-400.0000` are the
 * same money and a form that reports one as a change to the other would patch for no reason.
 */
export function sameAmount(left: string, right: string): boolean {
  const a = toMinorUnits(left);
  const b = toMinorUnits(right);
  return a !== null && b !== null && a === b;
}

/**
 * Which of two amounts is the larger: `-1`, `0` or `1`, and `null` when either is not an amount.
 *
 * Ordering, not arithmetic — it produces no figure, so nothing it returns can be mistaken for
 * money. It exists because "avalanche costs less in interest than snowball" is a comparison the
 * debt plan has to make, and the alternative on offer was `parseFloat`. Exact, because it compares
 * `bigint` minor units rather than two doubles that have each already lost something.
 */
export function compareAmounts(left: string, right: string): number | null {
  const a = toMinorUnits(left);
  const b = toMinorUnits(right);
  if (a === null || b === null) {
    return null;
  }
  return a === b ? 0 : a < b ? -1 : 1;
}

/**
 * `value ÷ largest` as a plain `number` between 0 and 1 — **geometry, never money**.
 *
 * The one place a figure legitimately becomes a `number`: an SVG needs coordinates, and a chart
 * cannot be drawn from decimal strings. What is converted is a *dimensionless ratio*, computed by
 * exact `bigint` division at a millionth of a unit; no amount is ever handed to a double, and the
 * figures the chart *states* are still the server's own strings through the `money` pipe. A
 * sixth-decimal rounding error in a ratio moves a line by a fraction of a pixel.
 *
 * `null` when either side is not an amount, or `largest` is zero — an unplottable point is a
 * visible gap, never a silent zero.
 */
export function amountFraction(value: string, largest: string): number | null {
  const numerator = toMinorUnits(value);
  const denominator = toMinorUnits(largest);
  if (numerator === null || denominator === null || denominator === 0n) {
    return null;
  }
  const precision = 1_000_000n;
  return Number((numerator * precision) / denominator) / Number(precision);
}

/**
 * `'445.0000'` → `'445'` — the same figure with its trailing zeros off, for a form field.
 *
 * **String surgery, never a parse.** The API reports every amount at the server's scale, which is
 * right on the wire and reads like a glitch in a text box somebody is about to edit. Dropping
 * trailing zeros changes no value: `sameAmount('445', '445.0000')` is true, and what is submitted
 * is a figure the household can see is theirs.
 *
 * It assumes nothing about how many decimals a currency has — it removes zeros and stops. Anything
 * that is not an amount is returned untouched, because this is cosmetic and must never be the
 * thing that loses a figure.
 */
export function trimAmount(amount: string): string {
  if (!isAmount(amount) || !amount.includes('.')) {
    return amount;
  }
  const trimmed = amount.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? amount : trimmed;
}

/** `'-400.0000'` → `-4000000n`. `null` for anything that is not an amount. */
function toMinorUnits(amount: string): bigint | null {
  if (!isAmount(amount)) {
    return null;
  }
  const negative = amount.startsWith('-');
  const digits = negative ? amount.slice(1) : amount;
  const [whole, fraction = ''] = digits.split('.');
  const scaled = BigInt(whole + fraction.padEnd(SCALE, '0'));
  return negative ? -scaled : scaled;
}

/** `-4000000n` → `'-400.0000'`. Always at the server's scale, so nothing is lost on the way out. */
function fromMinorUnits(value: bigint): string {
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(SCALE + 1, '0');
  const whole = digits.slice(0, digits.length - SCALE);
  const fraction = digits.slice(digits.length - SCALE);
  return `${negative ? '-' : ''}${whole}.${fraction}`;
}
