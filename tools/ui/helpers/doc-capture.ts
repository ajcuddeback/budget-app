import { test as base, expect, type Locator, type Page } from '@playwright/test';
import { mockApi, type ApiCall } from './mock-api.js';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Capture screenshots for the CUSTOMER-FACING user guide.
 *
 * Deliberately different from the validation fixture in ui-test.ts:
 *
 *   validation shots        doc shots
 *   -----------------       ----------------------------------------
 *   gitignored artifacts    committed into userguide/images/
 *   random-ish labels       stable filenames a doc links to
 *   full page, both sizes   focused and annotated, desktop-first
 *   throwaway               go stale, so each records when it was taken
 *
 * A screenshot in a user guide is a promise about what the reader will see. When it lies,
 * the reader assumes they broke something. That is why every capture is stamped with a
 * commit and date, and why tools/userguide-check.sh flags shots older than the UI they show.
 *
 * The API is mocked automatically from tools/ui/fixtures/demo-data.ts before the first
 * navigation, so a capture run has no backend, no database, and no credentials — it cannot
 * read real records because there is nothing real to read (ADR-0013).
 */

const IMAGES_DIR = path.resolve(
  process.env.USERGUIDE_IMAGES ?? path.join(process.cwd(), '../../userguide/images'),
);
const PARTS_DIR = path.join(IMAGES_DIR, '.manifest-parts');
const MANIFEST = path.join(IMAGES_DIR, 'manifest.json');

export type DocShot = {
  name: string;
  file: string;
  guide: string;
  caption: string;
  capturedAt: string;
  commit: string;
  viewport: string;
};

function currentCommit(): string {
  try {
    return execSync('git rev-parse --short HEAD', { encoding: 'utf8' }).trim();
  } catch {
    return 'unknown';
  }
}

/**
 * One file per shot, merged into the manifest when the run finishes.
 *
 * <b>Not a read-modify-write of the shared manifest.</b> Playwright runs `fullyParallel` across
 * several worker PROCESSES, so every worker read the manifest, added its own entry and wrote the
 * whole file back — and the last writer won. A 46-test run took the manifest from 122 entries to
 * 56 and nothing complained, because `userguide-check.sh` only checked manifest → disk. The 66
 * images that fell out silently stopped being staleness-checked, which is the one thing that
 * catches a guide whose screenshots no longer match the app.
 *
 * A file per shot has no shared mutable state, so there is nothing to race over. {@link
 * mergeManifest} turns them back into the manifest, once, after every worker has finished.
 */
function appendManifest(shot: DocShot): void {
  fs.mkdirSync(PARTS_DIR, { recursive: true });
  fs.writeFileSync(path.join(PARTS_DIR, `${shot.name}.json`), JSON.stringify(shot) + '\n');
}

/**
 * Folds this run's shots into the manifest, keeping entries for images it did not re-capture.
 *
 * <p>Called once after the Playwright run, from `tools/userguide-capture.sh` — a capture run is
 * usually a subset of the guides, and dropping the rest would make every other guide's
 * screenshots unchecked rather than merely unrefreshed.
 */
export function mergeManifest(): number {
  let all: DocShot[] = [];
  try {
    all = JSON.parse(fs.readFileSync(MANIFEST, 'utf8')) as DocShot[];
  } catch {
    all = []; // absent on the first capture, or unparseable — either way, start fresh
  }
  let parts: string[] = [];
  try {
    parts = fs.readdirSync(PARTS_DIR).filter((f) => f.endsWith('.json'));
  } catch {
    return all.length; // nothing was captured
  }
  for (const part of parts) {
    const shot = JSON.parse(fs.readFileSync(path.join(PARTS_DIR, part), 'utf8')) as DocShot;
    all = all.filter((s) => s.name !== shot.name);
    all.push(shot);
  }
  all.sort((a, b) => a.name.localeCompare(b.name));
  fs.writeFileSync(MANIFEST, JSON.stringify(all, null, 2) + '\n');
  fs.rmSync(PARTS_DIR, { recursive: true, force: true });
  return all.length;
}

type CaptureOptions = {
  /** Draw an attention ring around this element — the thing the step tells the reader to use. */
  highlight?: Locator;
  /** Number badge shown on the ring, matching the numbered step in the guide. */
  step?: number;
  /** Screenshot only this element's box (plus padding) instead of the viewport. */
  focus?: Locator;
  /** Capture the whole scrollable page. Default false — a focused image is easier to follow. */
  fullPage?: boolean;
};

type DocFixtures = {
  doc: {
    /** Name the guide these captures belong to. Sets the filename prefix. */
    guide: (slug: string) => void;
    capture: (name: string, caption: string, options?: CaptureOptions) => Promise<string>;
    /** Every /api/** request the page made, answered from fixtures. */
    apiCalls: ApiCall[];
  };
};

export const test = base.extend<DocFixtures>({
  doc: async ({ page }, use) => {
    let guideSlug = 'guide';

    // Installed before the spec navigates anywhere, so nothing escapes to a real backend.
    const apiCalls = await mockApi(page);

    const capture = async (
      name: string,
      caption: string,
      options: CaptureOptions = {},
    ): Promise<string> => {
      const { highlight, step, focus, fullPage = false } = options;

      if (highlight) {
        await highlight.scrollIntoViewIfNeeded();
        const box = await highlight.boundingBox();
        if (!box) throw new Error(`Cannot highlight "${name}": element has no bounding box`);
        // Inline callback, not new Function(): page.evaluate runs via the debugger
        // protocol and works under a strict CSP, whereas new Function() inside the page
        // would be blocked by our own `no unsafe-eval` policy.
        // A modal <dialog> lives in the browser's top layer, which paints above anything in the
        // normal page — including a ring appended to <body>. So when the element is inside a
        // dialog, the overlay is appended to that dialog instead and shows up in the capture.
        await highlight.evaluate(
          (el: Element, { box, step }: { box: { x: number; y: number; width: number; height: number }; step: number | null }) => {
            const host = el.closest('dialog') ?? document.body;
            const wrap = document.createElement('div');
            wrap.id = '__doc_overlay__';
            wrap.style.cssText =
              'position:fixed;inset:0;pointer-events:none;z-index:2147483647';

            const ring = document.createElement('div');
            ring.style.cssText = [
              'position:absolute',
              `left:${box.x - 6}px`,
              `top:${box.y - 6}px`,
              `width:${box.width + 12}px`,
              `height:${box.height + 12}px`,
              'border:3px solid #d92d20',
              'border-radius:8px',
              'box-shadow:0 0 0 4px rgba(217,45,32,0.20)',
            ].join(';');
            wrap.appendChild(ring);

            if (step !== null) {
              const badge = document.createElement('div');
              badge.textContent = String(step);
              badge.style.cssText = [
                'position:absolute',
                `left:${box.x - 20}px`,
                `top:${box.y - 20}px`,
                'width:28px',
                'height:28px',
                'border-radius:14px',
                'background:#d92d20',
                'color:#fff',
                'font:700 15px/28px system-ui,sans-serif',
                'text-align:center',
                'box-shadow:0 1px 4px rgba(0,0,0,0.3)',
              ].join(';');
              wrap.appendChild(badge);
            }
            host.appendChild(wrap);
          },
          { box, step: step ?? null },
        );
      }

      const file = path.join(IMAGES_DIR, `${guideSlug}--${name}.png`);
      fs.mkdirSync(IMAGES_DIR, { recursive: true });

      if (focus) {
        await focus.screenshot({ path: file });
      } else {
        await page.screenshot({ path: file, fullPage });
      }

      if (highlight) {
        await page.evaluate(() => document.getElementById('__doc_overlay__')?.remove());
      }

      const v = page.viewportSize();
      appendManifest({
        name: `${guideSlug}--${name}`,
        file: path.relative(path.resolve(IMAGES_DIR, '../..'), file),
        guide: guideSlug,
        caption,
        capturedAt: new Date().toISOString().slice(0, 10),
        commit: currentCommit(),
        viewport: v ? `${v.width}x${v.height}` : 'unknown',
      });
      return file;
    };

    await use({
      guide: (slug: string) => {
        guideSlug = slug;
      },
      capture,
      apiCalls,
    });
  },
});

export { expect, type Page, type Locator };
