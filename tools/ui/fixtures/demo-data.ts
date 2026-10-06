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
} as const;

export const DEMO_ACCOUNTS = [
  { id: 'acc-0001', name: 'Everyday Checking', type: 'CHECKING', currency: 'USD', balance: '2480.15' },
  { id: 'acc-0002', name: 'Emergency Fund', type: 'SAVINGS', currency: 'USD', balance: '6120.00' },
  { id: 'acc-0003', name: 'Travel Card', type: 'CREDIT_CARD', currency: 'USD', balance: '-318.44' },
] as const;

export const DEMO_CATEGORIES = [
  { id: 'cat-0001', name: 'Salary', kind: 'INCOME' },
  { id: 'cat-0002', name: 'Rent', kind: 'EXPENSE' },
  { id: 'cat-0003', name: 'Groceries', kind: 'EXPENSE' },
  { id: 'cat-0004', name: 'Transport', kind: 'EXPENSE' },
  { id: 'cat-0005', name: 'Eating out', kind: 'EXPENSE' },
  { id: 'cat-0006', name: 'Utilities', kind: 'EXPENSE' },
] as const;

export const DEMO_TRANSACTIONS = [
  { id: 'txn-0001', accountId: 'acc-0001', date: '2026-08-01', payee: 'Northwind Systems', categoryId: 'cat-0001', amount: '3200.00', status: 'CLEARED' },
  { id: 'txn-0002', accountId: 'acc-0001', date: '2026-08-01', payee: 'Fairview Lettings', categoryId: 'cat-0002', amount: '-1450.00', status: 'CLEARED' },
  { id: 'txn-0003', accountId: 'acc-0001', date: '2026-08-03', payee: 'Greenfield Market', categoryId: 'cat-0003', amount: '-86.40', status: 'CLEARED' },
  { id: 'txn-0004', accountId: 'acc-0003', date: '2026-08-05', payee: 'City Transit', categoryId: 'cat-0004', amount: '-62.00', status: 'CLEARED' },
  { id: 'txn-0005', accountId: 'acc-0003', date: '2026-08-09', payee: 'Corner Kitchen', categoryId: 'cat-0005', amount: '-41.20', status: 'CLEARED' },
  { id: 'txn-0006', accountId: 'acc-0001', date: '2026-08-12', payee: 'Greenfield Market', categoryId: 'cat-0003', amount: '-104.75', status: 'CLEARED' },
  { id: 'txn-0007', accountId: 'acc-0001', date: '2026-08-15', payee: 'Metro Power', categoryId: 'cat-0006', amount: '-128.30', status: 'CLEARED' },
  // A transfer: two legs, equal and opposite, sharing a group id. Neither is income or expense.
  { id: 'txn-0008', accountId: 'acc-0001', date: '2026-08-16', payee: 'Transfer to Emergency Fund', categoryId: null, amount: '-500.00', status: 'CLEARED', transferGroupId: 'trf-0001' },
  { id: 'txn-0009', accountId: 'acc-0002', date: '2026-08-16', payee: 'Transfer from Everyday Checking', categoryId: null, amount: '500.00', status: 'CLEARED', transferGroupId: 'trf-0001' },
  { id: 'txn-0010', accountId: 'acc-0001', date: '2026-08-20', payee: 'Greenfield Market', categoryId: 'cat-0003', amount: '-92.15', status: 'PENDING' },
] as const;

export const DEMO_BUDGET = {
  period: '2026-08',
  lines: [
    { categoryId: 'cat-0002', planned: '1450.00', spent: '1450.00' }, // exactly on plan
    { categoryId: 'cat-0003', planned: '350.00', spent: '283.30' },   // under
    { categoryId: 'cat-0004', planned: '80.00', spent: '62.00' },     // under
    { categoryId: 'cat-0005', planned: '30.00', spent: '41.20' },     // over — the interesting case
    { categoryId: 'cat-0006', planned: '140.00', spent: '128.30' },   // under
  ],
} as const;

/** Everything the mock serves, keyed by the API path it answers. */
export const DEMO_API: Record<string, unknown> = {
  '/api/setup/status': { setupComplete: true, registrationOpen: false, passwordLoginEnabled: true, oidcEnabled: false },
  '/api/auth/me': DEMO_ME,
  '/api/auth/devices': { content: DEMO_DEVICES, page: 0, size: 50, totalElements: DEMO_DEVICES.length, totalPages: 1 },
  '/api/households/current': { ...DEMO_HOUSEHOLD, role: 'OWNER' },
  '/api/households/current/members': { content: DEMO_MEMBERS, page: 0, size: 50, totalElements: DEMO_MEMBERS.length, totalPages: 1 },
  '/api/accounts': { content: DEMO_ACCOUNTS, page: 0, size: 50, totalElements: DEMO_ACCOUNTS.length, totalPages: 1 },
  '/api/categories': { content: DEMO_CATEGORIES, page: 0, size: 50, totalElements: DEMO_CATEGORIES.length, totalPages: 1 },
  '/api/transactions': { content: DEMO_TRANSACTIONS, page: 0, size: 50, totalElements: DEMO_TRANSACTIONS.length, totalPages: 1 },
  '/api/budgets/2026-08': DEMO_BUDGET,
};
