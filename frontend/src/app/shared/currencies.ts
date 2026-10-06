/**
 * ISO 4217 codes the browser knows about. `Intl` is the source rather than a list we would have to
 * maintain; the server re-validates against its own currency table, so a code this offers that the
 * server refuses comes back as an ordinary validation error.
 */
const FALLBACK = ['USD', 'EUR', 'GBP', 'JPY', 'CAD', 'AUD', 'CHF', 'CNY', 'INR', 'MXN', 'BRL'];

export function currencyCodes(): readonly string[] {
  try {
    const codes = Intl.supportedValuesOf('currency');
    return codes.length > 0 ? codes : FALLBACK;
  } catch {
    return FALLBACK;
  }
}
