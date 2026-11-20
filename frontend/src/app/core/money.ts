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
