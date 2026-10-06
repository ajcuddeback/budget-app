/**
 * Locales offered for formatting dates and numbers, and for the interface language where a
 * translation exists (ADR-0023). Names are never listed here: they come from `Intl` and are shown
 * in each locale's own language, so a speaker can find theirs whatever language the page is in.
 *
 * A short list of common regional variants, not a closed set — a member's saved locale outside it
 * is kept and shown. The server accepts any well-formed BCP 47 tag.
 */
export const LOCALE_CHOICES: readonly string[] = [
  'en-US',
  'en-GB',
  'de-DE',
  'fr-FR',
  'es-ES',
  'es-MX',
  'it-IT',
  'pt-BR',
  'pt-PT',
  'nl-NL',
  'sv-SE',
  'pl-PL',
  'ru-RU',
  'tr-TR',
  'ar-EG',
  'he-IL',
  'fa-IR',
  'hi-IN',
  'ja-JP',
  'ko-KR',
  'zh-CN',
];
