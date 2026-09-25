import { Injectable, effect, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'budget-owl.theme';

/**
 * Dark mode is a requirement, not a later pass (non-negotiable #10).
 *
 * The chosen mode is stamped on the root element as `data-theme`, which the token sheet keys off.
 * With nothing chosen the page follows the operating system — so the three states are
 * light / dark / system, and system is the default.
 *
 * Preference is stored per browser. It is a display choice and never leaves the instance.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly mode = signal<ThemeMode>(this.initialMode());

  constructor() {
    effect(() => {
      const mode = this.mode();
      document.documentElement.setAttribute('data-theme', mode);
      // A private window or blocked site data makes this throw. A theme preference is not worth
      // breaking the page over.
      try {
        localStorage.setItem(STORAGE_KEY, mode);
      } catch {
        // Ignored on purpose.
      }
    });
  }

  toggle(): void {
    this.mode.update((mode) => (mode === 'dark' ? 'light' : 'dark'));
  }

  private initialMode(): ThemeMode {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored === 'light' || stored === 'dark') {
        return stored;
      }
    } catch {
      // Fall through to the system preference.
    }
    return globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
}
