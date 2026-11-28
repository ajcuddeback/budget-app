import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';
import { DEMO_DEBT_PAYMENTS, DEMO_DEBT_STATES } from '../../fixtures/demo-data.js';
import { DEBT_API } from '../../fixtures/debt-api.generated.js';
import type { Page } from '@playwright/test';

/**
 * Screens for the slice 10 guides: add-a-debt, choose-how-to-clear-your-debts,
 * log-a-debt-payment and confirm-what-you-owe.
 *
 * Everything the plan shows comes out of the GENERATED debt fixtures, so these captures show the
 * real solver's arithmetic rather than plausible numbers: three debts, $905.00 a month against
 * $645.00 of minimums, and two orders of attack that finish in the same month.
 *
 * The two balance states the demo household does not have — an estimate, and one estimated for
 * over a year — live in `DEBT_STATES`, so the tests that need them re-route `GET /api/debts`
 * after the mock is installed. Registered later, so they win.
 */

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await mockApi(page, { session: 'owner' });
});

const card = (page: Page, heading: string) =>
  page.locator('section.card').filter({ hasText: heading }).first();

/**
 * A card of the detail page, by the id its heading carries.
 *
 * By heading text would pick the wrong card: *Balance now* is both a card title and a row of the
 * Loan terms grid, so filtering on the words captured the terms panel twice.
 */
const region = (page: Page, heading: string) =>
  page.locator(`section.card[aria-labelledby="${heading}"]`);

/**
 * Serve the debt list from the two debts whose balances are not confirmed.
 *
 * Matched on the **path**, not a glob: `DebtsService.list()` sends `?includeArchived=false`, so
 * `**​/api/debts` matches nothing and the generated three-debt household is served instead —
 * which is a screenshot of the wrong thing rather than a failure.
 *
 * Both of these debts carry `paymentCount: 0` in the generated state, so the real application
 * answers their payment history with an empty page. The shared mock has no generated payments
 * file for them and answers `404`, which the detail page renders as *not found* — so the empty
 * page is supplied here. No figure is invented: an empty list is the whole response.
 */
async function useBalanceStates(page: Page): Promise<void> {
  await page.route(
    (url) => url.pathname === '/api/debts',
    (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(DEMO_DEBT_STATES),
      }),
  );
  await page.route(/\/api\/debts\/deb-900[12]\/payments/, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
    }),
  );
}

test('@doc the debt plan, end to end', async ({ page, doc }) => {
  doc.guide('choose-how-to-clear-your-debts');

  await page.goto('/debt-plan');
  await expect(page.getByRole('heading', { name: 'Debt plan', level: 1 })).toBeVisible();

  await doc.capture(
    'page',
    'The Debt plan page: the monthly commitment, the order of attack, the balance-to-zero chart, the two orders compared, and the table of debts.',
    { fullPage: true },
  );

  await doc.capture(
    'commitment',
    'The strip at the top of the page: what is committed to debt each month and how much of it is strategic, with Change the monthly amount beside it.',
    { focus: page.locator('.header-strip') },
  );

  await doc.capture(
    'strategy',
    'The Order of attack card with Avalanche, Snowball and Your own order as three buttons, Avalanche in use.',
    { highlight: page.getByRole('group', { name: 'Order of attack' }), step: 2 },
  );

  await doc.capture(
    'chart',
    'The Balance to zero card: one line per order of attack, the assumption underneath, and the headline saying what is left to clear and the debt-free month.',
    { focus: card(page, 'Balance to zero') },
  );

  await doc.capture(
    'compare',
    'The Avalanche or snowball card: two cards side by side, each with its reasoning, its debt-free month, when the first debt goes and the interest along the way, then the sentence that compares them.',
    { focus: card(page, 'Avalanche or snowball') },
  );

  await doc.capture(
    'debts',
    'The Your debts table: rung, debt, balance, rate, progress, when it clears and what it is paying, with Open and Edit terms on each row.',
    { focus: card(page, 'Your debts') },
  );

  await doc.capture(
    'footer',
    'The line at the foot of the page saying the minimums are each counted once in Bills and income.',
    { focus: page.locator('.footer-line') },
  );
});

test('@doc changing what goes towards debt each month', async ({ page, doc }) => {
  doc.guide('choose-how-to-clear-your-debts');

  await page.goto('/debt-plan');
  await page.getByRole('button', { name: 'Change the monthly amount' }).click();
  await doc.capture(
    'monthly-amount',
    'Towards debt each month opened for editing, with the amount in a box, its hint, and Save and Cancel beside it.',
    { highlight: page.getByLabel('Towards debt each month'), step: 2 },
  );
});

test('@doc the debt plan with nothing in it', async ({ page, doc }) => {
  doc.guide('add-a-debt');

  // On the path, not a glob: the list request carries ?includeArchived=false.
  await page.route(
    (url) => url.pathname === '/api/debts',
    (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          debts: [],
          strategy: 'AVALANCHE',
          currency: 'USD',
          owed: '0.0000',
          debtMinimums: '0.0000',
          debtCount: 0,
        }),
      }),
  );

  await page.goto('/debt-plan');
  await expect(page.getByText('Nothing owed here yet')).toBeVisible();
  await doc.capture(
    'empty',
    'The Debt plan page with no debts: Nothing owed here yet, what a debt is, the note about a minimum that is already a bill, and an Add a debt button.',
    { highlight: page.getByRole('button', { name: 'Add a debt' }), step: 2 },
  );
});

test('@doc adding a debt', async ({ page, doc }) => {
  doc.guide('add-a-debt');

  await page.goto('/debt-plan');
  await doc.capture(
    'add-button',
    'The Your debts card with the Add a debt button beside its heading.',
    { highlight: page.getByRole('button', { name: 'Add a debt' }), step: 2 },
  );

  await page.getByRole('button', { name: 'Add a debt' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('What you call it').fill('Store card');
  await dialog.getByLabel('Kind').selectOption('CREDIT_CARD');
  await dialog.getByLabel('Who you owe it to').fill('Argos');
  await doc.capture(
    'form-top',
    'The Add a debt window: What you call it, Kind, Who you owe it to, Balance now and the date that balance was true.',
    { step: 3 },
  );

  await dialog.getByLabel('Balance now').fill('1240.00');
  await dialog.getByLabel('The date that balance was true').fill('2026-08-18');
  await doc.capture(
    'balance',
    'Balance now filled in with 1240.00 and the date that balance was true set, with the hint that it comes off the statement.',
    { highlight: dialog.getByLabel('The date that balance was true'), step: 5 },
  );

  await dialog.getByLabel('Annual rate').fill('29.9');
  await dialog.getByLabel('Minimum payment').fill('45');
  await doc.capture(
    'rate-and-minimum',
    'Annual rate set to 29.9 and Minimum payment set to 45, each with its hint underneath.',
    { highlight: dialog.getByLabel('Minimum payment'), step: 7 },
  );

  await dialog.getByLabel('The bill that pays its minimum').selectOption({ label: 'Car payment' });
  await doc.capture(
    'bill-link',
    'The bill that pays its minimum, set to Car payment, with the note that saying which bill means the minimum is counted once.',
    { highlight: dialog.getByLabel('The bill that pays its minimum'), step: 9 },
  );
});

test("@doc changing a debt's terms", async ({ page, doc }) => {
  doc.guide('add-a-debt');

  await page.goto('/debt-plan/deb-0001');
  await page.getByRole('button', { name: 'Edit terms' }).click();
  const dialog = page.getByRole('dialog');
  await doc.capture(
    'edit-terms',
    'The Edit terms window for a debt that already exists, with the Work the rate out for me tick box and its note above the annual rate.',
    { highlight: dialog.getByLabel('Work the rate out for me'), step: 3 },
  );
});

test('@doc one debt in detail', async ({ page, doc }) => {
  doc.guide('log-a-debt-payment');

  await page.goto('/debt-plan/deb-0001');
  await expect(page.getByRole('heading', { name: 'Visa', level: 1 })).toBeVisible();

  await doc.capture(
    'page',
    'The page for one debt: its name, the loan terms, the balance now, the balance over time and the payment history.',
    { fullPage: true },
  );

  await doc.capture(
    'terms',
    'The Loan terms card: what it started at, the balance now, the annual rate, the minimum payment, what you are paying, months left, when it is gone and the interest still to come, with Solved and From your plan tags.',
    { focus: region(page, 'terms-heading') },
  );

  await doc.capture(
    'balance',
    'The Balance now card: the figure, when it was confirmed, and how much has been paid off so far.',
    { focus: region(page, 'balance-heading') },
  );

  await doc.capture(
    'over-time',
    'The Balance over time card: what you are paying against the at-the-minimum comparison line, with the sentence saying what the extra is buying.',
    { focus: region(page, 'over-time-heading') },
  );

  await doc.capture(
    'history',
    'The Payment history table: date, paid, interest, principal, balance after and how each row got here, with the compounding rule underneath.',
    { focus: region(page, 'history-heading') },
  );
});

test('@doc logging a debt payment', async ({ page, doc }) => {
  doc.guide('log-a-debt-payment');

  await page.goto('/debt-plan/deb-0001');
  await doc.capture(
    'log-button',
    'The two buttons under the debt name: Log a payment and Edit terms.',
    { highlight: page.getByRole('button', { name: 'Log a payment' }), step: 2 },
  );

  await page.getByRole('button', { name: 'Log a payment' }).click();
  const dialog = page.getByRole('dialog');
  // The sheet opens with the scheduled figure in it and asks the server what that would record.
  await expect(dialog.locator('.preview')).toContainText('principal');
  await doc.capture(
    'sheet',
    'The Log a payment to Visa window: Amount paid filled in with what the plan puts against it, the split that amount would record, Date paid, Paid from and Note.',
    { step: 3 },
  );

  await doc.capture(
    'preview',
    'The line under Amount paid giving the interest, the principal and the balance after for the amount in the box.',
    { highlight: dialog.locator('.preview'), step: 4 },
  );

  await doc.capture(
    'paid-from',
    'The Paid from box, with the note that nothing is recorded against that account and no transaction is created.',
    { highlight: dialog.getByLabel('Paid from'), step: 6 },
  );

  // The line that used to sit here — "Budget Owl works out how much went on interest…" — is shown
  // only while there is no split to show, so with the preview answering it is not on screen.
  await doc.capture('note', 'The Note box at the foot of the window, above Record it and Cancel.', {
    highlight: dialog.getByLabel('Note'),
    step: 7,
  });

  // The banner reports the SERVER's split, and the shared mock answers every write with
  // `{ ok: true }` — which renders the banner with its figures missing. So this one request is
  // answered with a row whose four figures are the GENERATED preview for $445.00 against this
  // debt. That is what the real application records: `DebtPaymentService.log` and `preview` both
  // build the row through `draft`, and `DebtIT.the_preview_is_exactly_what_logging_then_records`
  // pins it. Answering with the historical `gen-0002` instead, as this spec once did, put one
  // split in the sheet and a different one in the banner a click later. No figure here is written
  // by hand; the rest of the row's shape is `gen-0002`'s.
  const previewed = DEBT_API['POST /api/debts/deb-0001/payments/preview 445.00'];
  await page.route(/\/api\/debts\/deb-0001\/payments$/, async (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    return route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        ...DEMO_DEBT_PAYMENTS['deb-0001'].content[1],
        amount: '445.0000',
        interest: previewed.interest,
        principal: previewed.principal,
        balanceAfter: previewed.balanceAfter,
      }),
    });
  });
  await dialog.getByLabel('Date paid').fill('2026-08-20');
  await dialog.getByRole('button', { name: 'Record it' }).click();
  await expect(page.locator('.alert-success')).toBeVisible();
  await doc.capture(
    'recorded',
    'The banner after recording a payment, naming how much went on interest, how much came off the balance, and the balance now.',
    { focus: page.locator('.alert-success') },
  );
});

test('@doc the split for a one-tap amount', async ({ page, doc }) => {
  doc.guide('log-a-debt-payment');

  await page.goto('/debt-plan/deb-0001');
  await page.getByRole('button', { name: 'Log a payment' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.locator('.preview')).toContainText('principal');

  // The fifty-more button. Its answer is the generated preview for $495.00.
  await dialog.getByRole('button', { name: /\$495\.00/ }).click();
  await expect(dialog.locator('.preview')).toContainText('$495.00 principal');
  await doc.capture(
    'preview-extra',
    'Amount paid after tapping the fifty-more button, with the split underneath already worked out again for that amount.',
    { focus: dialog, highlight: dialog.locator('.preview') },
  );
});

test('@doc when the split cannot be shown', async ({ page, doc }) => {
  doc.guide('log-a-debt-payment');

  // The preview refused, the way a server that is briefly unreachable refuses it. Registered after
  // the shared mock, so it wins. Nothing else changes: the sheet must still record payments.
  await page.route(/\/api\/debts\/deb-0001\/payments\/preview$/, (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      body: JSON.stringify({ title: 'Service Unavailable', status: 503 }),
    }),
  );

  await page.goto('/debt-plan/deb-0001');
  await page.getByRole('button', { name: 'Log a payment' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('I could not work out the split', { exact: false })).toBeVisible();
  await doc.capture(
    'preview-unavailable',
    'The Log a payment window with the note that the split could not be worked out just now, and Record it still there.',
    {
      focus: dialog,
      highlight: dialog.getByText('I could not work out the split', { exact: false }),
    },
  );
});

test('@doc setting your own order', async ({ page, doc }) => {
  doc.guide('choose-how-to-clear-your-debts');

  await page.goto('/debt-plan');
  await expect(page.getByRole('heading', { name: 'Debt plan', level: 1 })).toBeVisible();

  const orders = page.getByRole('group', { name: 'Order of attack' });
  await doc.capture(
    'own-order-button',
    'The Order of attack card with Your own order circled.',
    { highlight: orders.getByRole('button', { name: /Your own order/ }), step: 1 },
  );

  await orders.getByRole('button', { name: /Your own order/ }).click();
  await expect(page.getByRole('button', { name: /Your own order/, pressed: true })).toBeVisible();

  await doc.capture(
    'own-order-chosen',
    'The top of the page after choosing Your own order: the saved message and the Order of attack card with Your own order in use.',
    {},
  );

  await doc.capture(
    'own-order-compare',
    'The Avalanche or snowball card with a line above the two cards saying what your own order costs.',
    { focus: card(page, 'Avalanche or snowball') },
  );

  await doc.capture(
    'own-order-chart',
    'The Balance to zero card under Your own order before anything is moved: one line keyed to all three orders.',
    { focus: card(page, 'Balance to zero') },
  );

  const list = card(page, 'Your debts');
  // Into view BEFORE the capture: the ring is drawn fixed to the viewport, and a focused shot that
  // has to scroll to reach its element leaves the ring behind on whatever was there before.
  await list.scrollIntoViewIfNeeded();
  await doc.capture(
    'own-order-moves',
    'The Your debts table with Move up and Move down on each row, Move up on Car loan circled.',
    {
      focus: list,
      highlight: list.getByRole('button', { name: 'Move up: Car loan' }),
      step: 2,
    },
  );

  // The what-if (+$50 a month) after the move is the generator's projection for THIS order
  // (DemoFixtureIT, keyed "… CUSTOM <order>"), so the line the page shows is the server's.

  // One move: the car loan to rung 1. Visa, Car, Student becomes Car, Visa, Student — an order the
  // generator produced, so every figure after it is the server's for that order.
  await list.getByRole('button', { name: 'Move up: Car loan' }).click();
  await expect(card(page, 'Avalanche or snowball')).toContainText('$3,811.53');

  await doc.capture(
    'own-order-moved',
    'The Your debts table after moving Car loan up: Car loan on rung 1 with the extra, Visa on rung 2, and new dates.',
    { focus: list },
  );

  await doc.capture(
    'own-order-moved-compare',
    'The Avalanche or snowball card after the move: the line above the cards with the new debt-free month and interest, and the two cards unchanged.',
    { focus: card(page, 'Avalanche or snowball') },
  );

  await doc.capture(
    'own-order-moved-chart',
    'The Balance to zero card after the move: your own order drawn as its own line beside avalanche and snowball.',
    { focus: card(page, 'Balance to zero') },
  );
});

test('@doc your own order as a viewer', async ({ page, doc }) => {
  doc.guide('choose-how-to-clear-your-debts');
  await mockApi(page, { session: 'viewer' });

  // A viewer cannot choose an order, so the household's — set by an owner or member — is served
  // straight from the generated responses for that order. On the path: the list carries a query.
  const order = 'deb-0002,deb-0001,deb-0003';
  await page.route(
    (url) => url.pathname === '/api/debts',
    (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(DEBT_API[`GET /api/debts CUSTOM ${order}`]),
      }),
  );
  await page.route(
    (url) => url.pathname === '/api/debt-plan',
    (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(DEBT_API[`PUT /api/debt-plan CUSTOM ${order}`]),
      }),
  );

  await page.goto('/debt-plan');
  await expect(page.getByRole('heading', { name: 'Debt plan', level: 1 })).toBeVisible();
  await doc.capture(
    'viewer-own-order',
    'The Your debts table as a viewer when the household uses its own order: the order in a sentence, and no Move up or Move down.',
    { focus: card(page, 'Your debts') },
  );
});

test('@doc a debt balance that is an estimate', async ({ page, doc }) => {
  doc.guide('confirm-what-you-owe');
  await useBalanceStates(page);

  // Straight to the debt, not through the plan page: there is no generated payoff plan for this
  // household, so a plan rendered from the other household's rungs would be a screenshot of two
  // halves that never appear together.
  await page.goto('/debt-plan/deb-9001');
  await expect(page.getByRole('heading', { name: 'Store card', level: 1 })).toBeVisible();
  await doc.capture(
    'balance',
    'The Balance now card for an estimated balance: the figure, the words estimated — not confirmed, and a Confirm the balance button.',
    { focus: region(page, 'balance-heading') },
  );

  // The sentence that says what the estimate assumed lives under the terms grid, not under the
  // headline figure it describes.
  await doc.capture(
    'estimate-line',
    'The foot of the Loan terms card, saying the balance is an estimate rolled forward from the figure last confirmed, with the minimum assumed paid and the interest added.',
    { focus: region(page, 'terms-heading') },
  );

  await doc.capture(
    'confirm-button',
    'The buttons under the debt name, with Confirm the balance beside Log a payment and Edit terms.',
    { highlight: page.getByRole('button', { name: 'Confirm the balance' }).first(), step: 3 },
  );

  await page.getByRole('button', { name: 'Confirm the balance' }).first().click();
  const dialog = page.getByRole('dialog');
  await doc.capture(
    'sheet',
    'The Is this still right? window: the estimate, what it assumed, what was last confirmed, and the buttons Confirm, Correct it and Cancel.',
    { step: 4 },
  );

  await dialog.getByRole('button', { name: 'Correct it' }).click();
  await dialog.getByLabel('What the statement says').fill('1205.00');
  await doc.capture(
    'correct-it',
    'The same window after Correct it: What the statement says filled in, the date it was true, and the note that a disagreement is recorded as a correction.',
    { step: 7 },
  );
});

test('@doc a debt balance estimated for over a year', async ({ page, doc }) => {
  doc.guide('confirm-what-you-owe');
  await useBalanceStates(page);

  await page.goto('/debt-plan/deb-9002');
  await expect(page.getByRole('heading', { name: 'Old overdraft', level: 1 })).toBeVisible();

  await doc.capture(
    'stale-balance',
    'The Balance now card for a balance estimated for over a year: the last confirmed figure, the words last confirmed over a year ago, and the sentence asking for a statement.',
    { focus: region(page, 'balance-heading') },
  );

  await doc.capture(
    'no-line',
    'The Balance over time card with no chart, saying there is no line to draw because the payment is less than the interest the balance builds up.',
    { focus: region(page, 'over-time-heading') },
  );

  await page.getByRole('button', { name: 'Confirm the balance' }).first().click();
  await doc.capture(
    'stale-sheet',
    'The Time for a statement window: the last figure confirmed, how many months it has been, and the buttons underneath.',
    {},
  );
});

test('@doc the debt plan as a viewer', async ({ page, doc }) => {
  doc.guide('choose-how-to-clear-your-debts');
  await mockApi(page, { session: 'viewer' });

  await page.goto('/debt-plan');
  await expect(page.getByRole('heading', { name: 'Debt plan', level: 1 })).toBeVisible();
  await doc.capture(
    'viewer',
    'The Debt plan page for someone whose role is Viewer: every figure and every date, and nothing to click.',
    { fullPage: true },
  );
});
