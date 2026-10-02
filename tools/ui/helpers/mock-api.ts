import type { Page, Route } from '@playwright/test';
import {
  DEMO_API,
  DEMO_HOUSEHOLD,
  DEMO_INVITATION,
  DEMO_ME,
  DEMO_ME_MEMBER,
  DEMO_ME_NO_HOUSEHOLD,
  DEMO_MEMBERS,
  DEMO_PROBLEMS,
  DEMO_TOKENS,
  DEMO_USER,
} from '../fixtures/demo-data.js';

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
export type ApiCall = {
  method: string;
  path: string;
  /** Request headers, lower-cased — for asserting the CSRF header went out. */
  headers?: Record<string, string>;
  /** The parsed JSON body, when there was one. Fixture traffic only; never real credentials. */
  body?: unknown;
};

/** Who the mocked session belongs to. `anonymous` answers 401 to `/api/auth/me`. */
export type MockSession = 'anonymous' | 'owner' | 'member' | 'no-household';

export type MockOptions = {
  /** Default `owner`: documentation captures show the signed-in app. */
  session?: MockSession;
  /** A fresh instance with no users: `/api/setup/status` says setup is not complete. */
  freshInstance?: boolean;
};

function json(route: Route, status: number, body: unknown, contentType = 'application/json'): Promise<void> {
  return route.fulfill({ status, contentType, body: JSON.stringify(body) });
}

function problem(route: Route, body: { status: number }): Promise<void> {
  return json(route, body.status, body, 'application/problem+json');
}

function readBody(route: Route): unknown {
  try {
    return route.request().postDataJSON();
  } catch {
    return undefined;
  }
}

export async function mockApi(page: Page, options: MockOptions = {}): Promise<ApiCall[]> {
  const calls: ApiCall[] = [];
  let session: MockSession = options.session ?? 'owner';
  let setupComplete = !options.freshInstance;

  // A member's own display preferences are the one write the mock remembers, so that saving a
  // language and reading the profile back shows the language applying. Per page, never shared.
  let ownPreferences: { displayCurrency: unknown; locale: unknown } | null = null;

  const me = () => {
    const base =
      session === 'member' ? DEMO_ME_MEMBER : session === 'no-household' ? DEMO_ME_NO_HOUSEHOLD : DEMO_ME;
    return ownPreferences && base.household ? { ...base, household: { ...base.household, ...ownPreferences } } : base;
  };

  await page.route('**/api/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    const body = readBody(route);
    calls.push({ method, path: url.pathname, headers: route.request().headers(), body });

    const at = `${method} ${url.pathname}`;
    const field = (name: string): unknown =>
      typeof body === 'object' && body !== null ? (body as Record<string, unknown>)[name] : undefined;

    // ---- setup and sign-in ----------------------------------------------------------------
    if (at === 'GET /api/setup/status') {
      return json(route, 200, { ...(DEMO_API['/api/setup/status'] as object), setupComplete });
    }
    if (at === 'POST /api/setup/first-user') {
      setupComplete = true;
      return json(route, 201, {
        userId: DEMO_USER.id,
        email: field('email'),
        displayName: field('displayName'),
        householdId: DEMO_HOUSEHOLD.id,
        householdName: field('householdName'),
        baseCurrency: field('baseCurrency'),
      });
    }
    if (at === 'GET /api/auth/me') {
      return session === 'anonymous' ? problem(route, DEMO_PROBLEMS.notAuthenticated) : json(route, 200, me());
    }
    if (at === 'POST /api/auth/login') {
      // The fixture password for "wrong" — anything else signs in. Never a real credential.
      if (field('password') === 'wrong-password') {
        return problem(route, DEMO_PROBLEMS.authenticationFailed);
      }
      session = 'owner';
      return json(route, 200, DEMO_ME);
    }
    if (at === 'POST /api/auth/logout') {
      const was = session;
      session = 'anonymous';
      return was === 'anonymous' ? problem(route, DEMO_PROBLEMS.notAuthenticated) : route.fulfill({ status: 204 });
    }

    // ---- invitations ----------------------------------------------------------------------
    const accept = /^\/api\/invitations\/([^/]+)\/accept$/.exec(url.pathname);
    if (method === 'POST' && accept) {
      const token = decodeURIComponent(accept[1]);
      if (token === DEMO_TOKENS.unusable) return problem(route, DEMO_PROBLEMS.invitationUnusable);
      if (token === DEMO_TOKENS.newAccount && !field('password')) {
        return json(route, 400, { type: 'https://budgetapp.dev/errors/validation-failed', title: 'Validation failed', status: 400, code: 'validation-failed', params: { fields: ['displayName', 'password'] }, correlationId: 'demo0000000000000000000000000008' }, 'application/problem+json');
      }
      return json(route, 200, { householdId: DEMO_HOUSEHOLD.id, householdName: DEMO_HOUSEHOLD.name, role: 'MEMBER', userCreated: Boolean(field('password')) });
    }
    if (at === 'POST /api/households/current/invitations') {
      return json(route, 201, { ...DEMO_INVITATION, email: field('email') ?? DEMO_INVITATION.email, role: field('role') ?? DEMO_INVITATION.role });
    }

    // ---- household ------------------------------------------------------------------------
    if (url.pathname.startsWith('/api/households/current') && session === 'no-household') {
      return problem(route, DEMO_PROBLEMS.notAMember);
    }
    if (at === 'GET /api/households/current') {
      return json(route, 200, { ...DEMO_HOUSEHOLD, role: session === 'member' ? 'MEMBER' : 'OWNER' });
    }
    if (at === 'PUT /api/households/current') {
      return json(route, 200, { ...DEMO_HOUSEHOLD, name: field('name'), baseCurrency: field('baseCurrency'), role: 'OWNER' });
    }
    const memberId = /^\/api\/households\/current\/members\/([^/]+)$/.exec(url.pathname);
    if (method === 'PATCH' && memberId) {
      const target = memberId[1] === 'me' ? DEMO_ME.household.membershipId : memberId[1];
      const found = DEMO_MEMBERS.find((m) => m.id === target) ?? DEMO_MEMBERS[0];
      if (memberId[1] === 'me') {
        ownPreferences = { displayCurrency: field('displayCurrency') ?? null, locale: field('locale') ?? null };
        return json(route, 200, { ...found, ...ownPreferences });
      }
      return json(route, 200, { ...found, role: field('role') });
    }
    if (method === 'DELETE') {
      return route.fulfill({ status: 204 });
    }

    // ---- everything else ------------------------------------------------------------------
    // Writes succeed without persisting: a guide screenshot shows the confirmation, and the
    // next navigation re-reads the unchanged fixtures. Deterministic by construction.
    if (method !== 'GET') {
      return json(route, method === 'POST' ? 201 : 200, { ok: true });
    }

    const found = DEMO_API[url.pathname];
    if (found === undefined) {
      // Loud rather than silent: an unmocked endpoint means the fixtures are behind the app,
      // and a guide screenshot of an empty screen is worse than a failed run.
      return json(
        route,
        501,
        { title: 'Not mocked', status: 501, detail: `No fixture for ${url.pathname}. Add it to tools/ui/fixtures/demo-data.ts.` },
        'application/problem+json',
      );
    }
    return json(route, 200, found);
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
