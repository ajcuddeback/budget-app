import type { Page, Route } from '@playwright/test';
import {
  DEMO_ACCOUNTS,
  DEMO_BILLS,
  DEMO_BILLS_ACTIVE,
  DEMO_ENVELOPES,
  DEMO_ENVELOPES_ACTIVE,
  DEMO_ENVELOPE_ENTRIES,
  DEMO_CATEGORIES,
  DEMO_CATEGORIES_ACTIVE,
  DEMO_PAYEE_SUGGESTIONS,
  DEMO_TRANSACTIONS,
  DEMO_ACCOUNTS_ACTIVE,
  DEMO_API,
  DEMO_HOUSEHOLD,
  DEMO_INCOME,
  DEMO_INCOME_ACTIVE,
  DEMO_INVITATION,
  DEMO_ME,
  DEMO_ME_MEMBER,
  DEMO_ME_NO_HOUSEHOLD,
  DEMO_ME_VIEWER,
  DEMO_MEMBERS,
  DEMO_DEBTS,
  DEMO_DEBT_PLAN,
  DEMO_DEBT_WHAT_IF,
  DEMO_DEBT_WHAT_IF_PLUS_FIFTY,
  DEMO_DEBT_DETAIL,
  DEMO_DEBT_STATE_DETAIL,
  DEMO_DEBT_PAYMENTS,
  DEMO_PERIOD_SUMMARY,
  DEMO_PROBLEMS,
  DEMO_TOKENS,
  DEMO_TRANSFER,
  DEMO_USER,
  DEMO_ADMIN_ACCOUNTS,
  DEMO_INSTANCE,
  DEMO_INSTANCE_SETTINGS,
  DEMO_REPORTS,
  DEMO_REPORT_STATES,
} from '../fixtures/demo-data.js';
import { DEBT_API } from '../fixtures/debt-api.generated.js';

/** A generated response by its key, or undefined. The keys are built at run time from a request. */
const generated = (key: string): unknown => (DEBT_API as Record<string, unknown>)[key];

/**
 * An amount as the generator keyed it: two decimal places, by string surgery rather than a parse,
 * so "445", "445.0" and "445.0000" all find the same generated preview.
 */
const canonicalAmount = (amount: string): string => {
  const [whole, fraction = ''] = amount.trim().split('.');
  return `${whole}.${(fraction + '00').slice(0, 2)}`;
};

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

/**
 * The dates a schedule falls on in a month — the same five rules as the server's `Recurrence`.
 *
 * <b>Why the mock projects at all.</b> The fixtures describe one household in August 2026, but
 * the bills screen opens on the CURRENT month: bills recur, so unlike envelopes it never has to
 * hunt for a month that has any. Serving August's dates whatever month was asked for produced a
 * page headed "November 2026" with every row dated August — which is incoherent in a screenshot
 * and would be worse in the user guide, where the capture is the documentation (ADR-0012).
 */
function datesIn(
  schedule: {
    kind: string;
    dayOfMonth: number | null;
    month: number | null;
    dayOfWeek: string | null;
    anchorDate: string | null;
  },
  period: string,
): string[] {
  const [year, month] = period.split('-').map(Number);
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const on = (day: number) => `${period}-${String(day).padStart(2, '0')}`;
  const WEEKDAYS = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];

  const everyMatchingWeekday = (): number[] => {
    const wanted = WEEKDAYS.indexOf(schedule.dayOfWeek ?? '');
    const days: number[] = [];
    for (let day = 1; day <= lastDay; day += 1) {
      if (new Date(Date.UTC(year, month - 1, day)).getUTCDay() === wanted) days.push(day);
    }
    return days;
  };

  switch (schedule.kind) {
    case 'MONTHLY':
      // Clamped, so a bill due on the 31st falls on the 30th of April.
      return [on(Math.min(schedule.dayOfMonth ?? 1, lastDay))];
    case 'YEARLY':
      return schedule.month === month ? [on(Math.min(schedule.dayOfMonth ?? 1, lastDay))] : [];
    case 'WEEKLY':
      return everyMatchingWeekday().map(on);
    case 'FORTNIGHTLY': {
      const anchor = Date.parse(`${schedule.anchorDate ?? `${period}-01`}T00:00:00Z`);
      return everyMatchingWeekday()
        .filter((day) => {
          const weeks = Math.round((Date.UTC(year, month - 1, day) - anchor) / 604800000);
          // The same floorMod the server uses, so a date before the anchor steps correctly.
          return ((weeks % 2) + 2) % 2 === 0;
        })
        .map(on);
    }
    default:
      // IRREGULAR projects nothing. Recording against a date is what creates its occurrence.
      return [];
  }
}

/**
 * One bill's occurrences in a month: the projected dates, carrying the fixture's recorded
 * statuses in order.
 *
 * That ordering is what keeps every case the fixtures were chosen for — a paid one, an overdue
 * one, one paid for a surprising amount — present in whatever month the screen is showing.
 */
function occurrencesIn(
  bill: (typeof DEMO_BILLS)[number],
  period: string,
  recorded: Map<string, Record<string, unknown>> = new Map(),
) {
  const dates = bill.active ? datesIn(bill.schedule, period) : [];
  const projected: Record<string, unknown>[] = dates.map((dueDate, index) => {
    const since = recorded.get(`${bill.id}|${dueDate}`);
    if (since) {
      // Something was recorded during this run. A real server reads it back on the next GET, so
      // the mock does too — otherwise a screenshot taken after "Mark paid" shows the row still
      // due, which in a user guide is a picture of the app not working.
      return { ...since, expectedAmount: bill.expectedAmount };
    }
    const fixture = bill.occurrences[index];
    if (!fixture) {
      return {
        billId: bill.id,
        dueDate,
        expectedAmount: bill.expectedAmount,
        status: 'DUE',
        actualAmount: null,
        paidDate: null,
        transactionId: null,
        statusSource: 'MANUAL',
        recorded: false,
        overdue: false,
        surprising: false,
        version: null,
      };
    }
    return {
      ...fixture,
      dueDate,
      paidDate: fixture.paidDate ? dueDate : null,
    };
  });
  // An irregular bill projects nothing, so a payment recorded against a date is the only thing
  // that brings a row into being — the same rule the server follows.
  const extra = [...recorded.values()].filter(
    (one) =>
      one['billId'] === bill.id &&
      String(one['dueDate']).startsWith(period) &&
      !dates.includes(String(one['dueDate'])),
  );
  return [...projected, ...extra].sort((a, b) =>
    String(a['dueDate']).localeCompare(String(b['dueDate'])),
  );
}

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

/**
 * The admin console's settings, as the mock holds them.
 *
 * `passwordLoginLocked` is NOT here: the server derives it from the provider's state and so does
 * the mock, so no spec can describe an instance whose lock contradicts its own configuration.
 */
export type AdminSettingsState = {
  registrationOpen: boolean;
  passwordLoginEnabled: boolean;
  oidcEnabled: boolean;
  oidcIssuerUri: string | null;
  oidcClientId: string | null;
  /** Whether a secret is configured. Never what it is — there is no field for that anywhere. */
  oidcClientSecretSet: boolean;
  oidcProvisioningEnabled: boolean;
  oidcOwnerLoginAt: string | null;
  updatedAt: string;
  version: number;
};

export type MockOptions = {
  /** Default `owner`: documentation captures show the signed-in app. */
  session?: MockSession;
  /** A fresh instance with no users: `/api/setup/status` says setup is not complete. */
  freshInstance?: boolean;
  /** Where the admin console's settings start. Merged over `DEMO_INSTANCE_SETTINGS`. */
  adminSettings?: Partial<AdminSettingsState>;
  /**
   * What another administrator changes between this page reading the settings and saving them.
   * The next `PATCH /api/admin/settings` applies it and answers `409 stale-version`, which is the
   * only way the console's conflict notice can name the fields that moved.
   */
  adminConflict?: Partial<AdminSettingsState>;
  /**
   * Which household Insights reports on. Default `demo`, the household the guide is captured from;
   * `empty` has recorded nothing, `two-currencies` holds money in dollars and euros. All three are
   * generated by `DemoFixtureIT` from the real application.
   */
  reports?: 'demo' | 'empty' | 'two-currencies';
};

/** When a saved setting says it changed. A constant, because a capture has to be the same twice. */
const SETTINGS_SAVED_AT = '2026-11-20T09:15:00Z';

function json(
  route: Route,
  status: number,
  body: unknown,
  contentType = 'application/json',
): Promise<void> {
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
function rebalanced(
  before: { openingBalance: string; balance: string },
  openingBalance: unknown,
): string {
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
  /**
   * What this run has recorded against a bill, keyed `billId|dueDate`.
   *
   * The only state the mock keeps, and it is kept because the screen reloads from the server
   * after recording: without it, marking a bill paid would flip back to due in front of the
   * person who just did it. Per page, so nothing leaks between tests.
   */
  const recordedOccurrences = new Map<string, Record<string, unknown>>();
  let session: MockSession = options.session ?? 'owner';
  let setupComplete = !options.freshInstance;

  // A member's own display preferences are the one write the mock remembers, so that saving a
  // language and reading the profile back shows the language applying. Per page, never shared.
  let ownPreferences: { displayCurrency: unknown; locale: unknown } | null = null;

  // The household's own debt order, once a PUT has chosen one — the ids rung 1 first, joined. The
  // plan and the debt list are then served for THAT order, from the six the generator produced,
  // so a move on screen shows the figures the real application computes for the order it sent.
  // Per page, never shared.
  let customOrder: string | null = null;

  const me = () => {
    const base =
      session === 'member'
        ? DEMO_ME_MEMBER
        : session === 'viewer'
          ? DEMO_ME_VIEWER
          : session === 'no-household'
            ? DEMO_ME_NO_HOUSEHOLD
            : DEMO_ME;
    return ownPreferences && base.household
      ? { ...base, household: { ...base.household, ...ownPreferences } }
      : base;
  };

  /**
   * The console's settings, as state this run can move.
   *
   * Kept for the same reason the recorded occurrences are: the page re-reads after saving, and a
   * mock that forgot would show a switch springing back in front of the person who just moved it.
   * `passwordLoginLocked` is absent on purpose and derived below — see DEMO_INSTANCE_SETTINGS.
   */
  const adminSettings: AdminSettingsState = { ...DEMO_INSTANCE_SETTINGS, ...options.adminSettings };

  /** What another administrator saved first, held until the next PATCH asks for it. */
  let foreignChange: Partial<AdminSettingsState> | null = options.adminConflict ?? null;

  /** The server's rule, not a setting: nothing may hide password login until a provider works. */
  const passwordLoginLocked = (): boolean =>
    !adminSettings.oidcEnabled || adminSettings.oidcOwnerLoginAt === null;

  const settingsResponse = (): Record<string, unknown> => ({
    ...adminSettings,
    passwordLoginLocked: passwordLoginLocked(),
  });

  /** The server's issuer rule: https anywhere, or http on this machine. */
  const usableIssuer = (value: string): boolean => {
    try {
      const parsed = new URL(value.trim());
      return (
        parsed.protocol === 'https:' ||
        (parsed.protocol === 'http:' &&
          ['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname))
      );
    } catch {
      return false;
    }
  };

  const applySettingsPatch = (sent: Record<string, unknown>): void => {
    for (const flag of [
      'registrationOpen',
      'passwordLoginEnabled',
      'oidcEnabled',
      'oidcProvisioningEnabled',
    ] as const) {
      if (typeof sent[flag] === 'boolean') adminSettings[flag] = sent[flag];
    }
    for (const text of ['oidcIssuerUri', 'oidcClientId'] as const) {
      if (typeof sent[text] === 'string') adminSettings[text] = sent[text];
    }
    // The secret is the one field whose PRESENCE is the message: present and blank CLEARS the
    // stored one, absent leaves it alone (`AdminRequests.oidcClientSecretChange`). Modelled here
    // because it is the behaviour the console's secret field exists to avoid tripping over, and a
    // mock that ignored the field would let that defect pass a green run.
    if (Object.hasOwn(sent, 'oidcClientSecret')) {
      adminSettings.oidcClientSecretSet = String(sent['oidcClientSecret'] ?? '').trim() !== '';
      // ADR-0034: a changed credential is an unproven credential, so the proof goes and password
      // login comes back — the same thing `configureOidc` does for a changed issuer. The mock has
      // to carry this or the console would be developed against a server that is quieter than the
      // real one, and "rotate the secret" would look like a change with no consequences.
      //
      // Sent-and-different is the closest this mock can get: it never holds the secret itself,
      // only whether one is set. A spec that re-sends the identical secret would see a reset here
      // and not on the server. No spec does, and the console only sends the field when somebody
      // typed into an empty box — but it is a divergence, so it is written down rather than left
      // for somebody to discover.
      Object.assign(adminSettings, { oidcOwnerLoginAt: null, passwordLoginEnabled: true });
    }
    if (sent['oidcEnabled'] === false) {
      // `InstanceSettings.disableOidc`: the provider's configuration is cleared and password login
      // comes back on, so nobody is left without a way in. The request says only `oidcEnabled`.
      Object.assign(adminSettings, {
        oidcIssuerUri: null,
        oidcClientId: null,
        oidcClientSecretSet: false,
        oidcProvisioningEnabled: false,
        oidcOwnerLoginAt: null,
        passwordLoginEnabled: true,
      });
    }
    adminSettings.version += 1;
    adminSettings.updatedAt = SETTINGS_SAVED_AT;
  };

  await page.route('**/api/**', async (route: Route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    const body = readBody(route);
    calls.push({ method, path: url.pathname, headers: route.request().headers(), body });

    const at = `${method} ${url.pathname}`;
    const field = (name: string): unknown =>
      typeof body === 'object' && body !== null
        ? (body as Record<string, unknown>)[name]
        : undefined;

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
      return session === 'anonymous'
        ? problem(route, DEMO_PROBLEMS.notAuthenticated)
        : json(route, 200, me());
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
      return was === 'anonymous'
        ? problem(route, DEMO_PROBLEMS.notAuthenticated)
        : route.fulfill({ status: 204 });
    }

    // ---- the instance an operator runs ----------------------------------------------------
    // Administrator-only, and that is enforced here rather than assumed: the console hides its
    // link and the guard refuses the route, but `403 not-instance-admin` is the control (ADR-0029),
    // so the mock answers one. Nothing here carries a figure of anybody's money.
    if (url.pathname.startsWith('/api/admin/')) {
      if (session === 'anonymous') {
        return problem(route, DEMO_PROBLEMS.notAuthenticated);
      }
      if (!me().instanceAdmin) {
        return problem(route, DEMO_PROBLEMS.notInstanceAdmin);
      }
      if (at === 'GET /api/admin/instance') {
        return json(route, 200, DEMO_INSTANCE);
      }
      if (at === 'GET /api/admin/users') {
        return json(route, 200, {
          content: DEMO_ADMIN_ACCOUNTS,
          total: DEMO_ADMIN_ACCOUNTS.length,
        });
      }
      if (at === 'GET /api/admin/settings') {
        return json(route, 200, settingsResponse());
      }
      if (at === 'PATCH /api/admin/settings') {
        // Somebody else saved first. Their change is applied and this one refused, which is the
        // order that matters: the console re-reads afterwards, and that re-read is the only way it
        // can name the settings that moved.
        if (foreignChange) {
          Object.assign(adminSettings, foreignChange, {
            version: adminSettings.version + 1,
            updatedAt: SETTINGS_SAVED_AT,
          });
          foreignChange = null;
          return problem(route, DEMO_PROBLEMS.staleVersion);
        }
        if (field('version') !== adminSettings.version) {
          return problem(route, DEMO_PROBLEMS.staleVersion);
        }
        // The two refusals the console is written around, answered rather than assumed away: the
        // database refuses to hide password login until a provider has been proved to work, and an
        // issuer that is not https (or http on this machine) is a validation failure naming itself.
        if (field('passwordLoginEnabled') === false && passwordLoginLocked()) {
          return problem(route, DEMO_PROBLEMS.passwordLoginLocked);
        }
        if (
          typeof field('oidcIssuerUri') === 'string' &&
          !usableIssuer(field('oidcIssuerUri') as string)
        ) {
          return problem(route, DEMO_PROBLEMS.invalidIssuerUri);
        }
        applySettingsPatch(
          typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {},
        );
        return json(route, 200, settingsResponse());
      }
    }

    // ---- invitations ----------------------------------------------------------------------
    const accept = /^\/api\/invitations\/([^/]+)\/accept$/.exec(url.pathname);
    if (method === 'POST' && accept) {
      const token = decodeURIComponent(accept[1]);
      if (token === DEMO_TOKENS.unusable) return problem(route, DEMO_PROBLEMS.invitationUnusable);
      if (token === DEMO_TOKENS.newAccount && !field('password')) {
        return json(
          route,
          400,
          {
            type: 'https://budgetowl.app/errors/validation-failed',
            title: 'Validation failed',
            status: 400,
            code: 'validation-failed',
            params: { fields: ['displayName', 'password'] },
            correlationId: 'demo0000000000000000000000000008',
          },
          'application/problem+json',
        );
      }
      return json(route, 200, {
        householdId: DEMO_HOUSEHOLD.id,
        householdName: DEMO_HOUSEHOLD.name,
        role: 'MEMBER',
        userCreated: Boolean(field('password')),
        email: DEMO_INVITATION.email,
      });
    }
    if (at === 'POST /api/households/current/invitations') {
      return json(route, 201, {
        ...DEMO_INVITATION,
        email: field('email') ?? DEMO_INVITATION.email,
        role: field('role') ?? DEMO_INVITATION.role,
      });
    }

    // ---- accounts -------------------------------------------------------------------------
    // Enough of the real contract that a capture is honest: the default list hides archived rows,
    // every amount is a string (ADR-0006), and a write answers with an account rather than `ok`,
    // so the screen's own re-render is exercised instead of being skipped.
    if (at === 'GET /api/accounts') {
      const all = url.searchParams.get('includeArchived') === 'true';
      const rows = all ? DEMO_ACCOUNTS : DEMO_ACCOUNTS_ACTIVE;
      return json(route, 200, {
        content: rows,
        page: 0,
        size: 50,
        totalElements: rows.length,
        totalPages: 1,
      });
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
        return json(route, 200, {
          ...found,
          archived: account[2] === 'archive',
          version: found.version + 1,
        });
      }
      if (method === 'PATCH') {
        // Only the properties the client actually sent, which is what PATCH means — and the one
        // place the three-way `lastFour` is visible: absent leaves it, null clears it (ADR-0028).
        const sent =
          typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
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
      return json(route, 200, {
        content: rows,
        page: 0,
        size: 200,
        totalElements: rows.length,
        totalPages: 1,
      });
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
        return json(route, 200, {
          ...found,
          archived: category[2] === 'archive',
          version: found.version + 1,
        });
      }
      if (method === 'PATCH') {
        const sent =
          typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
        const changes = { ...sent };
        delete changes['version'];
        // The three-way `parentId` is visible here: absent leaves the parent, null promotes it to
        // the top level, an id moves it.
        const parentId =
          'parentId' in changes ? (changes['parentId'] as string | null) : found.parentId;
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
      const total =
        currencies.size === 1
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
      const found =
        DEMO_TRANSACTIONS.find((one) => one.id === transaction[1]) ?? DEMO_TRANSACTIONS[0];
      if (method === 'DELETE') {
        return route.fulfill({ status: 204 });
      }
      if (method === 'PATCH') {
        const sent =
          typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
        const changes = { ...sent };
        delete changes['version'];
        return json(route, 200, { ...found, ...changes, version: found.version + 1 });
      }
      return json(route, 200, found);
    }

    // ---- transfers ------------------------------------------------------------------------
    // Addressed by the GROUP id, never by either leg's transaction id: the pair is the object, and
    // editing or deleting half of one is refused by the real API (ADR-0033). The fixture household
    // moves money between two USD accounts, so there is one figure; a cross-currency pair would
    // carry two and an implied rate.
    if (at === 'POST /api/transfers') {
      const from = DEMO_ACCOUNTS.find((one) => one.id === field('fromAccountId'));
      const to = DEMO_ACCOUNTS.find((one) => one.id === field('toAccountId'));
      const sent = (field('amount') as string | undefined) ?? '0.00';
      // The strings the client sent, unparsed: a mock that re-formatted an amount would hide the
      // one defect ADR-0006 exists to prevent.
      const received = (field('amountReceived') as string | undefined) ?? sent;
      return json(route, 201, {
        ...DEMO_TRANSFER,
        id: 'tgr-0099',
        fromAccountId: from?.id ?? DEMO_TRANSFER.fromAccountId,
        fromAccountName: from?.name ?? DEMO_TRANSFER.fromAccountName,
        toAccountId: to?.id ?? DEMO_TRANSFER.toAccountId,
        toAccountName: to?.name ?? DEMO_TRANSFER.toAccountName,
        date: (field('date') as string | undefined) ?? DEMO_TRANSFER.date,
        sent,
        sentCurrency: from?.currency ?? 'USD',
        received,
        receivedCurrency: to?.currency ?? 'USD',
        crossCurrency: (from?.currency ?? 'USD') !== (to?.currency ?? 'USD'),
        note: (field('note') as string | undefined) ?? null,
      });
    }
    const transfer = /^\/api\/transfers\/([^/]+)$/.exec(url.pathname);
    if (transfer) {
      if (method === 'DELETE') {
        return route.fulfill({ status: 204 });
      }
      if (method === 'PATCH') {
        const sent = (field('amount') as string | undefined) ?? DEMO_TRANSFER.sent;
        return json(route, 200, {
          ...DEMO_TRANSFER,
          date: (field('date') as string | undefined) ?? DEMO_TRANSFER.date,
          sent,
          // Within one currency the legs are equal, and the real API refuses a second figure here.
          received: (field('amountReceived') as string | undefined) ?? sent,
          note: field('note') === undefined ? DEMO_TRANSFER.note : (field('note') as string | null),
          // The pair's version moves by two: both legs are written together.
          version: DEMO_TRANSFER.version + 2,
        });
      }
      return json(route, 200, DEMO_TRANSFER);
    }

    // ---- bills, income and the month's summary ---------------------------------------------
    // An occurrence is addressed by its DUE DATE, not an id (ADR-0032), because for most of its
    // life it has no row. The routes below are matched in that shape so a spec can assert that a
    // tap on one Friday's "Mark paid" went to that Friday and to no other.
    if (at === 'GET /api/bills') {
      const all = url.searchParams.get('includeInactive') === 'true';
      const period = url.searchParams.get('period') ?? '2026-08';
      const rows = (all ? DEMO_BILLS : DEMO_BILLS_ACTIVE).map((bill) => ({
        ...bill,
        occurrences: occurrencesIn(bill, period, recordedOccurrences),
      }));
      return json(route, 200, {
        content: rows,
        total: DEMO_PERIOD_SUMMARY.bills,
        outstanding: DEMO_PERIOD_SUMMARY.billsOutstanding,
        overdue: DEMO_PERIOD_SUMMARY.billsOverdue,
        currency: 'USD',
      });
    }
    if (at === 'POST /api/bills') {
      return json(route, 201, {
        ...DEMO_BILLS[0],
        id: 'bil-0099',
        name: field('name') ?? 'New bill',
        expectedAmount: field('expectedAmount') ?? '0.0000',
        categoryId: field('categoryId') ?? null,
        categoryName: null,
        categoryParentName: null,
        amountVaries: field('amountVaries') === true,
        tolerance: field('tolerance') ?? null,
        // A write answers with no occurrences: which month the client is looking at is the
        // client's question, and the list endpoint is where it asks.
        occurrences: [],
        version: 0,
      });
    }
    const occurrenceRoute = /^\/api\/bills\/([^/]+)\/occurrences\/([^/]+)$/.exec(url.pathname);
    if (occurrenceRoute) {
      const [, billId, dueDate] = occurrenceRoute;
      if (method === 'DELETE') {
        // Forgetting: the row goes back to whatever the schedule projects.
        recordedOccurrences.delete(`${billId}|${dueDate}`);
        return route.fulfill({ status: 204 });
      }
      const bill = DEMO_BILLS.find((one) => one.id === billId) ?? DEMO_BILLS[0];
      const status = field('status') ?? 'PAID';
      const paid = status === 'PAID';
      const tolerance = Number(bill.tolerance ?? 0);
      const actualAmount = paid ? (field('actualAmount') ?? bill.expectedAmount) : null;
      const written = {
        billId,
        dueDate,
        expectedAmount: bill.expectedAmount,
        status,
        actualAmount,
        paidDate: paid ? (field('paidDate') ?? dueDate) : null,
        transactionId: null,
        statusSource: 'MANUAL',
        recorded: true,
        overdue: false,
        surprising:
          paid && Math.abs(Number(actualAmount) - Number(bill.expectedAmount)) > tolerance,
        version: 0,
      };
      recordedOccurrences.set(`${billId}|${dueDate}`, written);
      return json(route, 200, written);
    }
    const billRoute = /^\/api\/bills\/([^/]+)$/.exec(url.pathname);
    if (billRoute) {
      const found = DEMO_BILLS.find((one) => one.id === billRoute[1]) ?? DEMO_BILLS[0];
      if (method === 'DELETE') {
        return route.fulfill({ status: 204 });
      }
      if (method === 'PATCH') {
        const sent =
          typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
        const changes = { ...sent };
        delete changes['version'];
        delete changes['schedule'];
        return json(route, 200, { ...found, ...changes, version: found.version + 1 });
      }
      return json(route, 200, found);
    }

    // Any month, answered with August's figures. The screen opens on the CURRENT month — right
    // for bills, which recur, unlike envelopes which have to find a month that has any — so a
    // mock keyed on one month would answer nothing on every run after August 2026.
    const summaryRoute = /^\/api\/periods\/([^/]+)\/summary$/.exec(url.pathname);
    if (summaryRoute && method === 'GET') {
      return json(route, 200, { ...DEMO_PERIOD_SUMMARY, period: summaryRoute[1] });
    }

    if (at === 'GET /api/income') {
      const all = url.searchParams.get('includeInactive') === 'true';
      const period = url.searchParams.get('period') ?? '2026-08';
      const rows = (all ? DEMO_INCOME : DEMO_INCOME_ACTIVE).map((source) => ({
        ...source,
        dates: source.active ? datesIn(source.schedule, period) : [],
      }));
      return json(route, 200, {
        content: rows,
        total: DEMO_PERIOD_SUMMARY.income,
        currency: 'USD',
      });
    }
    if (at === 'POST /api/income') {
      return json(route, 201, {
        ...DEMO_INCOME[0],
        id: 'inc-0099',
        name: field('name') ?? 'New income',
        amount: field('amount') ?? '0.0000',
        accountId: field('accountId') ?? null,
        accountName: null,
        dates: [],
        expectedForPeriod: '0.0000',
        version: 0,
      });
    }
    const incomeRoute = /^\/api\/income\/([^/]+)$/.exec(url.pathname);
    if (incomeRoute) {
      const found = DEMO_INCOME.find((one) => one.id === incomeRoute[1]) ?? DEMO_INCOME[0];
      if (method === 'DELETE') {
        return route.fulfill({ status: 204 });
      }
      if (method === 'PATCH') {
        const sent =
          typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
        const changes = { ...sent };
        delete changes['version'];
        delete changes['schedule'];
        return json(route, 200, { ...found, ...changes, version: found.version + 1 });
      }
      return json(route, 200, found);
    }

    // ---- envelopes ------------------------------------------------------------------------
    // The period filter is the part worth modelling: a month that has no envelopes must come back
    // empty rather than showing August's, or the empty state would never be capturable.
    if (at === 'GET /api/envelopes') {
      const period = url.searchParams.get('period') ?? '2026-08';
      const all = url.searchParams.get('includeArchived') === 'true';
      const rows = (all ? DEMO_ENVELOPES : DEMO_ENVELOPES_ACTIVE).filter(
        (envelope) => envelope.period === period,
      );
      // Archived envelopes are returned but never totalled: a retired envelope is not part of
      // the month's plan, and the server agrees.
      const planned = rows.filter((envelope) => !envelope.archived);
      const sum = (pick: (e: (typeof DEMO_ENVELOPES)[number]) => string) =>
        planned.reduce((total, envelope) => total + Number(pick(envelope)), 0).toFixed(4);
      return json(route, 200, {
        content: rows,
        totalAllocated: sum((e) => e.amount),
        totalSpent: sum((e) => e.spent),
        totalRemaining: sum((e) => e.remaining),
        currency: 'USD',
        page: 0,
        size: 200,
        totalElements: rows.length,
        totalPages: rows.length === 0 ? 0 : 1,
      });
    }
    if (at === 'POST /api/envelopes') {
      return json(route, 201, {
        ...DEMO_ENVELOPES[1],
        id: 'env-0099',
        name: field('name') ?? 'New envelope',
        period: field('period') ?? '2026-08',
        amount: field('amount') ?? '0.0000',
        spent: '0.0000',
        // Everything remaining, nothing logged — what a new envelope actually looks like.
        remaining: field('amount') ?? '0.0000',
        categoryId: field('categoryId') ?? null,
        categoryName: null,
        categoryParentName: null,
        entryCount: 0,
        empty: true,
        archived: false,
        version: 0,
      });
    }
    const entryRoute = /^\/api\/envelopes\/([^/]+)\/entries(?:\/([^/]+))?$/.exec(url.pathname);
    if (entryRoute) {
      const envelopeId = entryRoute[1];
      if (method === 'GET') {
        const rows = DEMO_ENVELOPE_ENTRIES.filter((entry) => entry.envelopeId === envelopeId);
        return json(route, 200, {
          content: rows,
          page: 0,
          size: 50,
          totalElements: rows.length,
          totalPages: rows.length === 0 ? 0 : 1,
        });
      }
      if (method === 'DELETE') {
        return route.fulfill({ status: 204 });
      }
      if (method === 'POST') {
        return json(route, 201, {
          ...DEMO_ENVELOPE_ENTRIES[1],
          id: 'ent-0099',
          envelopeId,
          date: field('date') ?? '2026-08-20',
          // The string the client sent, unparsed: a mock that re-formatted an amount would hide
          // the one defect ADR-0006 exists to prevent.
          amount: field('amount') ?? '1.0000',
          payeeName: field('payeeName') ?? null,
          payeeId: field('payeeName') ? 'pay-0099' : null,
          note: field('note') ?? null,
          // Attaching a transaction is what makes an entry SYNCED; typing one never does.
          source: field('transactionId') ? 'SYNCED' : 'MANUAL',
          transactionId: field('transactionId') ?? null,
          version: 0,
        });
      }
      if (method === 'PATCH') {
        const found =
          DEMO_ENVELOPE_ENTRIES.find((entry) => entry.id === entryRoute[2]) ??
          DEMO_ENVELOPE_ENTRIES[0];
        const sent =
          typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
        const changes = { ...sent };
        delete changes['version'];
        return json(route, 200, { ...found, ...changes, version: found.version + 1 });
      }
    }
    const envelope = /^\/api\/envelopes\/([^/]+)(?:\/(archive|unarchive))?$/.exec(url.pathname);
    if (envelope && envelope[1] !== 'periods') {
      const found = DEMO_ENVELOPES.find((one) => one.id === envelope[1]) ?? DEMO_ENVELOPES[0];
      if (method === 'DELETE') {
        return route.fulfill({ status: 204 });
      }
      if (envelope[2]) {
        return json(route, 200, {
          ...found,
          archived: envelope[2] === 'archive',
          version: found.version + 1,
        });
      }
      if (method === 'PATCH') {
        const sent =
          typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
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
      return json(route, 200, {
        ...DEMO_HOUSEHOLD,
        name: field('name'),
        baseCurrency: field('baseCurrency'),
        role: 'OWNER',
      });
    }
    const memberId = /^\/api\/households\/current\/members\/([^/]+)$/.exec(url.pathname);
    if (method === 'PATCH' && memberId) {
      const target = memberId[1] === 'me' ? DEMO_ME.household.membershipId : memberId[1];
      const found = DEMO_MEMBERS.find((m) => m.id === target) ?? DEMO_MEMBERS[0];
      if (memberId[1] === 'me') {
        ownPreferences = {
          displayCurrency: field('displayCurrency') ?? null,
          locale: field('locale') ?? null,
        };
        return json(route, 200, { ...found, ...ownPreferences });
      }
      return json(route, 200, { ...found, role: field('role') });
    }
    if (method === 'DELETE') {
      return route.fulfill({ status: 204 });
    }

    // ---- debts and the payoff plan ----------------------------------------------------------
    // Served from debt-api.json, which `DemoFixtureIT` GENERATES from the real application and
    // then guards against drift. Nothing here computes a projection: a mock that did its own
    // solver arithmetic would be a second implementation, and the guide would document whichever
    // of the two happened to be wrong.
    if (at === 'GET /api/debts') {
      return json(
        route,
        200,
        customOrder ? generated(`GET /api/debts CUSTOM ${customOrder}`) : DEMO_DEBTS,
      );
    }
    if (at === 'GET /api/debt-plan') {
      return json(
        route,
        200,
        customOrder ? generated(`PUT /api/debt-plan CUSTOM ${customOrder}`) : DEMO_DEBT_PLAN,
      );
    }
    if (at === 'PUT /api/debt-plan' && field('strategy') === 'CUSTOM') {
      // The household's own order. Answered only for an order the generator produced — all six a
      // three-debt household can choose — and loudly refused otherwise, never answered with a
      // plan for some other order. A PUT that keeps CUSTOM without naming an order (changing the
      // monthly amount, say) keeps the order already chosen.
      const sent = field('customOrder');
      const order = Array.isArray(sent) ? sent.join(',') : customOrder;
      const answer = order ? generated(`PUT /api/debt-plan CUSTOM ${order}`) : undefined;
      if (answer === undefined) {
        return json(
          route,
          501,
          {
            title: 'Not mocked',
            status: 501,
            detail: `No generated plan for the custom order "${order ?? ''}". Add it to DemoFixtureIT and regenerate.`,
          },
          'application/problem+json',
        );
      }
      customOrder = order;
      return json(route, 200, answer);
    }
    if (at === 'PUT /api/debt-plan') {
      // Leaving "Your own order" returns the plan to the generated avalanche one.
      customOrder = null;
    }
    const preview = url.pathname.match(/^\/api\/debts\/([^/]+)\/payments\/preview$/);
    if (method === 'POST' && preview) {
      // What logging an amount WOULD record. Keyed on the debt and the amount actually sent, for
      // the same reason the what-if is: answering an amount nobody generated with a plausible
      // split is a screenshot of arithmetic the application never did.
      const amount = canonicalAmount(String(field('amount') ?? ''));
      const answer = generated(`POST /api/debts/${preview[1]}/payments/preview ${amount}`);
      return answer === undefined
        ? json(
            route,
            501,
            {
              title: 'Not mocked',
              status: 501,
              detail: `No generated preview for ${preview[1]} at ${amount}. Add it to DemoFixtureIT's PREVIEW_AMOUNTS and regenerate.`,
            },
            'application/problem+json',
          )
        : json(route, 200, answer);
    }
    if (at === 'GET /api/debt-plan/projection') {
      // Keyed on what was ASKED. Returning the stored plan for an unrecognised question is how
      // the page came to say "adding $50.00 a month would not move the date" — it was comparing
      // the plan against itself. It moves it two months. A mock that answers a question it was
      // not asked produces a screenshot that is a lie in the user guide, so an unknown amount is
      // a loud 501 instead.
      const strategy = url.searchParams.get('strategy');
      const monthlyAmount = url.searchParams.get('monthlyAmount');
      // Under a household's own order the real server projects THAT order, so the what-if is
      // keyed on it. Answering with the avalanche what-if put "debt-free February 2029 — 4 months
      // sooner" under a plan that is not avalanche.
      if (customOrder !== null && strategy === null && monthlyAmount !== null) {
        const own = generated(
          `GET /api/debt-plan/projection?monthlyAmount=${canonicalAmount(monthlyAmount)} CUSTOM ${customOrder}`,
        );
        return own === undefined
          ? json(
              route,
              501,
              {
                title: 'Not mocked',
                status: 501,
                detail: `No generated what-if at ${monthlyAmount} for the custom order ${customOrder}. Add it to DemoFixtureIT and regenerate.`,
              },
              'application/problem+json',
            )
          : json(route, 200, own);
      }
      if (monthlyAmount === '955.00' || monthlyAmount === '955.0000') {
        return json(route, 200, DEMO_DEBT_WHAT_IF_PLUS_FIFTY);
      }
      if (strategy === 'SNOWBALL') {
        return json(route, 200, DEMO_DEBT_WHAT_IF);
      }
      if (monthlyAmount === null || monthlyAmount === DEMO_DEBT_PLAN.monthlyAmount) {
        return json(route, 200, DEMO_DEBT_PLAN);
      }
      return json(
        route,
        501,
        {
          title: 'Not mocked',
          status: 501,
          detail: `No generated projection for monthlyAmount=${monthlyAmount}. Add it to DemoFixtureIT and regenerate.`,
        },
        'application/problem+json',
      );
    }
    const debtPayments = url.pathname.match(/^\/api\/debts\/([^/]+)\/payments$/);
    if (method === 'GET' && debtPayments) {
      const page = DEMO_DEBT_PAYMENTS[debtPayments[1] as keyof typeof DEMO_DEBT_PAYMENTS];
      return page === undefined
        ? json(route, 404, DEMO_PROBLEMS.notFound, 'application/problem+json')
        : json(route, 200, page);
    }
    const debtId = url.pathname.match(/^\/api\/debts\/([^/]+)$/);
    if (method === 'GET' && debtId) {
      const detail =
        DEMO_DEBT_DETAIL[debtId[1] as keyof typeof DEMO_DEBT_DETAIL] ??
        DEMO_DEBT_STATE_DETAIL[debtId[1] as keyof typeof DEMO_DEBT_STATE_DETAIL];
      return detail === undefined
        ? json(route, 404, DEMO_PROBLEMS.notFound, 'application/problem+json')
        : json(route, 200, detail);
    }

    // ---- reports (Insights) -----------------------------------------------------------------
    // Served from report-api.generated.ts, which `DemoFixtureIT` GENERATES from the real
    // application and guards against drift. Keyed on exactly what was asked — the range, the month
    // and the representation — and loudly refused for anything it was not generated for: a report
    // for a range nobody asked the server about would be arithmetic the application never did.
    const report = url.pathname.match(/^\/api\/reports\/(income-and-spending|spending-by-category)$/);
    if (report) {
      if (session === 'anonymous') {
        return problem(route, DEMO_PROBLEMS.notAuthenticated);
      }
      if (session === 'no-household') {
        return problem(route, DEMO_PROBLEMS.notAMember);
      }
      if (method !== 'GET') {
        // The real server has no other method on these paths, and a test holds that.
        return json(
          route,
          405,
          { title: 'Method not allowed', status: 405, code: 'request-rejected' },
          'application/problem+json',
        );
      }
      const csv = (route.request().headers()['accept'] ?? '').includes('text/csv');
      const prefix =
        options.reports === 'empty'
          ? 'EMPTY '
          : options.reports === 'two-currencies'
            ? 'TWO_CURRENCIES '
            : '';
      const key = `${prefix}GET ${url.pathname}${url.search}${csv ? ' CSV' : ''}`;
      const answer = (prefix === '' ? DEMO_REPORTS : DEMO_REPORT_STATES)[key];
      if (answer === undefined) {
        return json(
          route,
          501,
          {
            title: 'Not mocked',
            status: 501,
            detail: `No generated report for "${key}". Add it to DemoFixtureIT and regenerate.`,
          },
          'application/problem+json',
        );
      }
      if (csv) {
        const from = url.searchParams.get('from') ?? '';
        const to = url.searchParams.get('to') ?? '';
        return route.fulfill({
          status: 200,
          contentType: 'text/csv;charset=UTF-8',
          headers: {
            'Content-Disposition': `attachment; filename="budget-owl-${report[1]}-${from}-${to}.csv"`,
            'Cache-Control': 'no-store',
          },
          body: String(answer),
        });
      }
      return json(route, 200, answer);
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
        {
          title: 'Not mocked',
          status: 501,
          detail: `No fixture for ${url.pathname}. Add it to tools/ui/fixtures/demo-data.ts.`,
        },
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
