import type { Page, Route } from '@playwright/test';
import { DEMO_API } from '../fixtures/demo-data.js';

/**
 * Serve the whole API from fixtures, in-process.
 *
 * Playwright intercepts every /api/** request and answers it from fixtures/demo-data.ts.
 * Nothing reaches a network, a server, or a database — there is no backend to point at and
 * no credentials to hold, so a capture run physically cannot read anyone's records.
 *
 * That is the control. It replaces the instruction not to use real data, which was only ever
 * a request that everyone remember to behave.
 *
 * It also makes captures deterministic: the same bytes every run, so a screenshot diff means
 * the UI changed rather than that a balance moved.
 */

/** Requests that reached the mock, so a spec can assert the page asked for what it needed. */
export type ApiCall = { method: string; path: string };

export async function mockApi(page: Page): Promise<ApiCall[]> {
  const calls: ApiCall[] = [];

  await page.route('**/api/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    calls.push({ method, path: url.pathname });

    // Writes succeed without persisting: a guide screenshot shows the confirmation, and the
    // next navigation re-reads the unchanged fixtures. Deterministic by construction.
    if (method !== 'GET') {
      await route.fulfill({
        status: method === 'POST' ? 201 : 200,
        contentType: 'application/json',
        body: JSON.stringify({ ok: true }),
      });
      return;
    }

    const body = DEMO_API[url.pathname];
    if (body === undefined) {
      // Loud rather than silent: an unmocked endpoint means the fixtures are behind the app,
      // and a guide screenshot of an empty screen is worse than a failed run.
      await route.fulfill({
        status: 501,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          title: 'Not mocked',
          status: 501,
          detail: `No fixture for ${url.pathname}. Add it to tools/ui/fixtures/demo-data.ts.`,
        }),
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(body),
    });
  });

  return calls;
}

/**
 * Refuse any target that is not on this machine.
 *
 * Documentation captures have no legitimate reason to reach a remote host, and their output is
 * committed to the repository. Enforced in code so it holds regardless of what a prompt,
 * an environment variable, or a hurried human asks for.
 */
export function assertLocalTarget(rawUrl: string): void {
  let host: string;
  try {
    host = new URL(rawUrl).hostname;
  } catch {
    throw new Error(`Not a valid URL: ${rawUrl}`);
  }
  const local = ['localhost', '127.0.0.1', '::1', '[::1]', '0.0.0.0'];
  if (!local.includes(host)) {
    throw new Error(
      `Refusing to capture against "${host}". Documentation captures run only against a local ` +
        `instance serving fixture data (tools/ui/fixtures/demo-data.ts). See ADR-0013.`,
    );
  }
}
