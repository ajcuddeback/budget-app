import fs from 'node:fs';

/**
 * Resolve a Chromium executable.
 *
 * On a normal dev machine `npx playwright install chromium` puts the browser where Playwright
 * expects it, so we return undefined and let Playwright use its own. In pre-baked containers
 * (CI images, Claude Code web sessions) a Chromium is already present but its revision may not
 * match the pinned @playwright/test version — there we point at it explicitly rather than
 * downloading a second copy.
 *
 * Override with UI_CHROMIUM_PATH when neither applies.
 */
export function chromiumExecutable(): string | undefined {
  if (process.env.UI_CHROMIUM_PATH) return process.env.UI_CHROMIUM_PATH;

  const root = process.env.PLAYWRIGHT_BROWSERS_PATH;
  if (!root) return undefined;

  const candidates = [
    `${root}/chromium`,
    `${root}/chromium/chrome-linux/chrome`,
    `${root}/chromium-1194/chrome-linux/chrome`,
  ];

  for (const candidate of candidates) {
    try {
      // statSync alone: existsSync-then-stat is a check-then-use race, and stat already
      // throws for a missing path.
      if (fs.statSync(candidate).isFile()) return candidate;
    } catch {
      /* not present, or not readable — keep looking */
    }
  }
  return undefined;
}
