import type { Page, Route } from '@playwright/test';
import {
  DEMO_ACCOUNTS,
  DEMO_CATEGORIES,
  DEMO_CATEGORIES_ACTIVE,
  DEMO_PAYEE_SUGGESTIONS,
  DEMO_TRANSACTIONS,
  DEMO_ACCOUNTS_ACTIVE,
  DEMO_API,
  DEMO_HOUSEHOLD,
  DEMO_INVITATION,
  DEMO_ME,
  DEMO_ME_MEMBER,
  DEMO_ME_NO_HOUSEHOLD,
  DEMO_ME_VIEWER,
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
export type MockSession = 'anonymous' | 'owner' | 'member' | 'viewer' | 'no-household';

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

/**
 * The balance an account has after its opening balance moved.
 *
 * `balance` is `openingBalance` plus the account's transactions — a different field, not a copy
 * (docs/features/accounts.md). Echoing the opening balance back as the balance collapsed the two:
 * renaming "Everyday Checking" dropped its balance from $2,480.15 to $1,641.75 on screen, which a
 * user-guide screenshot would then teach as real. So the transaction part is kept and only the
 * correction is applied. Arithmetic is on `bigint` minor units, never a float (ADR-0006).
 */
function rebalanced(before: { openingBalance: string; balance: string }, openingBalance: unknown): string {
  if (typeof openingBalance !== 'string' || openingBalance === before.openingBalance) {
    return before.balance;
  }
  const next = minorUnits(openingBalance);
  const previous = minorUnits(before.openingBalance);
  const balance = minorUnits(before.balance);
  if (next === null || previous === null || balance === null) {
    return before.balance;
  }
  return decimalString(balance + next - previous);
}

/** `'-400.0000'` → `-4000000n`, at `NUMERIC(19,4)`. `null` for anything that is not an amount. */
function minorUnits(amount: string): bigint | null {
  if (!/^-?[0-9]{1,15}(\.[0-9]{1,4})?$/.test(amount)) {
    return null;
  }
  const negative = amount.startsWith('-');
  const [whole, fraction = ''] = (negative ? amount.slice(1) : amount).split('.');
  const scaled = BigInt(whole + fraction.padEnd(4, '0'));
  return negative ? -scaled : scaled;
}

/** `-4000000n` → `'-400.0000'`, the scale the server reports. */
function decimalString(value: bigint): string {
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(5, '0');
  return `${negative ? '-' : ''}${digits.slice(0, -4)}.${digits.slice(-4)}`;
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
      session === 'member'
        ? DEMO_ME_MEMBER
        : session === 'viewer'
          ? DEMO_ME_VIEWER
          : session === 'no-household'
            ? DEMO_ME_NO_HOUSEHOLD
            : DEMO_ME;
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
        return json(route, 400, { type: 'https://budgetowl.app/errors/validation-failed', title: 'Validation failed', status: 400, code: 'validation-failed', params: { fields: ['displayName', 'password'] }, correlationId: 'demo0000000000000000000000000008' }, 'application/problem+json');
      }
      return json(route, 200, { householdId: DEMO_HOUSEHOLD.id, householdName: DEMO_HOUSEHOLD.name, role: 'MEMBER', userCreated: Boolean(field('password')), email: DEMO_INVITATION.email });
    }
    if (at === 'POST /api/households/current/invitations') {
      return json(route, 201, { ...DEMO_INVITATION, email: field('email') ?? DEMO_INVITATION.email, role: field('role') ?? DEMO_INVITATION.role });
    }

    // ---- accounts -------------------------------------------------------------------------
    // Enough of the real contract that a capture is honest: the default list hides archived rows,
    // every amount is a string (ADR-0006), and a write answers with an account rather than `ok`,
    // so the screen's own re-render is exercised instead of being skipped.
    if (at === 'GET /api/accounts') {
      const all = url.searchParams.get('includeArchived') === 'true';
      const rows = all ? DEMO_ACCOUNTS : DEMO_ACCOUNTS_ACTIVE;
      return json(route, 200, { content: rows, page: 0, size: 50, totalElements: rows.length, totalPages: 1 });
    }
    if (at === 'POST /api/accounts') {
      return json(route, 201, {
        ...DEMO_ACCOUNTS[0],
        id: 'acc-0099',
        name: field('name') ?? 'New account',
        lastFour: field('lastFour') ?? null,
        type: field('type') ?? 'CHECKING',
        currency: field('currency') ?? DEMO_HOUSEHOLD.baseCurrency,
        openingBalance: field('openingBalance') ?? '0.00',
        balance: field('openingBalance') ?? '0.00',
        archived: false,
        version: 0,
      });
    }
    const account = /^\/api\/accounts\/([^/]+)(?:\/(archive|unarchive))?$/.exec(url.pathname);
    if (account) {
      const found = DEMO_ACCOUNTS.find((one) => one.id === account[1]) ?? DEMO_ACCOUNTS[0];
      if (method === 'DELETE') {
        return route.fulfill({ status: 204 });
      }
      if (account[2]) {
        return json(route, 200, { ...found, archived: account[2] === 'archive', version: found.version + 1 });
      }
      if (method === 'PATCH') {
        // Only the properties the client actually sent, which is what PATCH means — and the one
        // place the three-way `lastFour` is visible: absent leaves it, null clears it (ADR-0028).
        const sent = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
        const changes = { ...sent };
        delete changes['version'];
        const patched = { ...found, ...changes, version: found.version + 1 };
        return json(route, 200, { ...patched, balance: rebalanced(found, patched.openingBalance) });
      }
      return json(route, 200, found);
    }

    // ---- categories -----------------------------------------------------------------------
    // Enough of the real contract that a capture is honest: the tree arrives flat with a
    // `parentId`, the default list hides archived rows, and a write answers with a category so
    // the screen's own re-render is exercised rather than skipped.
    if (at === 'GET /api/categories') {
      const all = url.searchParams.get('includeArchived') === 'true';
      const rows = all ? DEMO_CATEGORIES : DEMO_CATEGORIES_ACTIVE;
      return json(route, 200, { content: rows, page: 0, size: 200, totalElements: rows.length, totalPages: 1 });
    }
    if (at === 'POST /api/categories') {
      const parentId = field('parentId') ?? null;
      const parent = DEMO_CATEGORIES.find((one) => one.id === parentId);
      return json(route, 201, {
        ...DEMO_CATEGORIES[0],
        id: 'cat-0099',
        name: field('name') ?? 'New category',
        // A child takes its parent's kind. The server decides this, so the mock must too, or a
        // capture would show a combination the real API cannot produce.
        kind: parent ? parent.kind : (field('kind') ?? 'EXPENSE'),
        parentId: parent ? parent.id : null,
        parentName: parent ? parent.name : null,
        archived: false,
        version: 0,
      });
    }
    const category = /^\/api\/categories\/([^/]+)(?:\/(archive|unarchive))?$/.exec(url.pathname);
    if (category) {
      const found = DEMO_CATEGORIES.find((one) => one.id === category[1]) ?? DEMO_CATEGORIES[0];
      if (method === 'DELETE') {
        // 204, and no transaction is touched: the server nulls their category_id instead.
        return route.fulfill({ status: 204 });
      }
      if (category[2]) {
        return json(route, 200, { ...found, archived: category[2] === 'archive', version: found.version + 1 });
      }
      if (method === 'PATCH') {
        const sent = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
        const changes = { ...sent };
        delete changes['version'];
        // The three-way `parentId` is visible here: absent leaves the parent, null promotes it to
        // the top level, an id moves it.
        const parentId = 'parentId' in changes ? (changes['parentId'] as string | null) : found.parentId;
        const parent = DEMO_CATEGORIES.find((one) => one.id === parentId);
        return json(route, 200, {
          ...found,
          ...changes,
          parentId: parent ? parent.id : null,
          parentName: parent ? parent.name : null,
          version: found.version + 1,
        });
      }
      return json(route, 200, found);
    }

    // ---- transactions ---------------------------------------------------------------------
    // The filters are the part worth modelling: each one narrows the fixture rows the way the
    // server's query does, so a capture of a filtered ledger shows what the real one would.
    if (at === 'GET /api/transactions') {
      const accountId = url.searchParams.get('accountId');
      const categoryId = url.searchParams.get('categoryId');
      const uncategorised = url.searchParams.get('uncategorised') === 'true';
      const from = url.searchParams.get('from');
      const to = url.searchParams.get('to');
      const search = (url.searchParams.get('search') ?? '').toLowerCase();

      const rows = DEMO_TRANSACTIONS.filter((one) => {
        if (accountId && one.accountId !== accountId) return false;
        // Transfers have no category and never will: moving money between your own accounts is
        // neither income nor spending, so they stay out of the work queue (the server agrees).
        if (uncategorised && (one.categoryId !== null || one.transfer)) return false;
        if (categoryId && one.categoryId !== categoryId) return false;
        if (from && one.date < from) return false;
        if (to && one.date > to) return false;
        if (search) {
          const haystack = `${one.payeeName ?? ''} ${one.note ?? ''}`.toLowerCase();
          if (!haystack.includes(search)) return false;
        }
        return true;
      });

      // One currency across every fixture row, so there is always a total. A page that mixed them
      // would carry null here and the screen says so rather than converting (ADR-0022).
      const currencies = new Set(rows.map((one) => one.currency));
      const total = currencies.size === 1
        ? rows.reduce((sum, one) => sum + Number(one.amount), 0).toFixed(2)
        : null;

      return json(route, 200, {
        content: rows,
        pageTotal: total,
        pageTotalCurrency: currencies.size === 1 ? [...currencies][0] : null,
        page: 0,
        size: 50,
        totalElements: rows.length,
        totalPages: rows.length === 0 ? 0 : 1,
      });
    }
    if (at === 'GET /api/transactions/payee-suggestions') {
      const typed = (url.searchParams.get('typed') ?? '').toLowerCase();
      return json(
        route,
        200,
        DEMO_PAYEE_SUGGESTIONS.filter((name) => name.toLowerCase().startsWith(typed)),
      );
    }
    if (at === 'POST /api/transactions') {
      const account = DEMO_ACCOUNTS.find((one) => one.id === field('accountId'));
      const categoryId = field('categoryId') ?? null;
      const chosen = DEMO_CATEGORIES.find((one) => one.id === categoryId);
      return json(route, 201, {
        ...DEMO_TRANSACTIONS[0],
        id: 'txn-0099',
        accountId: account?.id ?? DEMO_ACCOUNTS[0].id,
        accountName: account?.name ?? DEMO_ACCOUNTS[0].name,
        date: field('date') ?? '2026-08-21',
        // The string the client sent, unparsed: a mock that re-formatted an amount would hide the
        // one defect ADR-0006 exists to prevent.
        amount: field('amount') ?? '-1.00',
        currency: account?.currency ?? 'USD',
        payeeId: field('payeeName') ? 'pay-0099' : null,
        payeeName: field('payeeName') ?? null,
        categoryId: chosen?.id ?? null,
        categoryName: chosen?.name ?? null,
        categoryParentName: chosen?.parentName ?? null,
        note: field('note') ?? null,
        status: field('status') ?? 'CLEARED',
        transfer: false,
        version: 0,
      });
    }
    const transaction = /^\/api\/transactions\/([^/]+)$/.exec(url.pathname);
    if (transaction) {
      const found = DEMO_TRANSACTIONS.find((one) => one.id === transaction[1]) ?? DEMO_TRANSACTIONS[0];
      if (method === 'DELETE') {
        return route.fulfill({ status: 204 });
      }
      if (method === 'PATCH') {
        const sent = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
        const changes = { ...sent };
        delete changes['version'];
        return json(route, 200, { ...found, ...changes, version: found.version + 1 });
      }
      return json(route, 200, found);
    }

    // ---- household ------------------------------------------------------------------------
    if (url.pathname.startsWith('/api/households/current') && session === 'no-household') {
      return problem(route, DEMO_PROBLEMS.notAMember);
    }
    if (at === 'GET /api/households/current') {
      return json(route, 200, {
        ...DEMO_HOUSEHOLD,
        role: session === 'member' ? 'MEMBER' : session === 'viewer' ? 'VIEWER' : 'OWNER',
      });
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
