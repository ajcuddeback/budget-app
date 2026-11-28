import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { isAmount } from '../money';
import { isPeriod, periodAsDate } from '../period';
import { ENGLISH, MessageKey, MessageParams } from './messages';

const RIGHT_TO_LEFT = new Set(['ar', 'he', 'fa', 'ur', 'ps', 'sd', 'ug', 'yi', 'dv', 'ckb']);

/**
 * A derived rate, as the server writes one: a plain decimal, to six places (`TransferService`).
 *
 * Not {@link isAmount}, which allows the four places `NUMERIC(19,4)` stores — a rate is a division
 * carried further than any amount, and reusing the amount shape silently dropped every one of them.
 */
const RATE = /^-?[0-9]{1,15}(\.[0-9]{1,10})?$/;

/**
 * Narrows a rate to the string shape `Intl.NumberFormat` formats exactly, as {@link isAmount} does
 * for an amount: the proof the regex just produced, handed to `Intl` instead of a cast.
 */
function isRate(value: string): value is `${number}` {
  return RATE.test(value);
}

/**
 * Runtime internationalisation (ADR-0023).
 *
 * The locale is a per-user, run-time choice, so this is not Angular's build-time i18n, which would
 * ship one bundle per language. Language and formatting both follow {@link locale}: translated
 * text comes from a JSON file when one exists for the language and from English otherwise, while
 * dates, numbers and currency names always come from `Intl` for the full locale. Nothing here
 * formats a number or a date by hand.
 */
@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly http = inject(HttpClient);

  /** A BCP 47 tag. Always valid for `Intl`. */
  readonly locale = signal<string>(initialLocale());

  private readonly overlay = signal<Readonly<Record<string, string>>>({});

  readonly language = computed(() => this.locale().split('-')[0].toLowerCase());
  readonly direction = computed<'rtl' | 'ltr'>(() =>
    RIGHT_TO_LEFT.has(this.language()) ? 'rtl' : 'ltr',
  );

  constructor() {
    effect(() => {
      const root = document.documentElement;
      root.setAttribute('lang', this.locale());
      root.setAttribute('dir', this.direction());
    });
  }

  /**
   * Switch to a locale. `null` means "follow the platform" (the member has not chosen one).
   * Never rejects: a language with no translation file is a normal case, answered with English.
   */
  async use(requested: string | null | undefined): Promise<void> {
    const locale = canonicalise(requested) ?? platformLocale();
    const language = locale.split('-')[0].toLowerCase();
    this.locale.set(locale);
    if (language === 'en') {
      this.overlay.set({});
      return;
    }
    try {
      const body = await firstValueFrom(
        this.http.get<unknown>(`/i18n/${encodeURIComponent(language)}.json`),
      );
      // Only this language's strings are accepted, and only as strings. A file served by a
      // single-page-app fallback is HTML, not JSON, and lands in the catch below.
      if (this.locale() === locale) {
        this.overlay.set(onlyStrings(body));
      }
    } catch {
      if (this.locale() === locale) {
        this.overlay.set({});
      }
    }
  }

  /** Looks a message up and fills `{name}` placeholders. Falls back to English, then the key. */
  t(key: MessageKey | string, params?: MessageParams): string {
    const template = this.overlay()[key] ?? ENGLISH[key] ?? key;
    if (!params) {
      return template;
    }
    return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
      Object.hasOwn(params, name) ? String(params[name]) : whole,
    );
  }

  has(key: string): boolean {
    return Object.hasOwn(ENGLISH, key);
  }

  formatDateTime(iso: string | null | undefined): string {
    if (!iso) {
      return '';
    }
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    return new Intl.DateTimeFormat(this.locale(), {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(date);
  }

  formatDate(iso: string | null | undefined): string {
    if (!iso) {
      return '';
    }
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    return new Intl.DateTimeFormat(this.locale(), { dateStyle: 'medium' }).format(date);
  }

  /**
   * A period — `2026-08` — as the month it names: "August 2026", never "2026-08".
   *
   * A month label is not a date, so it is not {@link formatDate}'s job: the period has no day in
   * it and inventing one for display would show "1 August 2026" for a whole month. An empty
   * string for anything that is not a period, for the reason {@link formatMoney} gives.
   */
  formatMonth(period: string | null | undefined): string {
    if (!period || !isPeriod(period)) {
      return '';
    }
    return new Intl.DateTimeFormat(this.locale(), { month: 'long', year: 'numeric' }).format(
      periodAsDate(period),
    );
  }

  /** "August" — month `1`–`12` named in the active locale, for a month picker. */
  formatMonthName(month: number): string {
    if (!Number.isInteger(month) || month < 1 || month > 12) {
      return '';
    }
    return new Intl.DateTimeFormat(this.locale(), { month: 'long' }).format(
      new Date(2026, month - 1, 1),
    );
  }

  /**
   * "15 August" — a day in a month, with no year, in the active locale's own order.
   *
   * A yearly bill falls on a day of a month and in no particular year, so the year is absent
   * rather than set to this one. The order is `Intl`'s, never a template: "August 15" and
   * "15 August" are both right, in different places.
   */
  formatMonthDay(month: number, day: number): string {
    if (!Number.isInteger(month) || month < 1 || month > 12) {
      return '';
    }
    return new Intl.DateTimeFormat(this.locale(), { month: 'long', day: 'numeric' }).format(
      new Date(2026, month - 1, clampDay(month, day)),
    );
  }

  /**
   * "Wednesday" — the ISO weekday `1`–`7`, Monday first, named in the active locale.
   *
   * Taken from `Intl` rather than from the message catalogue: seven names per language that every
   * calendar already knows is seven more strings for a translator to get wrong.
   */
  formatWeekday(isoDay: number): string {
    if (!Number.isInteger(isoDay) || isoDay < 1 || isoDay > 7) {
      return '';
    }
    // 2024-01-01 was a Monday, so the offset is the ISO number itself.
    return new Intl.DateTimeFormat(this.locale(), { weekday: 'long' }).format(
      new Date(2024, 0, isoDay),
    );
  }

  /**
   * An amount in its own currency, for the active locale.
   *
   * **The string the API sent is handed straight to `Intl`** (ADR-0006). `Intl.NumberFormat`
   * accepts a decimal string and formats its digits exactly; parsing it to a `number` first would
   * round every amount a double cannot hold, and the one that gets rounded will be somebody's
   * money. Nothing in this app calls `parseFloat` on an amount.
   *
   * An empty string for anything that is not an amount: a figure we cannot trust is better absent
   * than approximated.
   */
  formatMoney(amount: string | null | undefined, currency: string): string {
    const value = (amount ?? '').trim();
    if (!isAmount(value)) {
      return '';
    }
    try {
      return new Intl.NumberFormat(this.locale(), { style: 'currency', currency }).format(value);
    } catch {
      // A code `Intl` does not know. The figure and the code are still true and still readable.
      return `${value} ${currency}`;
    }
  }

  /**
   * A **derived** rate, formatted for the active locale.
   *
   * Separate from {@link formatMoney} because a rate is not money: it has no currency, it carries
   * more places than an amount does, and nothing in this app reads one back (ADR-0022). The string
   * the server sent is handed to `Intl` exactly as an amount is — a rate shown beside somebody's
   * transfer is still arithmetic about their money, and `parseFloat` has no business near it.
   *
   * An empty string for anything that is not a plain decimal, for the reason {@link formatMoney}
   * gives.
   */
  formatRate(rate: string | null | undefined): string {
    const value = (rate ?? '').trim();
    if (!isRate(value)) {
      return '';
    }
    try {
      return new Intl.NumberFormat(this.locale(), { maximumFractionDigits: 6 }).format(value);
    } catch {
      // No locale `Intl` will accept. The digits are still true and still readable.
      return value;
    }
  }

  /**
   * A server-computed **ratio** — `"0.1600"` — as a whole percentage with no sign: "16%".
   *
   * The string is handed to `Intl` exactly as an amount is, and `Intl` does the multiplication by
   * a hundred on the decimal, so nothing here parses the ratio. The sign is dropped on purpose
   * (`signDisplay: 'never'`): the page says "up" or "down" in words, and "down −22%" would say it
   * twice. An empty string for anything that is not a plain decimal.
   */
  formatPercent(ratio: string | null | undefined): string {
    const value = (ratio ?? '').trim();
    if (!isRate(value)) {
      return '';
    }
    try {
      return new Intl.NumberFormat(this.locale(), {
        style: 'percent',
        maximumFractionDigits: 0,
        signDisplay: 'never',
      }).format(value);
    } catch {
      return value;
    }
  }

  /** `2026-08` → "Aug 2026": a chart's end label, where the long form does not fit. */
  formatMonthShort(period: string | null | undefined): string {
    if (!period || !isPeriod(period)) {
      return '';
    }
    return new Intl.DateTimeFormat(this.locale(), { month: 'short', year: 'numeric' }).format(
      periodAsDate(period),
    );
  }

  /**
   * Two periods as one range, "Sep 2025 – Aug 2026", in the locale's own range pattern.
   *
   * `formatRange` rather than two dates and a dash: the canvas's "Sep 25 → Aug 26" reads as two
   * days of the month, and the separator and the order are the locale's to choose.
   */
  formatMonthRange(from: string | null | undefined, to: string | null | undefined): string {
    if (!from || !to || !isPeriod(from) || !isPeriod(to)) {
      return '';
    }
    return new Intl.DateTimeFormat(this.locale(), { month: 'short', year: 'numeric' }).formatRange(
      periodAsDate(from),
      periodAsDate(to),
    );
  }

  /** "US Dollar", "Dollar américain" — the currency's name in the active locale. */
  currencyName(code: string): string {
    try {
      return new Intl.DisplayNames(this.locale(), { type: 'currency' }).of(code) ?? code;
    } catch {
      return code;
    }
  }

  /** A locale's own name for itself ("Deutsch"), so a speaker can find theirs in any language. */
  localeName(tag: string): string {
    try {
      return new Intl.DisplayNames(tag, { type: 'language' }).of(tag) ?? tag;
    } catch {
      return tag;
    }
  }

  /** "5 minutes" for a rate-limit wait, from the seconds the server reports. */
  duration(totalSeconds: number): string {
    const seconds = Math.max(1, Math.ceil(totalSeconds));
    if (seconds < 60) {
      return this.t('duration.seconds', { count: seconds });
    }
    const minutes = Math.ceil(seconds / 60);
    if (minutes < 60) {
      return minutes === 1
        ? this.t('duration.minute')
        : this.t('duration.minutes', { count: minutes });
    }
    const hours = Math.ceil(minutes / 60);
    return hours === 1 ? this.t('duration.hour') : this.t('duration.hours', { count: hours });
  }
}

/** The last day of a month, so a bill on the 31st does not display as the 1st of the next. */
function clampDay(month: number, day: number): number {
  const last = new Date(2026, month, 0).getDate();
  return Math.min(Math.max(Number.isInteger(day) ? day : 1, 1), last);
}

function platformLocale(): string {
  return canonicalise(globalThis.navigator?.language) ?? 'en';
}

function initialLocale(): string {
  return platformLocale();
}

function canonicalise(tag: string | null | undefined): string | null {
  if (!tag) {
    return null;
  }
  try {
    return Intl.getCanonicalLocales(tag)[0] ?? null;
  } catch {
    return null;
  }
}

function onlyStrings(body: unknown): Record<string, string> {
  const clean: Record<string, string> = {};
  if (typeof body === 'object' && body !== null && !Array.isArray(body)) {
    for (const [key, value] of Object.entries(body)) {
      if (typeof value === 'string') {
        clean[key] = value;
      }
    }
  }
  return clean;
}
