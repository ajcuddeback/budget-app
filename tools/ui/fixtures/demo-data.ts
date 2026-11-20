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
  household: { ...DEMO_ME.household, role: 'MEMBER', membershipId: DEMO_MEMBERS[1].id, displayCurrency: 'EUR', locale: 'de-DE' },
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
    label: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36',
    createdAt: '2026-10-02T08:15:00Z',
    lastUsedAt: '2026-10-02T09:40:00Z',
    expiresAt: '2026-10-02T20:15:00Z',
    current: true,
  },
  {
    id: 's-1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809',
    kind: 'SESSION',
    label: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15',
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
  authenticationFailed: { type: 'https://budgetowl.app/errors/authentication-failed', title: 'Authentication failed', status: 401, code: 'authentication-failed', correlationId: 'demo0000000000000000000000000001' },
  notAuthenticated: { type: 'https://budgetowl.app/errors/not-authenticated', title: 'Not authenticated', status: 401, code: 'not-authenticated', correlationId: 'demo0000000000000000000000000002' },
  notAMember: { type: 'https://budgetowl.app/errors/not-a-member', title: 'Not a member of this household', status: 403, code: 'not-a-member', correlationId: 'demo0000000000000000000000000003' },
  invitationUnusable: { type: 'https://budgetowl.app/errors/invitation-unusable', title: 'This invitation cannot be used', status: 404, code: 'invitation-unusable', correlationId: 'demo0000000000000000000000000004' },
  lastOwner: { type: 'https://budgetowl.app/errors/last-owner', title: 'A household must keep at least one owner', status: 409, code: 'last-owner', correlationId: 'demo0000000000000000000000000005' },
  rateLimited: { type: 'https://budgetowl.app/errors/rate-limited', title: 'Too many attempts', status: 429, code: 'rate-limited', params: { retryAfterSeconds: 120 }, correlationId: 'demo0000000000000000000000000006' },
  internal: { type: 'https://budgetowl.app/errors/internal-error', title: 'Internal error', status: 500, code: 'internal-error', correlationId: 'demo0000000000000000000000000007' },
  staleVersion: { type: 'https://budgetowl.app/errors/stale-version', title: 'Somebody else changed this first', status: 409, code: 'stale-version', correlationId: 'demo0000000000000000000000000009' },
  accountNotEmpty: { type: 'https://budgetowl.app/errors/account-not-empty', title: 'This account has transactions', status: 409, code: 'account-not-empty', correlationId: 'demo0000000000000000000000000010' },
} as const;

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
  { id: 'acc-0001', name: 'Everyday Checking', lastFour: '4417', type: 'CHECKING', currency: 'USD', openingBalance: '1641.75', balance: '2480.15', empty: false, archived: false, createdAt: '2026-06-01T09:05:00Z', updatedAt: '2026-08-20T11:20:00Z', version: 2 },
  { id: 'acc-0002', name: 'Emergency Fund', lastFour: null, type: 'SAVINGS', currency: 'USD', openingBalance: '5620.00', balance: '6120.00', empty: false, archived: false, createdAt: '2026-06-01T09:07:00Z', updatedAt: '2026-08-16T10:02:00Z', version: 1 },
  { id: 'acc-0003', name: 'Travel Card', lastFour: '8820', type: 'CREDIT_CARD', currency: 'USD', openingBalance: '-215.24', balance: '-318.44', empty: false, archived: false, createdAt: '2026-06-02T18:40:00Z', updatedAt: '2026-08-09T08:15:00Z', version: 3 },
  { id: 'acc-0004', name: 'Berlin Savings', lastFour: null, type: 'SAVINGS', currency: 'EUR', openingBalance: '1200.00', balance: '1200.00', empty: true, archived: false, createdAt: '2026-06-04T07:30:00Z', updatedAt: '2026-06-04T07:30:00Z', version: 0 },
  { id: 'acc-0005', name: 'Old Joint Account', lastFour: '0041', type: 'CHECKING', currency: 'USD', openingBalance: '84.60', balance: '84.60', empty: true, archived: true, createdAt: '2026-06-01T09:12:00Z', updatedAt: '2026-07-18T16:45:00Z', version: 4 },
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
  { id: 'cat-0001', name: 'Income', kind: 'INCOME', parentId: null, parentName: null, archived: false, createdAt: '2026-06-01T09:00:00Z', updatedAt: '2026-06-01T09:00:00Z', version: 0 },
  { id: 'cat-0002', name: 'Salary', kind: 'INCOME', parentId: 'cat-0001', parentName: 'Income', archived: false, createdAt: '2026-06-01T09:00:00Z', updatedAt: '2026-06-01T09:00:00Z', version: 0 },
  { id: 'cat-0003', name: 'Bills', kind: 'EXPENSE', parentId: null, parentName: null, archived: false, createdAt: '2026-06-01T09:00:00Z', updatedAt: '2026-06-01T09:00:00Z', version: 0 },
  { id: 'cat-0004', name: 'Rent', kind: 'EXPENSE', parentId: 'cat-0003', parentName: 'Bills', archived: false, createdAt: '2026-06-01T09:00:00Z', updatedAt: '2026-06-01T09:00:00Z', version: 0 },
  { id: 'cat-0005', name: 'Utilities', kind: 'EXPENSE', parentId: 'cat-0003', parentName: 'Bills', archived: false, createdAt: '2026-06-01T09:00:00Z', updatedAt: '2026-06-01T09:00:00Z', version: 0 },
  { id: 'cat-0006', name: 'Groceries', kind: 'EXPENSE', parentId: null, parentName: null, archived: false, createdAt: '2026-06-01T09:00:00Z', updatedAt: '2026-06-01T09:00:00Z', version: 0 },
  { id: 'cat-0007', name: 'Transport', kind: 'EXPENSE', parentId: null, parentName: null, archived: false, createdAt: '2026-06-01T09:00:00Z', updatedAt: '2026-06-01T09:00:00Z', version: 0 },
  { id: 'cat-0008', name: 'Eating Out', kind: 'EXPENSE', parentId: null, parentName: null, archived: false, createdAt: '2026-06-01T09:00:00Z', updatedAt: '2026-06-01T09:00:00Z', version: 0 },
  // Archived: kept so past transactions still read correctly, absent from every picker.
  { id: 'cat-0009', name: 'Wedding', kind: 'EXPENSE', parentId: null, parentName: null, archived: true, createdAt: '2026-06-01T09:00:00Z', updatedAt: '2026-07-20T09:00:00Z', version: 1 },
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
 *   income or expense (`txn-0008`, `txn-0009`).
 */
export const DEMO_TRANSACTIONS = [
  { id: 'txn-0010', accountId: 'acc-0001', accountName: 'Everyday Checking', date: '2026-08-20', amount: '-92.15', currency: 'USD', payeeId: 'pay-0003', payeeName: 'Greenfield Market', categoryId: null, categoryName: null, categoryParentName: null, note: null, status: 'PENDING', transfer: false, createdAt: '2026-08-20T18:30:00Z', updatedAt: '2026-08-20T18:30:00Z', version: 0 },
  { id: 'txn-0009', accountId: 'acc-0002', accountName: 'Emergency Fund', date: '2026-08-16', amount: '500.00', currency: 'USD', payeeId: null, payeeName: null, categoryId: null, categoryName: null, categoryParentName: null, note: 'Transfer from Everyday Checking', status: 'CLEARED', transfer: true, createdAt: '2026-08-16T08:00:00Z', updatedAt: '2026-08-16T08:00:00Z', version: 0 },
  { id: 'txn-0008', accountId: 'acc-0001', accountName: 'Everyday Checking', date: '2026-08-16', amount: '-500.00', currency: 'USD', payeeId: null, payeeName: null, categoryId: null, categoryName: null, categoryParentName: null, note: 'Transfer to Emergency Fund', status: 'CLEARED', transfer: true, createdAt: '2026-08-16T08:00:00Z', updatedAt: '2026-08-16T08:00:00Z', version: 0 },
  { id: 'txn-0007', accountId: 'acc-0001', accountName: 'Everyday Checking', date: '2026-08-15', amount: '-128.30', currency: 'USD', payeeId: 'pay-0006', payeeName: 'Metro Power', categoryId: 'cat-0005', categoryName: 'Utilities', categoryParentName: 'Bills', note: null, status: 'CLEARED', transfer: false, createdAt: '2026-08-15T09:00:00Z', updatedAt: '2026-08-15T09:00:00Z', version: 0 },
  { id: 'txn-0006', accountId: 'acc-0001', accountName: 'Everyday Checking', date: '2026-08-12', amount: '-104.75', currency: 'USD', payeeId: 'pay-0003', payeeName: 'Greenfield Market', categoryId: 'cat-0006', categoryName: 'Groceries', categoryParentName: null, note: 'big shop', status: 'CLEARED', transfer: false, createdAt: '2026-08-12T17:10:00Z', updatedAt: '2026-08-12T17:10:00Z', version: 0 },
  { id: 'txn-0005', accountId: 'acc-0003', accountName: 'Travel Card', date: '2026-08-09', amount: '-41.20', currency: 'USD', payeeId: 'pay-0005', payeeName: 'Corner Kitchen', categoryId: 'cat-0008', categoryName: 'Eating Out', categoryParentName: null, note: null, status: 'CLEARED', transfer: false, createdAt: '2026-08-09T20:05:00Z', updatedAt: '2026-08-09T20:05:00Z', version: 0 },
  { id: 'txn-0004', accountId: 'acc-0003', accountName: 'Travel Card', date: '2026-08-05', amount: '-62.00', currency: 'USD', payeeId: 'pay-0004', payeeName: 'City Transit', categoryId: 'cat-0007', categoryName: 'Transport', categoryParentName: null, note: null, status: 'CLEARED', transfer: false, createdAt: '2026-08-05T08:40:00Z', updatedAt: '2026-08-05T08:40:00Z', version: 0 },
  { id: 'txn-0003', accountId: 'acc-0001', accountName: 'Everyday Checking', date: '2026-08-03', amount: '-86.40', currency: 'USD', payeeId: 'pay-0003', payeeName: 'Greenfield Market', categoryId: 'cat-0006', categoryName: 'Groceries', categoryParentName: null, note: null, status: 'CLEARED', transfer: false, createdAt: '2026-08-03T16:20:00Z', updatedAt: '2026-08-03T16:20:00Z', version: 0 },
  { id: 'txn-0002', accountId: 'acc-0001', accountName: 'Everyday Checking', date: '2026-08-01', amount: '-1450.00', currency: 'USD', payeeId: 'pay-0002', payeeName: 'Fairview Lettings', categoryId: 'cat-0004', categoryName: 'Rent', categoryParentName: 'Bills', note: null, status: 'CLEARED', transfer: false, createdAt: '2026-08-01T07:00:00Z', updatedAt: '2026-08-01T07:00:00Z', version: 0 },
  { id: 'txn-0001', accountId: 'acc-0001', accountName: 'Everyday Checking', date: '2026-08-01', amount: '3200.00', currency: 'USD', payeeId: 'pay-0001', payeeName: 'Northwind Systems', categoryId: 'cat-0002', categoryName: 'Salary', categoryParentName: 'Income', note: null, status: 'CLEARED', transfer: false, createdAt: '2026-08-01T06:00:00Z', updatedAt: '2026-08-01T06:00:00Z', version: 0 },
] as const;

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

export const DEMO_BUDGET = {
  period: '2026-08',
  lines: [
    // Ids match DEMO_CATEGORIES above. Spent figures match DEMO_TRANSACTIONS, so a budget screen
    // built on these fixtures agrees with the ledger rather than quietly contradicting it.
    { categoryId: 'cat-0004', planned: '1450.00', spent: '1450.00' }, // Rent — exactly on plan
    { categoryId: 'cat-0006', planned: '350.00', spent: '283.30' },   // Groceries — under
    { categoryId: 'cat-0007', planned: '80.00', spent: '62.00' },     // Transport — under
    { categoryId: 'cat-0008', planned: '30.00', spent: '41.20' },     // Eating Out — over
    { categoryId: 'cat-0005', planned: '140.00', spent: '128.30' },   // Utilities — under
  ],
} as const;

/** Everything the mock serves, keyed by the API path it answers. */
export const DEMO_API: Record<string, unknown> = {
  '/api/setup/status': { setupComplete: true, registrationOpen: false, passwordLoginEnabled: true, oidcEnabled: false },
  '/api/auth/me': DEMO_ME,
  '/api/auth/devices': { content: DEMO_DEVICES, page: 0, size: 50, totalElements: DEMO_DEVICES.length, totalPages: 1 },
  '/api/households/current': { ...DEMO_HOUSEHOLD, role: 'OWNER' },
  '/api/households/current/members': { content: DEMO_MEMBERS, page: 0, size: 50, totalElements: DEMO_MEMBERS.length, totalPages: 1 },
  '/api/accounts': { content: DEMO_ACCOUNTS_ACTIVE, page: 0, size: 50, totalElements: DEMO_ACCOUNTS_ACTIVE.length, totalPages: 1 },
  '/api/categories': { content: DEMO_CATEGORIES, page: 0, size: 200, totalElements: DEMO_CATEGORIES.length, totalPages: 1 },
  '/api/transactions': { content: DEMO_TRANSACTIONS, pageTotal: DEMO_LEDGER_PAGE_TOTAL, pageTotalCurrency: 'USD', page: 0, size: 50, totalElements: DEMO_TRANSACTIONS.length, totalPages: 1 },
  '/api/transactions/payee-suggestions': DEMO_PAYEE_SUGGESTIONS,
  '/api/budgets/2026-08': DEMO_BUDGET,
};
