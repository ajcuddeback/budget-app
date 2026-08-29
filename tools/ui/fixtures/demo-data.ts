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
  '/api/auth/me': DEMO_USER,
  '/api/accounts': { content: DEMO_ACCOUNTS, page: 0, size: 50, totalElements: DEMO_ACCOUNTS.length, totalPages: 1 },
  '/api/categories': { content: DEMO_CATEGORIES, page: 0, size: 50, totalElements: DEMO_CATEGORIES.length, totalPages: 1 },
  '/api/transactions': { content: DEMO_TRANSACTIONS, page: 0, size: 50, totalElements: DEMO_TRANSACTIONS.length, totalPages: 1 },
  '/api/budgets/2026-08': DEMO_BUDGET,
};
