/**
 * The canonical demo dataset.
 *
 * Every screenshot in the user guide and every UI check renders from this and nothing else.
 * There is no database behind it and no server to connect to — helpers/mock-api.ts serves it
 * from memory via Playwright request interception.
 *
 * Three properties matter:
 *
 *  1. DETERMINISTIC. The same values every run, so a re-captured screenshot differs only when
 *     the UI actually changed. Balances derived at runtime would make every capture a diff.
 *  2. OBVIOUSLY FICTIONAL. Names invented, emails at example.test (RFC 6761 reserves it and it
 *     can never resolve), amounts plausible but rounded-looking.
 *  3. REALISTIC IN SHAPE. A guide screenshot showing one account and one transaction teaches
 *     nothing. This covers a full month: several accounts, a credit card in the negative,
 *     income, regular spending, a transfer, and a budget with lines both under and over.
 *
 * Edit this to change what appears in the guide. Do not add data anywhere else.
 */

import { DEBT_API } from './debt-api.generated';
import { DEBT_STATES } from './debt-states.generated';
import { REPORT_API, REPORT_LEDGER } from './report-api.generated';
import { REPORT_STATES } from './report-states.generated';

export const DEMO_USER = {
  id: '11111111-1111-4111-8111-111111111111',
  displayName: 'Alex Rivera',
  email: 'alex@example.test',
} as const;

/**
 * The household is the ownership root (ADR-0017). Two members with different roles, so guide
 * screenshots and UI checks exercise the shared case rather than the single-user one — a
 * household of one would never surface a role, and roles are where the interesting bugs are.
 */
export const DEMO_HOUSEHOLD = {
  id: '22222222-2222-4222-8222-222222222222',
  name: 'Rivera Household',
  baseCurrency: 'USD',
} as const;

export const DEMO_MEMBERS = [
  {
    id: '44444444-4444-4444-8444-444444444441',
    userId: DEMO_USER.id,
    displayName: 'Alex Rivera',
    email: 'alex@example.test',
    role: 'OWNER',
    joinedAt: '2026-06-01T09:00:00Z',
    displayCurrency: null,
    locale: null,
  },
  {
    id: '44444444-4444-4444-8444-444444444442',
    userId: '33333333-3333-4333-8333-333333333333',
    displayName: 'Sam Rivera',
    email: 'sam@example.test',
    role: 'MEMBER',
    joinedAt: '2026-06-03T18:30:00Z',
    displayCurrency: 'EUR',
    locale: 'de-DE',
  },
  {
    id: '44444444-4444-4444-8444-444444444443',
    userId: '55555555-5555-4555-8555-555555555555',
    displayName: 'Jordan Rivera',
    email: 'jordan@example.test',
    role: 'VIEWER',
    joinedAt: '2026-07-12T12:00:00Z',
    displayCurrency: null,
    locale: null,
  },
] as const;

/** What `GET /api/auth/me` answers for the owner: the profile plus the one household's membership. */
export const DEMO_ME = {
  ...DEMO_USER,
  instanceAdmin: true,
  household: {
    householdId: DEMO_HOUSEHOLD.id,
    name: DEMO_HOUSEHOLD.name,
    baseCurrency: DEMO_HOUSEHOLD.baseCurrency,
    role: 'OWNER',
    membershipId: DEMO_MEMBERS[0].id,
    displayCurrency: null,
    locale: null,
  },
} as const;

/** The same endpoint for the household's second member, who may write but not administer. */
export const DEMO_ME_MEMBER = {
  id: DEMO_MEMBERS[1].userId,
  displayName: DEMO_MEMBERS[1].displayName,
  email: DEMO_MEMBERS[1].email,
  instanceAdmin: false,
  household: {
    ...DEMO_ME.household,
    role: 'MEMBER',
    membershipId: DEMO_MEMBERS[1].id,
    displayCurrency: 'EUR',
    locale: 'de-DE',
  },
} as const;

/**
 * The same endpoint for the household's viewer, who may read everything and write nothing.
 *
 * A `VIEWER` is the role every screen has to be checked against on its own: the server refuses
 * their writes with `403 read-only-role` whatever the client did, and the UI's job is to not
 * offer a button that is going to be refused. Without this fixture that case cannot be captured.
 */
export const DEMO_ME_VIEWER = {
  id: DEMO_MEMBERS[2].userId,
  displayName: DEMO_MEMBERS[2].displayName,
  email: DEMO_MEMBERS[2].email,
  instanceAdmin: false,
  household: { ...DEMO_ME.household, role: 'VIEWER', membershipId: DEMO_MEMBERS[2].id },
} as const;

/** A signed-in user nobody has invited: an empty state, not an error. */
export const DEMO_ME_NO_HOUSEHOLD = {
  id: '66666666-6666-4666-8666-666666666666',
  displayName: 'Casey Lindqvist',
  email: 'casey@example.test',
  instanceAdmin: false,
  household: null,
} as const;

/**
 * Devices: this browser, a second browser, and a phone. The labels are what the server stores — a
 * browser's User-Agent trimmed to 100 characters, and the user's own words for a phone. Fictional
 * but shaped like the real thing, because the UI summarises them.
 */
export const DEMO_DEVICES = [
  {
    id: 's-9f2c1d7a4b8e3f6051a2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718',
    kind: 'SESSION',
    label:
      'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36',
    createdAt: '2026-10-02T08:15:00Z',
    lastUsedAt: '2026-10-02T09:40:00Z',
    expiresAt: '2026-10-02T20:15:00Z',
    current: true,
  },
  {
    id: 's-1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809',
    kind: 'SESSION',
    label:
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15',
    createdAt: '2026-09-30T19:02:00Z',
    lastUsedAt: '2026-10-01T07:11:00Z',
    expiresAt: '2026-10-01T19:02:00Z',
    current: false,
  },
  {
    id: 't-77777777-7777-4777-8777-777777777777',
    kind: 'BEARER',
    label: "Alex's Pixel",
    createdAt: '2026-08-14T10:00:00Z',
    lastUsedAt: '2026-10-02T06:30:00Z',
    expiresAt: '2026-11-13T10:00:00Z',
    current: false,
  },
] as const;

/**
 * A freshly created invitation. The token is a placeholder that is obviously not one — a real
 * token is high-entropy and shown once. The link in a screenshot must never be mistakable for a
 * working credential.
 */
export const DEMO_INVITATION = {
  id: '88888888-8888-4888-8888-888888888888',
  email: 'riley@example.test',
  role: 'MEMBER',
  token: 'DEMO-TOKEN-NOT-A-REAL-CREDENTIAL',
  acceptPath: '/join/DEMO-TOKEN-NOT-A-REAL-CREDENTIAL',
  expiresAt: '2026-10-09T09:00:00Z',
} as const;

/** Tokens the mock treats specially, so a spec can reach each outcome by name. */
export const DEMO_TOKENS = {
  /** An invitation that works. */
  valid: 'demo-valid-invitation',
  /** Expired, revoked, used or never existed — the server answers all four identically. */
  unusable: 'demo-unusable-invitation',
  /** An invitation for an address with no account yet: a bare accept is refused as incomplete. */
  newAccount: 'demo-new-account-invitation',
} as const;

/** The RFC 7807 bodies the API sends, by stable code. The client renders the sentence. */
export const DEMO_PROBLEMS = {
  authenticationFailed: {
    type: 'https://budgetowl.app/errors/authentication-failed',
    title: 'Authentication failed',
    status: 401,
    code: 'authentication-failed',
    correlationId: 'demo0000000000000000000000000001',
  },
  notAuthenticated: {
    type: 'https://budgetowl.app/errors/not-authenticated',
    title: 'Not authenticated',
    status: 401,
    code: 'not-authenticated',
    correlationId: 'demo0000000000000000000000000002',
  },
  notAMember: {
    type: 'https://budgetowl.app/errors/not-a-member',
    title: 'Not a member of this household',
    status: 403,
    code: 'not-a-member',
    correlationId: 'demo0000000000000000000000000003',
  },
  invitationUnusable: {
    type: 'https://budgetowl.app/errors/invitation-unusable',
    title: 'This invitation cannot be used',
    status: 404,
    code: 'invitation-unusable',
    correlationId: 'demo0000000000000000000000000004',
  },
  lastOwner: {
    type: 'https://budgetowl.app/errors/last-owner',
    title: 'A household must keep at least one owner',
    status: 409,
    code: 'last-owner',
    correlationId: 'demo0000000000000000000000000005',
  },
  rateLimited: {
    type: 'https://budgetowl.app/errors/rate-limited',
    title: 'Too many attempts',
    status: 429,
    code: 'rate-limited',
    params: { retryAfterSeconds: 120 },
    correlationId: 'demo0000000000000000000000000006',
  },
  internal: {
    type: 'https://budgetowl.app/errors/internal-error',
    title: 'Internal error',
    status: 500,
    code: 'internal-error',
    correlationId: 'demo0000000000000000000000000007',
  },
  staleVersion: {
    type: 'https://budgetowl.app/errors/stale-version',
    title: 'Somebody else changed this first',
    status: 409,
    code: 'stale-version',
    correlationId: 'demo0000000000000000000000000009',
  },
  accountNotEmpty: {
    type: 'https://budgetowl.app/errors/account-not-empty',
    title: 'This account has transactions',
    status: 409,
    code: 'account-not-empty',
    correlationId: 'demo0000000000000000000000000010',
  },
  notInstanceAdmin: {
    type: 'https://budgetowl.app/errors/not-instance-admin',
    title: 'Not the instance administrator',
    status: 403,
    code: 'not-instance-admin',
    correlationId: 'demo0000000000000000000000000011',
  },
  passwordLoginLocked: {
    type: 'https://budgetowl.app/errors/password-login-locked',
    title: 'Password sign-in cannot be turned off yet',
    status: 409,
    code: 'password-login-locked',
    correlationId: 'demo0000000000000000000000000012',
  },
  notFound: {
    type: 'https://budgetowl.app/errors/not-found',
    title: 'Not found',
    status: 404,
    code: 'not-found',
    correlationId: 'demo0000000000000000000000000014',
  },
  invalidIssuerUri: {
    type: 'https://budgetowl.app/errors/validation-failed',
    title: 'Validation failed',
    status: 400,
    code: 'validation-failed',
    params: { field: 'oidcIssuerUri' },
    correlationId: 'demo0000000000000000000000000013',
  },
} as const;

/**
 * The operator's instance, as `GET /api/admin/instance` answers it (docs/features/admin-console.md).
 *
 * **Not one figure of anybody's money is here, and there must never be** (ADR-0026). The count is
 * of accounts — people who can sign in — which is why it is derived from DEMO_ADMIN_ACCOUNTS
 * rather than being a number invented beside them.
 */
export const DEMO_INSTANCE = {
  version: '0.1.0',
  setupCompletedAt: '2026-06-01T08:55:00Z',
  administratorId: DEMO_USER.id,
  administratorEmail: DEMO_USER.email,
  administratorName: DEMO_USER.displayName,
  accountCount: 4,
} as const;

/**
 * How this instance lets people sign in: `GET /api/admin/settings`, in its default state.
 *
 * **There is no client secret field, because the response has none** — not masked, absent
 * (`AdminResponses`). `oidcClientSecretSet` is the only thing said about one, and a fixture
 * carrying a secret-shaped string would be the first place in this repository to put one on a
 * screen.
 *
 * `passwordLoginLocked` is deliberately NOT stored here: the mock derives it the way the server
 * does — no provider, or no `OWNER` has signed in through it (`AdminService`) — so no spec can set
 * up an instance whose lock state contradicts its own configuration.
 */
export const DEMO_INSTANCE_SETTINGS = {
  registrationOpen: false,
  passwordLoginEnabled: true,
  oidcEnabled: false,
  oidcIssuerUri: null,
  oidcClientId: null,
  oidcClientSecretSet: false,
  oidcProvisioningEnabled: false,
  oidcOwnerLoginAt: null,
  updatedAt: '2026-06-01T08:55:00Z',
  version: 3,
} as const;

/**
 * The same instance with a provider configured and an `OWNER`'s sign-in through it on record.
 *
 * The only state in which password sign-in can be turned off at all, so it is the only one that
 * can show that switch, its recovery warning, and the provider's own fields.
 */
export const DEMO_INSTANCE_SETTINGS_OIDC = {
  ...DEMO_INSTANCE_SETTINGS,
  oidcEnabled: true,
  oidcIssuerUri: 'https://id.example.test/realms/household',
  oidcClientId: 'budget-owl',
  oidcClientSecretSet: true,
  oidcOwnerLoginAt: '2026-11-18T19:12:00Z',
  updatedAt: '2026-11-18T19:30:00Z',
  version: 7,
} as const;

/**
 * Who can sign in to this instance: `GET /api/admin/users`.
 *
 * Operator data, not household data — no balance, no household role, and no password hash to have
 * one. Four accounts rather than one, because the list has to show the `Administrator` tag on
 * exactly one row (ADR-0029), the reader's own row, a `DISABLED` status, and a name and address
 * long enough to find out what a row does at phone width.
 */
export const DEMO_ADMIN_ACCOUNTS = [
  {
    id: DEMO_USER.id,
    email: DEMO_USER.email,
    displayName: DEMO_USER.displayName,
    status: 'ACTIVE',
    instanceAdmin: true,
    createdAt: '2026-06-01T08:55:00Z',
  },
  {
    id: DEMO_MEMBERS[1].userId,
    email: DEMO_MEMBERS[1].email,
    displayName: DEMO_MEMBERS[1].displayName,
    status: 'ACTIVE',
    instanceAdmin: false,
    createdAt: '2026-06-03T18:28:00Z',
  },
  {
    id: DEMO_MEMBERS[2].userId,
    email: DEMO_MEMBERS[2].email,
    displayName: DEMO_MEMBERS[2].displayName,
    status: 'ACTIVE',
    instanceAdmin: false,
    createdAt: '2026-07-12T11:58:00Z',
  },
  {
    id: '99999999-9999-4999-8999-999999999999',
    email: 'riley.fitzgerald-okonkwo@example.test',
    displayName: 'Riley Fitzgerald-Okonkwo',
    status: 'DISABLED',
    instanceAdmin: false,
    createdAt: '2026-09-28T07:40:00Z',
  },
] as const;

/**
 * The accounts, in the shape `AccountResponse` really sends (docs/features/accounts.md).
 *
 * Four things here are deliberate rather than decoration:
 *
 *  1. **Amounts are strings**, as they are on the wire (ADR-0006). A JSON number in this file
 *     would be a fixture teaching the one thing the API refuses — and testing-style.md is explicit
 *     that fixtures are read as examples and get copied.
 *  2. **`balance` is not `openingBalance`.** Each balance is its opening balance plus the
 *     DEMO_TRANSACTIONS recorded against that account, worked out once so the two agree. A client
 *     that assumed the two fields were equal would look correct against a sloppier fixture and
 *     break the moment a transaction existed.
 *  3. **A second currency**, because the accounts screen only groups and subtotals by currency
 *     when there is more than one, and a household with one would never show it (ADR-0022).
 *  4. **An archived account**, so the toggle that reveals them has something to reveal — and it
 *     keeps its balance, because archiving hides an account rather than zeroing it.
 *
 * `lastFour` is invented like everything else here, and arrives separately from the name: the
 * client composes `name •1234` so a right-to-left locale can reorder it (ADR-0023).
 */
export const DEMO_ACCOUNTS = [
  {
    id: 'acc-0001',
    name: 'Everyday Checking',
    lastFour: '4417',
    type: 'CHECKING',
    currency: 'USD',
    openingBalance: '1641.75',
    balance: '2480.15',
    empty: false,
    archived: false,
    createdAt: '2026-06-01T09:05:00Z',
    updatedAt: '2026-08-20T11:20:00Z',
    version: 2,
  },
  {
    id: 'acc-0002',
    name: 'Emergency Fund',
    lastFour: null,
    type: 'SAVINGS',
    currency: 'USD',
    openingBalance: '5620.00',
    balance: '6120.00',
    empty: false,
    archived: false,
    createdAt: '2026-06-01T09:07:00Z',
    updatedAt: '2026-08-16T10:02:00Z',
    version: 1,
  },
  {
    id: 'acc-0003',
    name: 'Travel Card',
    lastFour: '8820',
    type: 'CREDIT_CARD',
    currency: 'USD',
    openingBalance: '-215.24',
    balance: '-318.44',
    empty: false,
    archived: false,
    createdAt: '2026-06-02T18:40:00Z',
    updatedAt: '2026-08-09T08:15:00Z',
    version: 3,
  },
  {
    id: 'acc-0004',
    name: 'Berlin Savings',
    lastFour: null,
    type: 'SAVINGS',
    currency: 'EUR',
    openingBalance: '1200.00',
    balance: '1200.00',
    empty: true,
    archived: false,
    createdAt: '2026-06-04T07:30:00Z',
    updatedAt: '2026-06-04T07:30:00Z',
    version: 0,
  },
  {
    id: 'acc-0005',
    name: 'Old Joint Account',
    lastFour: '0041',
    type: 'CHECKING',
    currency: 'USD',
    openingBalance: '84.60',
    balance: '84.60',
    empty: true,
    archived: true,
    createdAt: '2026-06-01T09:12:00Z',
    updatedAt: '2026-07-18T16:45:00Z',
    version: 4,
  },
] as const;

/**
 * The default list is unarchived only; `?includeArchived=true` is what returns all of them.
 *
 * `empty` is its own field rather than something a client derives from the two balances: an account
 * with a +50 and a -50 nets to nothing and still has history, so it can be neither deleted nor
 * re-denominated. These fixtures have transactions, so every account that appears in
 * `DEMO_TRANSACTIONS` reports `empty: false`.
 */
export const DEMO_ACCOUNTS_ACTIVE = DEMO_ACCOUNTS.filter((account) => !account.archived);

/**
 * The household's categories, in the shape `CategoryResponse` actually sends.
 *
 * Two levels, and the tree arrives FLAT with a `parentId` — the endpoint is paginated like every
 * other collection, and a page boundary must not be able to fall between a parent and its
 * children, so the client assembles the tree (docs/features/categories.md).
 *
 * `parentName` is sent alongside so a child can be rendered on its own, in a transaction row or a
 * picker, without a second lookup. It is never pre-joined into one label: the client composes
 * `Bills → Utilities` through a translated template so a right-to-left locale can reorder it.
 */
export const DEMO_CATEGORIES = [
  {
    id: 'cat-0001',
    name: 'Income',
    kind: 'INCOME',
    parentId: null,
    parentName: null,
    archived: false,
    createdAt: '2026-06-01T09:00:00Z',
    updatedAt: '2026-06-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'cat-0002',
    name: 'Salary',
    kind: 'INCOME',
    parentId: 'cat-0001',
    parentName: 'Income',
    archived: false,
    createdAt: '2026-06-01T09:00:00Z',
    updatedAt: '2026-06-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'cat-0003',
    name: 'Bills',
    kind: 'EXPENSE',
    parentId: null,
    parentName: null,
    archived: false,
    createdAt: '2026-06-01T09:00:00Z',
    updatedAt: '2026-06-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'cat-0004',
    name: 'Rent',
    kind: 'EXPENSE',
    parentId: 'cat-0003',
    parentName: 'Bills',
    archived: false,
    createdAt: '2026-06-01T09:00:00Z',
    updatedAt: '2026-06-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'cat-0005',
    name: 'Utilities',
    kind: 'EXPENSE',
    parentId: 'cat-0003',
    parentName: 'Bills',
    archived: false,
    createdAt: '2026-06-01T09:00:00Z',
    updatedAt: '2026-06-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'cat-0006',
    name: 'Groceries',
    kind: 'EXPENSE',
    parentId: null,
    parentName: null,
    archived: false,
    createdAt: '2026-06-01T09:00:00Z',
    updatedAt: '2026-06-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'cat-0007',
    name: 'Transport',
    kind: 'EXPENSE',
    parentId: null,
    parentName: null,
    archived: false,
    createdAt: '2026-06-01T09:00:00Z',
    updatedAt: '2026-06-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'cat-0008',
    name: 'Eating Out',
    kind: 'EXPENSE',
    parentId: null,
    parentName: null,
    archived: false,
    createdAt: '2026-06-01T09:00:00Z',
    updatedAt: '2026-06-01T09:00:00Z',
    version: 0,
  },
  // Archived: kept so past transactions still read correctly, absent from every picker.
  {
    id: 'cat-0009',
    name: 'Wedding',
    kind: 'EXPENSE',
    parentId: null,
    parentName: null,
    archived: true,
    createdAt: '2026-06-01T09:00:00Z',
    updatedAt: '2026-07-20T09:00:00Z',
    version: 1,
  },
] as const;

export const DEMO_CATEGORIES_ACTIVE = DEMO_CATEGORIES.filter((category) => !category.archived);

/**
 * The ledger, in the shape `TransactionResponse` actually sends.
 *
 * Points worth keeping as the fixtures change:
 *
 * - **`amount` is a signed STRING** (ADR-0006). Negative is money leaving. There is no separate
 *   income/expense flag, because the sign already says it.
 * - **There is no currency on a transaction in the database** — it is denominated by its account —
 *   but the response carries the account's, because an amount means nothing without one.
 * - **`categoryId: null` is "Needs a category"**, a real state the UI shows and the work queue a
 *   person acts on. `txn-0010` is deliberately in it.
 * - **A transfer is two rows sharing a group id**, never one row with two accounts. Neither leg is
 *   income or expense (`txn-0008`, `txn-0009`), and both carry the same `transferGroupId`: that id
 *   is what lets a ledger row edit or delete the *pair*, which is the only thing either half can
 *   be written to as (ADR-0033).
 */
export const DEMO_TRANSACTIONS = [
  {
    id: 'txn-0010',
    accountId: 'acc-0001',
    accountName: 'Everyday Checking',
    date: '2026-08-20',
    amount: '-92.15',
    currency: 'USD',
    payeeId: 'pay-0003',
    payeeName: 'Greenfield Market',
    categoryId: null,
    categoryName: null,
    categoryParentName: null,
    note: null,
    status: 'PENDING',
    transfer: false,
    transferGroupId: null,
    createdAt: '2026-08-20T18:30:00Z',
    updatedAt: '2026-08-20T18:30:00Z',
    version: 0,
  },
  {
    id: 'txn-0009',
    accountId: 'acc-0002',
    accountName: 'Emergency Fund',
    date: '2026-08-16',
    amount: '500.00',
    currency: 'USD',
    payeeId: null,
    payeeName: null,
    categoryId: null,
    categoryName: null,
    categoryParentName: null,
    note: 'Transfer from Everyday Checking',
    status: 'CLEARED',
    transfer: true,
    transferGroupId: 'tgr-0001',
    createdAt: '2026-08-16T08:00:00Z',
    updatedAt: '2026-08-16T08:00:00Z',
    version: 0,
  },
  {
    id: 'txn-0008',
    accountId: 'acc-0001',
    accountName: 'Everyday Checking',
    date: '2026-08-16',
    amount: '-500.00',
    currency: 'USD',
    payeeId: null,
    payeeName: null,
    categoryId: null,
    categoryName: null,
    categoryParentName: null,
    note: 'Transfer to Emergency Fund',
    status: 'CLEARED',
    transfer: true,
    transferGroupId: 'tgr-0001',
    createdAt: '2026-08-16T08:00:00Z',
    updatedAt: '2026-08-16T08:00:00Z',
    version: 0,
  },
  {
    id: 'txn-0007',
    accountId: 'acc-0001',
    accountName: 'Everyday Checking',
    date: '2026-08-15',
    amount: '-128.30',
    currency: 'USD',
    payeeId: 'pay-0006',
    payeeName: 'Metro Power',
    categoryId: 'cat-0005',
    categoryName: 'Utilities',
    categoryParentName: 'Bills',
    note: null,
    status: 'CLEARED',
    transfer: false,
    transferGroupId: null,
    createdAt: '2026-08-15T09:00:00Z',
    updatedAt: '2026-08-15T09:00:00Z',
    version: 0,
  },
  {
    id: 'txn-0006',
    accountId: 'acc-0001',
    accountName: 'Everyday Checking',
    date: '2026-08-12',
    amount: '-104.75',
    currency: 'USD',
    payeeId: 'pay-0003',
    payeeName: 'Greenfield Market',
    categoryId: 'cat-0006',
    categoryName: 'Groceries',
    categoryParentName: null,
    note: 'big shop',
    status: 'CLEARED',
    transfer: false,
    transferGroupId: null,
    createdAt: '2026-08-12T17:10:00Z',
    updatedAt: '2026-08-12T17:10:00Z',
    version: 0,
  },
  {
    id: 'txn-0005',
    accountId: 'acc-0003',
    accountName: 'Travel Card',
    date: '2026-08-09',
    amount: '-41.20',
    currency: 'USD',
    payeeId: 'pay-0005',
    payeeName: 'Corner Kitchen',
    categoryId: 'cat-0008',
    categoryName: 'Eating Out',
    categoryParentName: null,
    note: null,
    status: 'CLEARED',
    transfer: false,
    transferGroupId: null,
    createdAt: '2026-08-09T20:05:00Z',
    updatedAt: '2026-08-09T20:05:00Z',
    version: 0,
  },
  {
    id: 'txn-0004',
    accountId: 'acc-0003',
    accountName: 'Travel Card',
    date: '2026-08-05',
    amount: '-62.00',
    currency: 'USD',
    payeeId: 'pay-0004',
    payeeName: 'City Transit',
    categoryId: 'cat-0007',
    categoryName: 'Transport',
    categoryParentName: null,
    note: null,
    status: 'CLEARED',
    transfer: false,
    transferGroupId: null,
    createdAt: '2026-08-05T08:40:00Z',
    updatedAt: '2026-08-05T08:40:00Z',
    version: 0,
  },
  {
    id: 'txn-0003',
    accountId: 'acc-0001',
    accountName: 'Everyday Checking',
    date: '2026-08-03',
    amount: '-86.40',
    currency: 'USD',
    payeeId: 'pay-0003',
    payeeName: 'Greenfield Market',
    categoryId: 'cat-0006',
    categoryName: 'Groceries',
    categoryParentName: null,
    note: null,
    status: 'CLEARED',
    transfer: false,
    transferGroupId: null,
    createdAt: '2026-08-03T16:20:00Z',
    updatedAt: '2026-08-03T16:20:00Z',
    version: 0,
  },
  {
    id: 'txn-0002',
    accountId: 'acc-0001',
    accountName: 'Everyday Checking',
    date: '2026-08-01',
    amount: '-1450.00',
    currency: 'USD',
    payeeId: 'pay-0002',
    payeeName: 'Fairview Lettings',
    categoryId: 'cat-0004',
    categoryName: 'Rent',
    categoryParentName: 'Bills',
    note: null,
    status: 'CLEARED',
    transfer: false,
    transferGroupId: null,
    createdAt: '2026-08-01T07:00:00Z',
    updatedAt: '2026-08-01T07:00:00Z',
    version: 0,
  },
  {
    id: 'txn-0001',
    accountId: 'acc-0001',
    accountName: 'Everyday Checking',
    date: '2026-08-01',
    amount: '3200.00',
    currency: 'USD',
    payeeId: 'pay-0001',
    payeeName: 'Northwind Systems',
    categoryId: 'cat-0002',
    categoryName: 'Salary',
    categoryParentName: 'Income',
    note: null,
    status: 'CLEARED',
    transfer: false,
    transferGroupId: null,
    createdAt: '2026-08-01T06:00:00Z',
    updatedAt: '2026-08-01T06:00:00Z',
    version: 0,
  },
] as const;

/**
 * The transfer `txn-0008` and `txn-0009` are the two halves of, as `TransferResponse` sends it.
 *
 * **Addressed by its group id**, never by either leg's transaction id: the pair is the object, and
 * naming one leg would make the other's id a second name for the same thing (ADR-0033).
 *
 * Both fixture accounts are in USD, so `crossCurrency` is false, `received` is the same figure as
 * `sent`, and `impliedRate` is null — there is no rate, and we would not invent one (ADR-0022).
 * `version` is the **pair's**: the sum of the two legs', which is why it is two and not one.
 */
export const DEMO_TRANSFER = {
  id: 'tgr-0001',
  fromAccountId: 'acc-0001',
  fromAccountName: 'Everyday Checking',
  toAccountId: 'acc-0002',
  toAccountName: 'Emergency Fund',
  date: '2026-08-16',
  sent: '500.00',
  sentCurrency: 'USD',
  received: '500.00',
  receivedCurrency: 'USD',
  crossCurrency: false,
  impliedRate: null,
  note: 'Transfer to Emergency Fund',
  createdAt: '2026-08-16T08:00:00Z',
  updatedAt: '2026-08-16T08:00:00Z',
  version: 0,
} as const;

/**
 * The ledger's page total, as the server computes it: the sum of this page's amounts.
 *
 * Every fixture row is in USD, so there is one. A page that mixed currencies would carry `null`
 * here and the screen says so rather than converting (ADR-0022).
 */
export const DEMO_LEDGER_PAGE_TOTAL = '1235.20';

/** Payee names the entry form suggests. The server returns names only; it resolves the id. */
export const DEMO_PAYEE_SUGGESTIONS = [
  'City Transit',
  'Corner Kitchen',
  'Fairview Lettings',
  'Greenfield Market',
  'Metro Power',
  'Northwind Systems',
] as const;

/**
 * The household's envelopes for one month, in the shape `EnvelopeResponse` sends.
 *
 * `remaining` is computed by the server and is **negative** on `env-0004`, deliberately: an
 * overspent envelope is the case the screens most need to get right, and a fixture set where
 * everything is comfortably under plan would never show it.
 *
 * `period` is a LABEL (ADR-0031) — "the envelope for August 2026" — not a date range.
 */
export const DEMO_ENVELOPES = [
  {
    id: 'env-0001',
    name: 'Groceries',
    period: '2026-08',
    categoryId: 'cat-0006',
    categoryName: 'Groceries',
    categoryParentName: null,
    amount: '400.0000',
    spent: '283.3000',
    remaining: '116.7000',
    currency: 'USD',
    entryCount: 3,
    empty: false,
    archived: false,
    createdAt: '2026-08-01T09:00:00Z',
    updatedAt: '2026-08-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'env-0002',
    name: 'Adhoc',
    period: '2026-08',
    categoryId: null,
    categoryName: null,
    categoryParentName: null,
    amount: '150.0000',
    spent: '0.0000',
    remaining: '150.0000',
    currency: 'USD',
    entryCount: 0,
    empty: true,
    archived: false,
    createdAt: '2026-08-01T09:00:00Z',
    updatedAt: '2026-08-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'env-0003',
    name: 'Fuel',
    period: '2026-08',
    categoryId: 'cat-0007',
    categoryName: 'Transport',
    categoryParentName: null,
    amount: '120.0000',
    spent: '62.0000',
    remaining: '58.0000',
    currency: 'USD',
    entryCount: 1,
    empty: false,
    archived: false,
    createdAt: '2026-08-01T09:00:00Z',
    updatedAt: '2026-08-01T09:00:00Z',
    version: 0,
  },
  // Overspent on purpose: the case the design most needs to handle honestly.
  {
    id: 'env-0004',
    name: 'Eating Out',
    period: '2026-08',
    categoryId: 'cat-0008',
    categoryName: 'Eating Out',
    categoryParentName: null,
    amount: '30.0000',
    spent: '41.2000',
    remaining: '-11.2000',
    currency: 'USD',
    entryCount: 1,
    empty: false,
    archived: false,
    createdAt: '2026-08-01T09:00:00Z',
    updatedAt: '2026-08-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'env-0005',
    name: 'Summer trip',
    period: '2026-08',
    categoryId: null,
    categoryName: null,
    categoryParentName: null,
    amount: '250.0000',
    spent: '0.0000',
    remaining: '250.0000',
    currency: 'USD',
    entryCount: 0,
    empty: true,
    archived: true,
    createdAt: '2026-08-01T09:00:00Z',
    updatedAt: '2026-08-10T09:00:00Z',
    version: 1,
  },
] as const;

export const DEMO_ENVELOPES_ACTIVE = DEMO_ENVELOPES.filter((envelope) => !envelope.archived);

/**
 * One envelope's log, with both sources present.
 *
 * `MANUAL` was typed; `SYNCED` is a transaction somebody attached. Both are a person's decision —
 * the app never files a transaction on its own — and the log says which on every row.
 */
export const DEMO_ENVELOPE_ENTRIES = [
  {
    id: 'ent-0003',
    envelopeId: 'env-0001',
    date: '2026-08-12',
    amount: '104.7500',
    currency: 'USD',
    payeeId: 'pay-0003',
    payeeName: 'Greenfield Market',
    note: 'big shop',
    source: 'SYNCED',
    transactionId: 'txn-0006',
    createdAt: '2026-08-12T17:10:00Z',
    updatedAt: '2026-08-12T17:10:00Z',
    version: 0,
  },
  {
    id: 'ent-0002',
    envelopeId: 'env-0001',
    date: '2026-08-06',
    amount: '92.1500',
    currency: 'USD',
    payeeId: null,
    payeeName: null,
    note: null,
    source: 'MANUAL',
    transactionId: null,
    createdAt: '2026-08-06T18:02:00Z',
    updatedAt: '2026-08-06T18:02:00Z',
    version: 0,
  },
  {
    id: 'ent-0001',
    envelopeId: 'env-0001',
    date: '2026-08-03',
    amount: '86.4000',
    currency: 'USD',
    payeeId: 'pay-0003',
    payeeName: 'Greenfield Market',
    note: null,
    source: 'SYNCED',
    transactionId: 'txn-0003',
    createdAt: '2026-08-03T16:20:00Z',
    updatedAt: '2026-08-03T16:20:00Z',
    version: 0,
  },
] as const;

/** The months this household has envelopes for, newest first. */
export const DEMO_ENVELOPE_PERIODS = ['2026-08', '2026-07'] as const;

/**
 * The household's bills for one month, in the shape `BillResponse` sends.
 *
 * Chosen so that every case the screen has to get right is on it at once, because a fixture set
 * where everything is a comfortable monthly direct debit would never show any of them:
 *
 * - **`bil-0002` is weekly**, with four occurrences in August and the third one paid. A screen
 *   that renders one row per bill rather than one per occurrence is visibly wrong here.
 * - **`bil-0003` varies** and was paid £43.10 over a £120 plan, outside its £22 tolerance, so it
 *   is `surprising` and both figures have to show.
 * - **`bil-0004` is overdue** — due on the 10th and still DUE — and nothing had to sweep it.
 * - **`bil-0005` is irregular**: listed, projecting nothing, which is what ADR-0032 asks for.
 * - **`bil-0006` is retired**, so it projects nothing and keeps the record it already had.
 * - **`bil-0007` is yearly** and falls outside August, so it is listed with no occurrences.
 *
 * An occurrence with `recorded: false` has no row behind it and no `version`: it is DUE because
 * the schedule says so, not because anybody said so.
 */
const occurrence = (
  billId: string,
  dueDate: string,
  expectedAmount: string,
  extra: Record<string, unknown> = {},
) => ({
  billId,
  dueDate,
  expectedAmount,
  status: 'DUE',
  actualAmount: null,
  paidDate: null,
  transactionId: null,
  statusSource: 'MANUAL',
  recorded: false,
  overdue: false,
  surprising: false,
  version: null,
  ...extra,
});

const PAID = { status: 'PAID', statusSource: 'MANUAL', recorded: true, version: 0 };

export const DEMO_BILLS = [
  {
    id: 'bil-0001',
    name: 'Rent',
    categoryId: 'cat-0004',
    categoryName: 'Rent',
    categoryParentName: null,
    expectedAmount: '1450.0000',
    currency: 'USD',
    schedule: { kind: 'MONTHLY', dayOfMonth: 1, month: null, dayOfWeek: null, anchorDate: null },
    amountVaries: false,
    tolerance: null,
    active: true,
    occurrences: [
      occurrence('bil-0001', '2026-08-01', '1450.0000', {
        ...PAID,
        actualAmount: '1450.0000',
        paidDate: '2026-08-01',
      }),
    ],
    createdAt: '2026-07-01T09:00:00Z',
    updatedAt: '2026-08-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'bil-0002',
    name: 'Cleaner',
    categoryId: null,
    categoryName: null,
    categoryParentName: null,
    expectedAmount: '40.0000',
    currency: 'USD',
    schedule: {
      kind: 'WEEKLY',
      dayOfMonth: null,
      month: null,
      dayOfWeek: 'FRIDAY',
      anchorDate: null,
    },
    amountVaries: false,
    tolerance: null,
    active: true,
    occurrences: [
      occurrence('bil-0002', '2026-08-07', '40.0000', {
        ...PAID,
        actualAmount: '40.0000',
        paidDate: '2026-08-07',
      }),
      occurrence('bil-0002', '2026-08-14', '40.0000', {
        ...PAID,
        actualAmount: '40.0000',
        paidDate: '2026-08-14',
      }),
      occurrence('bil-0002', '2026-08-21', '40.0000'),
      occurrence('bil-0002', '2026-08-28', '40.0000'),
    ],
    createdAt: '2026-07-01T09:00:00Z',
    updatedAt: '2026-08-14T09:00:00Z',
    version: 0,
  },
  {
    id: 'bil-0003',
    name: 'Phone',
    categoryId: 'cat-0005',
    categoryName: 'Utilities',
    categoryParentName: null,
    expectedAmount: '120.0000',
    currency: 'USD',
    schedule: { kind: 'MONTHLY', dayOfMonth: 14, month: null, dayOfWeek: null, anchorDate: null },
    amountVaries: true,
    tolerance: '22.0000',
    active: true,
    occurrences: [
      // £43.10 over a £120 plan that allows £22 of variation, so the app says so rather than
      // letting it pass as ordinary.
      occurrence('bil-0003', '2026-08-14', '120.0000', {
        ...PAID,
        actualAmount: '163.1000',
        paidDate: '2026-08-15',
        surprising: true,
      }),
    ],
    createdAt: '2026-07-01T09:00:00Z',
    updatedAt: '2026-08-15T09:00:00Z',
    version: 0,
  },
  {
    id: 'bil-0004',
    name: 'Council tax',
    categoryId: null,
    categoryName: null,
    categoryParentName: null,
    expectedAmount: '186.0000',
    currency: 'USD',
    schedule: { kind: 'MONTHLY', dayOfMonth: 10, month: null, dayOfWeek: null, anchorDate: null },
    amountVaries: false,
    tolerance: null,
    active: true,
    occurrences: [occurrence('bil-0004', '2026-08-10', '186.0000', { overdue: true })],
    createdAt: '2026-07-01T09:00:00Z',
    updatedAt: '2026-07-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'bil-0005',
    name: 'Vet',
    categoryId: null,
    categoryName: null,
    categoryParentName: null,
    expectedAmount: '0.0000',
    currency: 'USD',
    schedule: {
      kind: 'IRREGULAR',
      dayOfMonth: null,
      month: null,
      dayOfWeek: null,
      anchorDate: null,
    },
    amountVaries: false,
    tolerance: null,
    active: true,
    occurrences: [],
    createdAt: '2026-07-01T09:00:00Z',
    updatedAt: '2026-07-01T09:00:00Z',
    version: 0,
  },
  {
    // The car loan's minimum IS this bill row. It exists so the counted-once rule is visible on
    // the page rather than merely true in the code: 185 + 342 + 118 = 645 committed to debt, of
    // which only 185 + 118 = 303 is new to "what's left", because this 342 is already a bill.
    id: 'bil-0008',
    name: 'Car payment',
    categoryId: 'cat-0007',
    categoryName: 'Transport',
    categoryParentName: null,
    expectedAmount: '342.0000',
    currency: 'USD',
    schedule: { kind: 'MONTHLY', dayOfMonth: 5, month: null, dayOfWeek: null, anchorDate: null },
    amountVaries: false,
    tolerance: null,
    active: true,
    occurrences: [occurrence('bil-0008', '2026-08-05', '342.0000')],
    createdAt: '2026-07-01T09:00:00Z',
    updatedAt: '2026-07-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'bil-0007',
    name: 'Car insurance',
    categoryId: null,
    categoryName: null,
    categoryParentName: null,
    expectedAmount: '480.0000',
    currency: 'USD',
    schedule: { kind: 'YEARLY', dayOfMonth: 30, month: 4, dayOfWeek: null, anchorDate: null },
    amountVaries: false,
    tolerance: null,
    active: true,
    occurrences: [],
    createdAt: '2026-07-01T09:00:00Z',
    updatedAt: '2026-07-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'bil-0006',
    name: 'Old gym',
    categoryId: null,
    categoryName: null,
    categoryParentName: null,
    expectedAmount: '35.0000',
    currency: 'USD',
    schedule: { kind: 'MONTHLY', dayOfMonth: 5, month: null, dayOfWeek: null, anchorDate: null },
    amountVaries: false,
    tolerance: null,
    active: false,
    occurrences: [],
    createdAt: '2026-05-01T09:00:00Z',
    updatedAt: '2026-07-20T09:00:00Z',
    version: 1,
  },
] as const;

export const DEMO_BILLS_ACTIVE = DEMO_BILLS.filter((bill) => bill.active);

/**
 * The household's income sources, in the shape `IncomeResponse` sends.
 *
 * `inc-0002` is **fortnightly**, arriving on the 7th and the 21st of August — two arrivals, where
 * another month holds three. The figure is the month counted, not a monthly average, and `dates`
 * is what the screen shows to explain the total.
 */
export const DEMO_INCOME = [
  {
    id: 'inc-0001',
    name: 'Salary',
    amount: '3200.0000',
    currency: 'USD',
    schedule: { kind: 'MONTHLY', dayOfMonth: 28, month: null, dayOfWeek: null, anchorDate: null },
    accountId: 'acc-0001',
    accountName: 'Everyday Checking',
    sourceOfTruth: 'MANUAL_ESTIMATE',
    active: true,
    dates: ['2026-08-28'],
    expectedForPeriod: '3200.0000',
    createdAt: '2026-07-01T09:00:00Z',
    updatedAt: '2026-07-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'inc-0002',
    name: 'Weekend work',
    amount: '220.0000',
    currency: 'USD',
    schedule: {
      kind: 'FORTNIGHTLY',
      dayOfMonth: null,
      month: null,
      dayOfWeek: 'FRIDAY',
      anchorDate: '2026-08-07',
    },
    accountId: null,
    accountName: null,
    sourceOfTruth: 'MANUAL_ESTIMATE',
    active: true,
    dates: ['2026-08-07', '2026-08-21'],
    expectedForPeriod: '440.0000',
    createdAt: '2026-07-01T09:00:00Z',
    updatedAt: '2026-07-01T09:00:00Z',
    version: 0,
  },
  {
    id: 'inc-0003',
    name: 'Old retainer',
    amount: '500.0000',
    currency: 'USD',
    schedule: { kind: 'MONTHLY', dayOfMonth: 15, month: null, dayOfWeek: null, anchorDate: null },
    accountId: null,
    accountName: null,
    sourceOfTruth: 'MANUAL_ESTIMATE',
    active: false,
    dates: [],
    expectedForPeriod: '0.0000',
    createdAt: '2026-04-01T09:00:00Z',
    updatedAt: '2026-06-30T09:00:00Z',
    version: 1,
  },
] as const;

export const DEMO_INCOME_ACTIVE = DEMO_INCOME.filter((source) => source.active);

/**
 * What the household has left to spend in August, in the shape `SummaryResponse` sends.
 *
 * **Every figure is derived from the fixtures above, not typed independently**, so a screen built
 * on these cannot show a headline its own blocks disagree with — which is the defect the single
 * summary endpoint exists to prevent, and it would be silly for the fixtures to reintroduce it.
 *
 * Bills count whether or not they are paid: rent that has gone is not money still available.
 */
const sum = (figures: readonly string[]) =>
  figures.reduce((total, figure) => total + Number(figure), 0);

const BILL_FIGURES = DEMO_BILLS_ACTIVE.flatMap((bill) =>
  bill.occurrences
    .filter((one) => one.status !== 'SKIPPED')
    .map((one) => ({
      amount: one.status === 'PAID' ? (one.actualAmount ?? one.expectedAmount) : one.expectedAmount,
      outstanding: one.status !== 'PAID',
      overdue: one.overdue,
    })),
);

const BILLS_TOTAL = sum(BILL_FIGURES.map((one) => one.amount));
const BILLS_OUTSTANDING = sum(
  BILL_FIGURES.filter((one) => one.outstanding).map((one) => one.amount),
);
const BILLS_OVERDUE = sum(BILL_FIGURES.filter((one) => one.overdue).map((one) => one.amount));
const INCOME_TOTAL = sum(DEMO_INCOME_ACTIVE.map((source) => source.expectedForPeriod));
const ENVELOPES_ALLOCATED = sum(
  DEMO_ENVELOPES_ACTIVE.filter((envelope) => envelope.period === '2026-08').map((e) => e.amount),
);

/**
 * The debt responses, **generated from the real application** and not written by hand.
 *
 * `DemoFixtureIT` seeds these three debts and this plan through the real endpoints, reads the real
 * responses back, canonicalises the ids and writes `debt-api.json`. It then fails the backend gate
 * if the committed file stops matching what the application returns.
 *
 * It exists because a payoff plan is not data a reviewer can check by eye. A balance is; two
 * projections, a debt-free month, a total interest figure and a balance series per strategy are
 * solver output. Hand-written, they would be plausible numbers the application does not produce —
 * and since the user guide is captured from these fixtures (ADR-0012, ADR-0013), the guide would
 * teach arithmetic that does not exist.
 *
 * Regenerate with `mvn -f backend/pom.xml test -Dtest=DemoFixtureIT -Dfixtures.write=true`, read
 * the diff, and recapture the guide screenshots that show these figures.
 *
 * **The clock is frozen at 2026-08-20** inside the month the rest of this household lives in, so
 * nothing is estimated and no balance is stale. Unlike bills, the debt plan has no month selector,
 * so the figures are not re-projected for the month a capture happens to run in.
 */
export const DEMO_DEBTS = DEBT_API['GET /api/debts'];
export const DEMO_DEBT_PLAN = DEBT_API['GET /api/debt-plan'];
export const DEMO_DEBT_WHAT_IF = DEBT_API['GET /api/debt-plan/projection?strategy=SNOWBALL'];
/** The plan's amount plus fifty — the question the page actually asks. */
export const DEMO_DEBT_WHAT_IF_PLUS_FIFTY =
  DEBT_API['GET /api/debt-plan/projection?monthlyAmount=955.00'];
export const DEMO_DEBT_DETAIL = {
  'deb-0001': DEBT_API['GET /api/debts/deb-0001'],
  'deb-0002': DEBT_API['GET /api/debts/deb-0002'],
  'deb-0003': DEBT_API['GET /api/debts/deb-0003'],
} as const;
/**
 * The two balance states the demo household does not have, generated the same way.
 *
 * <p>All three demo debts are confirmed against the statement the guide's figures come from, so
 * the screens that say <em>this number is a guess</em> — the estimated wording, the stale wording
 * and the confirm-the-estimate sheet — would otherwise be rendered by nothing. They are separate
 * because adding a fourth debt to the household would move `owed`, `debtCount` and every date in
 * the plan.
 *
 * `deb-9001` is three months past its statement: ESTIMATED, and the figure on screen is the
 * anchor rolled forward with interest and the minimum taken off, which is why it is LOWER than
 * the anchor rather than higher. `deb-9002` is fourteen months past: the app stops extrapolating
 * and reports the last confirmed figure with `balanceStale` set, rather than showing a year and a
 * half of compounding as if it knew.
 */
export const DEMO_DEBT_STATES = DEBT_STATES['GET /api/debts'];
export const DEMO_DEBT_STATE_DETAIL = {
  'deb-9001': DEBT_STATES['GET /api/debts/deb-9001'],
  'deb-9002': DEBT_STATES['GET /api/debts/deb-9002'],
} as const;

export const DEMO_DEBT_PAYMENTS = {
  'deb-0001': DEBT_API['GET /api/debts/deb-0001/payments'],
  'deb-0002': DEBT_API['GET /api/debts/deb-0002/payments'],
  'deb-0003': DEBT_API['GET /api/debts/deb-0003/payments'],
} as const;

/**
 * The debt terms of the subtraction, derived from the generated figures above and from the bills
 * fixtures — never typed independently, for the same reason the other three are not.
 *
 * `debtMinimumsNotInBills` is the only one `left` loses. A minimum counts unless **this month's
 * bills figure already carries that bill**: `bil-0008` has an unskipped August occurrence, so the
 * car loan's 342 is a bill row and only 185 + 118 = 303 is new.
 */
const DEBT_MINIMUMS = Number(DEMO_DEBTS.debtMinimums);
const BILL_IDS_THIS_MONTH: Set<string> = new Set<string>(
  DEMO_BILLS_ACTIVE.filter((bill) => bill.occurrences.some((one) => one.status !== 'SKIPPED')).map(
    (bill) => bill.id,
  ),
);
const DEBT_MINIMUMS_NOT_IN_BILLS = sum(
  DEMO_DEBTS.debts
    .filter((debt) => !debt.archived && debt.paidOffAt === null)
    .filter((debt) => debt.billId === null || !BILL_IDS_THIS_MONTH.has(debt.billId))
    .map((debt) => debt.minimumPayment),
);

export const DEMO_PERIOD_SUMMARY = {
  period: '2026-08',
  income: INCOME_TOTAL.toFixed(4),
  bills: BILLS_TOTAL.toFixed(4),
  billsOutstanding: BILLS_OUTSTANDING.toFixed(4),
  billsOverdue: BILLS_OVERDUE.toFixed(4),
  envelopesAllocated: ENVELOPES_ALLOCATED.toFixed(4),
  owed: DEMO_DEBTS.owed,
  debtCount: DEMO_DEBTS.debtCount,
  debtMinimums: DEBT_MINIMUMS.toFixed(4),
  debtMinimumsNotInBills: DEBT_MINIMUMS_NOT_IN_BILLS.toFixed(4),
  left: (INCOME_TOTAL - BILLS_TOTAL - ENVELOPES_ALLOCATED - DEBT_MINIMUMS_NOT_IN_BILLS).toFixed(4),
  currency: 'USD',
} as const;

/**
 * The same month for a household that is short.
 *
 * A screen that clamps a negative figure to zero hides exactly what somebody needs to know in the
 * month they most need to know it, so the capture run has to be able to show one.
 */
export const DEMO_PERIOD_SUMMARY_SHORT = {
  ...DEMO_PERIOD_SUMMARY,
  income: '1800.0000',
  left: (1800 - BILLS_TOTAL - ENVELOPES_ALLOCATED - DEBT_MINIMUMS_NOT_IN_BILLS).toFixed(4),
} as const;

export const DEMO_BUDGET = {
  period: '2026-08',
  lines: [
    // Ids match DEMO_CATEGORIES above. Spent figures match DEMO_TRANSACTIONS, so a budget screen
    // built on these fixtures agrees with the ledger rather than quietly contradicting it.
    { categoryId: 'cat-0004', planned: '1450.00', spent: '1450.00' }, // Rent — exactly on plan
    { categoryId: 'cat-0006', planned: '350.00', spent: '283.30' }, // Groceries — under
    { categoryId: 'cat-0007', planned: '80.00', spent: '62.00' }, // Transport — under
    { categoryId: 'cat-0008', planned: '30.00', spent: '41.20' }, // Eating Out — over
    { categoryId: 'cat-0005', planned: '140.00', spent: '128.30' }, // Utilities — under
  ],
} as const;

/** Everything the mock serves, keyed by the API path it answers. */
export const DEMO_API: Record<string, unknown> = {
  '/api/setup/status': {
    setupComplete: true,
    registrationOpen: false,
    passwordLoginEnabled: true,
    oidcEnabled: false,
  },
  '/api/auth/me': DEMO_ME,
  '/api/auth/devices': {
    content: DEMO_DEVICES,
    page: 0,
    size: 50,
    totalElements: DEMO_DEVICES.length,
    totalPages: 1,
  },
  '/api/households/current': { ...DEMO_HOUSEHOLD, role: 'OWNER' },
  '/api/households/current/members': {
    content: DEMO_MEMBERS,
    page: 0,
    size: 50,
    totalElements: DEMO_MEMBERS.length,
    totalPages: 1,
  },
  '/api/accounts': {
    content: DEMO_ACCOUNTS_ACTIVE,
    page: 0,
    size: 50,
    totalElements: DEMO_ACCOUNTS_ACTIVE.length,
    totalPages: 1,
  },
  '/api/categories': {
    content: DEMO_CATEGORIES,
    page: 0,
    size: 200,
    totalElements: DEMO_CATEGORIES.length,
    totalPages: 1,
  },
  '/api/transactions': {
    content: DEMO_TRANSACTIONS,
    pageTotal: DEMO_LEDGER_PAGE_TOTAL,
    pageTotalCurrency: 'USD',
    page: 0,
    size: 50,
    totalElements: DEMO_TRANSACTIONS.length,
    totalPages: 1,
  },
  '/api/transactions/payee-suggestions': DEMO_PAYEE_SUGGESTIONS,
  '/api/envelopes/periods': DEMO_ENVELOPE_PERIODS,
  '/api/budgets/2026-08': DEMO_BUDGET,
  '/api/periods/2026-08/summary': DEMO_PERIOD_SUMMARY,
};

/**
 * Insights (slice 13): every report response the page can ask for on 20 August 2026, GENERATED by
 * `DemoFixtureIT` from the real application — never written here. A report is the server's
 * arithmetic over twelve months of ledger, and a hand-written one would be plausible figures the
 * application does not produce, which the user guide would then teach.
 *
 * **The ledger they were summed from** is `REPORT_LEDGER`: eleven months of categorised history
 * (Sep 2025 to Jul 2026, with two refunds, a December bonus, transfers, and spending under the
 * archived Wedding category) and then August, which is `DEMO_TRANSACTIONS` row for row — its one
 * uncategorised row and its transfer included. The history is not served by the ledger mock,
 * whose page is August's, and `DEMO_ACCOUNTS`' balances are worked out from `DEMO_TRANSACTIONS`
 * alone: their `openingBalance` reads as the balance before August.
 *
 * Keyed `GET <path>?from=…&to=…`, with ` CSV` appended for the `Accept: text/csv` representation.
 */
export const DEMO_REPORTS: Readonly<Record<string, unknown>> = REPORT_API;

/** An empty household's reports, and a two-currency one's, keyed the same way with a prefix. */
export const DEMO_REPORT_STATES: Readonly<Record<string, unknown>> = REPORT_STATES;

export const DEMO_REPORT_HISTORY = REPORT_LEDGER.filter((row) => row.date < '2026-08-01');

/**
 * Loud rather than silent: a `DEMO_TRANSACTIONS` row that is not in the ledger the report fixtures
 * were generated from means Insights would be summing a different month from the one the
 * transactions screen shows. Edit `DemoFixtureIT.DEMO_LEDGER` to match and regenerate.
 */
for (const row of DEMO_TRANSACTIONS) {
  const found = REPORT_LEDGER.some(
    (seeded) =>
      seeded.date === row.date &&
      seeded.amount === row.amount &&
      seeded.account === row.accountId &&
      seeded.category === row.categoryId &&
      seeded.transfer === row.transfer &&
      seeded.status === row.status,
  );
  if (!found) {
    throw new Error(
      `DEMO_TRANSACTIONS ${row.id} is not in the ledger the report fixtures were generated from. ` +
        'Update DemoFixtureIT.DEMO_LEDGER and regenerate (see report-api.generated.ts).',
    );
  }
}
