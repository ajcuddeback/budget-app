import { test as base, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import path from 'node:path';
import { OUT_DIR, record } from './artifacts.js';

/**
 * Everything a UI check needs, with the tedious parts already wired:
 *
 *   shot(label)   screenshot the page and register it for agent review
 *   a11y()        run axe-core and record violations as findings
 *   noErrors()    assert nothing was logged to console.error, no page crash,
 *                 and no request failed
 *   noOverflow()  assert the page does not scroll horizontally
 *
 * Console and network capture start before the first navigation, so a failure during
 * initial load is caught rather than missed.
 */

type UiFixtures = {
  ui: {
    shot: (label: string) => Promise<string>;
    a11y: (label?: string) => Promise<number>;
    noErrors: () => void;
    noOverflow: () => Promise<void>;
    consoleErrors: string[];
    failedRequests: string[];
  };
};

export const test = base.extend<UiFixtures>({
  ui: async ({ page }, use, testInfo) => {
    const consoleErrors: string[] = [];
    const failedRequests: string[] = [];

    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
    page.on('pageerror', (err) => consoleErrors.push(`Uncaught: ${err.message}`));
    page.on('requestfailed', (req) => {
      const failure = req.failure()?.errorText ?? 'failed';
      // Aborted requests are normal during navigation teardown.
      if (failure !== 'net::ERR_ABORTED') failedRequests.push(`${req.method()} ${req.url()} — ${failure}`);
    });
    page.on('response', (res) => {
      if (res.status() >= 500) failedRequests.push(`${res.status()} ${res.url()}`);
    });

    const specName = path.basename(testInfo.file).replace(/\.spec\.ts$/, '');
    const vpLabel = (): string => {
      const v = page.viewportSize();
      return v ? `${v.width}x${v.height}` : 'unknown';
    };
    // data: URLs are enormous and unreadable in a report; name them instead.
    const here = (): string => (page.url().startsWith('data:') ? '(inline fixture)' : page.url());

    const shot = async (label: string): Promise<string> => {
      const vp = vpLabel();
      const safe = `${specName}--${label}--${vp}`.replace(/[^a-zA-Z0-9.-]+/g, '_');
      const file = path.join(OUT_DIR, `${safe}.png`);
      await page.screenshot({ path: file, fullPage: true });
      record({ kind: 'screenshot', file, spec: specName, label, url: here(), viewport: vp });
      return file;
    };

    const a11y = async (label = 'page'): Promise<number> => {
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();

      for (const v of results.violations) {
        record({
          kind: 'finding',
          spec: specName,
          severity: (v.impact as never) ?? 'moderate',
          category: 'a11y',
          summary: `${v.id}: ${v.help}`,
          detail: v.helpUrl,
          where: `${label} — ${v.nodes.length} element(s): ${v.nodes[0]?.target.join(' ')}`,
          viewport: vpLabel(),
        });
      }
      return results.violations.length;
    };

    const noErrors = (): void => {
      for (const e of consoleErrors) {
        record({ kind: 'finding', spec: specName, severity: 'serious', category: 'console', summary: e, viewport: vpLabel(), where: here() });
      }
      for (const r of failedRequests) {
        record({ kind: 'finding', spec: specName, severity: 'serious', category: 'network', summary: r, viewport: vpLabel(), where: here() });
      }
      expect(consoleErrors, `console errors on ${page.url()}`).toEqual([]);
      expect(failedRequests, `failed requests on ${page.url()}`).toEqual([]);
    };

    const noOverflow = async (): Promise<void> => {
      const overflow = await page.evaluate(() => {
        const d = document.documentElement;
        return d.scrollWidth - d.clientWidth;
      });
      if (overflow > 0) {
        record({
          kind: 'finding',
          spec: specName,
          severity: 'serious',
          category: 'layout',
          summary: `Horizontal overflow of ${overflow}px`,
          where: here(),
          viewport: vpLabel(),
        });
      }
      expect(overflow, `horizontal overflow on ${page.url()}`).toBeLessThanOrEqual(0);
    };

    await use({ shot, a11y, noErrors, noOverflow, consoleErrors, failedRequests });
  },
});

export { expect, type Page };
