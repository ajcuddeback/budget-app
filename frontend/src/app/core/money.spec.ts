import {
  addAmounts,
  amountFraction,
  compareAmounts,
  isAmount,
  sameAmount,
  trimAmount,
} from './money';

/**
 * The point of every test in here is the same: **no amount is ever a `number`**.
 *
 * Each case below is one a double gets wrong. If somebody "simplifies" money.ts with `parseFloat`
 * and a `+`, these are what fail — which is the only reason they exist as separate tests rather
 * than one happy-path sum.
 */
describe('money', () => {
  it('adds the sum a double cannot', () => {
    // Number('0.1') + Number('0.2') === 0.30000000000000004.
    expect(addAmounts(['0.1', '0.2'])).toBe('0.3000');
    // Beyond 2^53: every digit survives, where a double would round the last four.
    expect(addAmounts(['99999999999999.9999', '0.0001'])).toBe('100000000000000.0000');
    expect(addAmounts(['12345678901234.5678', '0'])).toBe('12345678901234.5678');
  });

  it('keeps the sign, and treats zero as a figure rather than nothing', () => {
    expect(addAmounts(['-400.00', '-25.50'])).toBe('-425.5000');
    expect(addAmounts(['-400.00', '400'])).toBe('0.0000');
    expect(addAmounts(['0.00'])).toBe('0.0000');
    // An empty currency group sums to zero. "No accounts" is not "unknown".
    expect(addAmounts([])).toBe('0.0000');
  });

  it('refuses to guess at anything that is not an amount', () => {
    for (const nonsense of [
      '',
      ' 1.00',
      '1,00',
      '1.00 USD',
      '١٢٣٤',
      'NaN',
      'Infinity',
      '1e3',
      '+1',
      '1.23456',
    ]) {
      expect(isAmount(nonsense)).toBe(false);
      expect(addAmounts(['1.00', nonsense])).toBeNull();
    }
    expect(isAmount(null)).toBe(false);
    expect(isAmount(undefined)).toBe(false);
  });

  it('accepts the shapes the API and the form actually produce', () => {
    for (const amount of ['0', '-0', '0.0000', '-400', '-400.0000', '123456789012345.1234']) {
      expect(isAmount(amount)).toBe(true);
    }
  });

  it('compares amounts as money, not as text', () => {
    expect(sameAmount('-400', '-400.0000')).toBe(true);
    expect(sameAmount('1.5', '1.50')).toBe(true);
    expect(sameAmount('1.5', '1.51')).toBe(false);
    // Unreadable input is never "the same as" anything; the caller must notice.
    expect(sameAmount('1.5', 'nope')).toBe(false);
  });

  it('orders amounts exactly, including the pairs a double gets wrong', () => {
    expect(compareAmounts('3120.0000', '3900.0000')).toBe(-1);
    expect(compareAmounts('3900.0000', '3120.0000')).toBe(1);
    expect(compareAmounts('645', '645.0000')).toBe(0);
    expect(compareAmounts('-65.0000', '0')).toBe(-1);
    // 0.1 + 0.2 > 0.3 as doubles. As minor units it is exactly equal.
    expect(compareAmounts(addAmounts(['0.1', '0.2']) ?? '', '0.3')).toBe(0);
    expect(compareAmounts('1.00', 'nope')).toBeNull();
  });

  it('turns an amount into a ratio of the largest without ever parsing one as a figure', () => {
    // The chart's geometry. The ratio is a plain number; the money never is.
    expect(amountFraction('500.0000', '1000.0000')).toBe(0.5);
    expect(amountFraction('1000.0000', '1000.0000')).toBe(1);
    expect(amountFraction('0', '1000.0000')).toBe(0);
    expect(amountFraction('-20.0000', '1000.0000')).toBe(-0.02);
  });

  it('keeps a third exact to a millionth rather than to a float’s whim', () => {
    expect(amountFraction('1.0000', '3.0000')).toBeCloseTo(1 / 3, 6);
  });

  it('takes trailing zeros off a figure without changing it', () => {
    // The API reports at the server's scale; a text box somebody is about to edit should not.
    expect(trimAmount('445.0000')).toBe('445');
    expect(trimAmount('6180.5000')).toBe('6180.5');
    expect(trimAmount('0.1000')).toBe('0.1');
    expect(sameAmount(trimAmount('445.0000'), '445.0000')).toBe(true);
  });

  it('leaves alone anything with nothing to trim, and anything it cannot read', () => {
    expect(trimAmount('445')).toBe('445');
    expect(trimAmount('445.05')).toBe('445.05');
    expect(trimAmount('0.0000')).toBe('0');
    // Cosmetic only: it must never be the thing that loses a figure.
    expect(trimAmount('nonsense')).toBe('nonsense');
    expect(trimAmount('')).toBe('');
  });

  it('refuses to scale by nothing, so an unplottable point is a gap and not a zero', () => {
    expect(amountFraction('500.0000', '0')).toBeNull();
    expect(amountFraction('nope', '1000.0000')).toBeNull();
    expect(amountFraction('500.0000', 'nope')).toBeNull();
  });
});
