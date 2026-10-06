import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ENGLISH, MessageKey, MessageParams } from './messages';

const RIGHT_TO_LEFT = new Set(['ar', 'he', 'fa', 'ur', 'ps', 'sd', 'ug', 'yi', 'dv', 'ckb']);

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
    return new Intl.DateTimeFormat(this.locale(), { dateStyle: 'medium', timeStyle: 'short' }).format(
      date,
    );
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
      return minutes === 1 ? this.t('duration.minute') : this.t('duration.minutes', { count: minutes });
    }
    const hours = Math.ceil(minutes / 60);
    return hours === 1 ? this.t('duration.hour') : this.t('duration.hours', { count: hours });
  }
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
