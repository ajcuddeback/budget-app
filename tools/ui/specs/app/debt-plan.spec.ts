import { DEMO_DEBT_STATES } from '../../fixtures/demo-data.js';
import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * The second household, whose two debts exist to show the balance states.
 *
 * `GET /api/debts` is overridden per test rather than in the shared mock, so the demo figures the
 * guide captures cannot be perturbed by a state that only these checks need. The details
 * themselves are already served.
 */
async function serveBalanceStates(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/api/debts?*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(DEMO_DEBT_STATES),
    }),
  );
  // Both of these debts carry `paymentCount: 0`, so their history is an empty page. Served here
  // because the shared mock holds details for them and not histories; it states no figure and
  // computes nothing, which is why it is safe to write by hand where a balance would not be.
  await page.route('**/api/debts/deb-900*/payments*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }),
    }),
  );
}

/**
 * The debt plan screen: `docs/features/debt-plan.md`, UI section.
 *
 * Deliberately asserts **structure and rules rather than particular figures**. The amounts come
 * from `tools/ui/fixtures/demo-data.ts`, whose derived values are generated from the real backend
 * solver; pinning them here would make this spec a second, hand-written copy of arithmetic it does
 * not do, and the first thing to go stale. What it does pin is what would otherwise render as a
 * lie:
 *
 * - **Both strategies are drawn whichever is selected** — the comparison is a product requirement.
 * - **The chart is readable without being seen.** A line a screen reader cannot use is a picture
 *   of somebody's money; axe would not catch that on its own, so the description is asserted.
 * - **The counted-once rule is on the page**, in the household's own language.
 * - **A `VIEWER` has no write control at all** — absent, not disabled-looking.
 * - **The same debts appear in both structures**: the table at desktop, the ladder at 390.
 */
test.describe('the debt plan, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('ranks the debts, draws both strategies, and says what each one is paying', async ({
    page,
    ui,
  }) => {
    await page.goto('/debt-plan');

    await expect(page.getByRole('heading', { level: 1, name: /Debt plan/ })).toBeVisible();

    // Both named strategies, whichever is in use: the comparison card is not a view of the
    // selection.
    const comparison = page.getByRole('region', { name: /Avalanche or snowball/ });
    await expect(comparison).toContainText('Avalanche');
    await expect(comparison).toContainText('Snowball');

    // The chart, and the words that carry the same figures.
    const chart = page.getByRole('img', { name: 'Balance to zero' });
    await expect(chart).toBeVisible();
    const described = await chart.getAttribute('aria-describedby');
    expect(described).toBeTruthy();
    await expect(page.locator(`#${described}`)).toContainText(/Avalanche:/);

    // The counted-once rule, which is the whole reason a minimum is not asked for twice.
    await expect(page.getByText(/counted once in Bills & income/i)).toBeVisible();

    await ui.shot('debt-plan-overview');
    expect(await ui.a11y('the debt plan, populated')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('offers the three orders of attack and says which is in use', async ({ page, ui }) => {
    await page.goto('/debt-plan');

    const orders = page.getByRole('group', { name: /Order of attack/ });
    await expect(orders.getByRole('button', { name: 'Avalanche' })).toBeVisible();
    await expect(orders.getByRole('button', { name: 'Snowball' })).toBeVisible();
    await expect(orders.getByRole('button', { name: /Your own order/ })).toBeVisible();
    // Pressed rather than merely coloured, so the selection is audible as well as visible.
    await expect(orders.getByRole('button', { pressed: true })).toHaveCount(1);

    await ui.shot('debt-plan-strategy');
    ui.noErrors();
  });

  test('lets the monthly amount every date derives from be set', async ({ page, ui }) => {
    await page.goto('/debt-plan');

    // It had nowhere to be entered: the strip reported it and nothing changed it.
    await page.getByRole('button', { name: 'Change the monthly amount' }).click();
    const amount = page.getByLabel('Towards debt each month');
    await expect(amount).toHaveAttribute('type', 'text');
    await expect(amount).toHaveAttribute('inputmode', 'decimal');
    // The same figure the plan holds, written without the wire's trailing zeros.
    await expect(amount).toHaveValue('905');

    await ui.shot('debt-plan-monthly-amount');
    expect(await ui.a11y('the monthly amount, open for editing')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('opens a dialog whose amounts are text inputs with a decimal keypad', async ({
    page,
    ui,
  }) => {
    await page.goto('/debt-plan');
    await page.getByRole('button', { name: 'Add a debt' }).first().click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // type="text" with inputmode="decimal", never type="number" (ADR-0006).
    const balance = dialog.getByLabel('Balance now');
    await expect(balance).toHaveAttribute('type', 'text');
    await expect(balance).toHaveAttribute('inputmode', 'decimal');
    await expect(dialog.getByLabel('Annual rate')).toHaveAttribute('inputmode', 'decimal');

    await ui.shot('debt-plan-add-a-debt');
    expect(await ui.a11y('the add-a-debt dialog')).toBe(0);
    ui.noErrors();
  });
});

test.describe('one debt, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('labels what it worked out, what you entered and what the plan assigned', async ({
    page,
    ui,
  }) => {
    await page.goto('/debt-plan/deb-0001');

    await expect(page.getByRole('heading', { level: 1, name: 'Visa' })).toBeVisible();

    const terms = page.getByRole('region', { name: 'Loan terms' });
    // Three labels, not two. A figure the app worked out must never be mistaken for one the
    // household asserted, and the count above the panel is derived from the cells themselves.
    await expect(terms).toContainText('entered');
    await expect(terms).toContainText('solved');
    await expect(terms).toContainText('Solved');
    await expect(terms).toContainText(/from your plan/i);
    // The balance is never solved, and the panel says so.
    await expect(terms).toContainText('The balance is never worked out from the other figures');

    await ui.shot('debt-detail-terms');
    expect(await ui.a11y('one debt, terms and payments')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('draws the pace against the minimum, and says the second line is the comparison', async ({
    page,
    ui,
  }) => {
    await page.goto('/debt-plan/deb-0001');

    const chart = page.getByRole('img', { name: 'Balance over time' });
    await expect(chart).toBeVisible();
    // Two genuinely different lines here, unlike the plan's two strategies.
    await expect(page.locator('.chart-legend')).toContainText('At the minimum — the comparison');

    const described = await chart.getAttribute('aria-describedby');
    expect(described).toBeTruthy();
    await expect(page.locator(`#${described}`)).toContainText('What you are paying:');

    await ui.shot('debt-detail-over-time');
    ui.noErrors();
  });

  test('says how every payment got here, and tells a correction from a payment', async ({
    page,
    ui,
  }) => {
    await page.goto('/debt-plan/deb-0001');

    const history = page.getByRole('region', { name: 'Payment history' });
    await expect(history).toContainText('You logged it');
    await expect(history).toContainText('Statement correction');
    // The rule the splits were computed under, where somebody reading them will meet it.
    await expect(history).toContainText('until a statement says otherwise');

    // Two rows share a date — a payment and the statement correction that followed it — and the
    // balance the newest one leaves is the balance the page leads with. The exact row order is
    // pinned in `debt-detail.page.spec.ts`, where both structures can be inspected at once; here
    // what matters is that the two halves of the screen agree.
    await expect(history).toContainText('$6,180.00');
    await expect(page.getByRole('region', { name: 'Balance now' })).toContainText('$6,180.00');

    await ui.shot('debt-detail-history');
    await ui.noOverflow();
    ui.noErrors();
  });

  test('explains an empty history rather than showing an empty table', async ({ page, ui }) => {
    await page.goto('/debt-plan/deb-0002');

    await expect(page.getByRole('heading', { level: 1, name: 'Car loan' })).toBeVisible();
    await expect(page.getByText('Nothing recorded against this one yet')).toBeVisible();

    await ui.shot('debt-detail-no-payments');
    expect(await ui.a11y('one debt with no payments')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('opens the payment sheet prefilled, with amounts as text inputs', async ({ page, ui }) => {
    await page.goto('/debt-plan/deb-0001');
    await page.getByRole('button', { name: 'Log a payment' }).first().click();

    const sheet = page.getByRole('dialog');
    await expect(sheet).toBeVisible();

    const amount = sheet.getByLabel('Amount paid');
    await expect(amount).toHaveAttribute('type', 'text');
    await expect(amount).toHaveAttribute('inputmode', 'decimal');
    // Prefilled with what the plan puts against this debt, with one tap for fifty more.
    await expect(amount).toHaveValue('445');
    await expect(sheet.getByRole('button', { name: /\$495\.00/ })).toBeVisible();
    // "Paid from" is not a transaction and not where the debt is held.
    await expect(sheet).toContainText('no transaction is created');

    await ui.shot('debt-detail-log-payment');
    expect(await ui.a11y('the log-a-payment sheet')).toBe(0);
    ui.noErrors();
  });

  test('shows what the amount would record before it is recorded', async ({ page, ui }) => {
    await page.goto('/debt-plan/deb-0001');
    await page.getByRole('button', { name: 'Log a payment' }).first().click();

    const sheet = page.getByRole('dialog');
    // The server's own figures, built by the same code as logging, so they cannot disagree with
    // what gets stored. The demo Visa was paid on August 20 and the demo's today is August 20, so
    // August's interest is already charged (ADR-0043: once per debt per month): the whole $445
    // comes off $6,180.00, and the server says why.
    await expect(sheet).toContainText(
      '$0.00 interest · $445.00 principal · balance after $5,735.00',
    );
    await expect(sheet).toContainText("August 2026's interest was charged on an earlier payment");

    // One tap for fifty more asks again, immediately: still no interest, and all of it principal.
    await sheet.getByRole('button', { name: /\$495\.00/ }).click();
    await expect(sheet).toContainText('$0.00 interest · $495.00 principal');

    await ui.shot('debt-detail-payment-preview');
    expect(await ui.a11y('the log-a-payment sheet, with the split')).toBe(0);
    ui.noErrors();
  });

  test('still records a payment when the split cannot be worked out', async ({ page, ui }) => {
    await page.goto('/debt-plan/deb-0001');
    await page.getByRole('button', { name: 'Log a payment' }).first().click();

    const sheet = page.getByRole('dialog');
    await expect(sheet).toContainText('$0.00 interest');

    // An amount the generator has no answer for. The preview is a convenience and must never be
    // the thing that stops somebody recording what they paid.
    await sheet.getByLabel('Amount paid').fill('200.00');
    await expect(sheet).toContainText('could not work out the split just now');
    await expect(sheet).not.toContainText('interest ·');
    await expect(sheet.getByRole('button', { name: 'Record it' })).toBeEnabled();

    await ui.shot('debt-detail-payment-preview-unavailable');
    expect(await ui.a11y('the sheet with no preview')).toBe(0);
    // No `noErrors()` here, and no console assertion either: this check *provokes* a refusal, and
    // the browser logs a failed request as a console error. What is under test is that the sheet
    // goes on working, which is asserted above.
  });

  test('steps between debts by id, never by a name in the URL', async ({ page, ui }) => {
    await page.goto('/debt-plan/deb-0001');

    // The switcher at desktop reads "2 Car loan"; the stepper on a phone reads "Next: Car loan".
    // Both are links to the same id, which is the thing being asserted.
    await page
      .getByRole('navigation', { name: 'Your debts' })
      .getByRole('link', { name: /Car loan/ })
      .click();
    await expect(page).toHaveURL(/\/debt-plan\/deb-0002$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Car loan' })).toBeVisible();

    await ui.shot('debt-detail-switched');
    ui.noErrors();
  });
});

test.describe('one debt, as a viewer', () => {
  test('shows every figure and not one control', async ({ page, ui }) => {
    await mockApi(page, { session: 'viewer' });
    await page.goto('/debt-plan/deb-0001');

    await expect(page.getByRole('heading', { level: 1, name: 'Visa' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Payment history' })).toContainText(
      'You logged it',
    );

    await expect(page.getByRole('button', { name: 'Log a payment' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Edit terms' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Confirm/ })).toHaveCount(0);

    await ui.shot('debt-detail-viewer');
    expect(await ui.a11y('one debt, read-only')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});

test.describe('the household arranging its own order', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
    await page.goto('/debt-plan');
    await page.getByRole('group', { name: /Order of attack/ }).getByRole('button', {
      name: /Your own order/,
    }).click();
    await expect(page.getByRole('button', { name: /Your own order/, pressed: true })).toBeVisible();
  });

  test('changes nothing until something is moved', async ({ page, ui }) => {
    // Choosing Your own order sends the order already on screen, so the plan it comes back with
    // is the one that was there: debt-free April 2029 and $2,677.02, exactly as under avalanche.
    const comparison = page.getByRole('region', { name: /Avalanche or snowball/ });
    await expect(comparison).toContainText(
      'Your own order: debt-free April 2029, with $2,677.02 of interest',
    );
    await expect(page.getByRole('region', { name: 'Your debts' })).toContainText(
      'Move a debt up or down',
    );
    // All three lines are the same line here, so the chart draws one and keys all three on it
    // rather than offering three keys over a single visible stroke.
    const chart = page.getByRole('region', { name: /Balance to zero/ });
    await expect(chart.locator('.chart-legend li')).toHaveCount(1);
    await expect(chart.locator('.chart-legend')).toContainText(
      'Your own order · Avalanche · Snowball',
    );

    await ui.shot('debt-plan-own-order');
    expect(await ui.a11y('the plan under Your own order')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('names each move by debt and direction, with no up on the first rung', async ({
    page,
    ui,
  }) => {
    const list = page.getByRole('region', { name: 'Your debts' });
    // Buttons, not a drag handle: every one reachable from a keyboard and named for a screen
    // reader. The visible label is inside the accessible name, so the two agree (WCAG 2.5.3).
    await expect(list.getByRole('button', { name: 'Move up: Visa' })).toHaveCount(0);
    await expect(list.getByRole('button', { name: 'Move down: Visa' })).toBeVisible();
    await expect(list.getByRole('button', { name: 'Move up: Student loan' })).toBeVisible();
    await expect(list.getByRole('button', { name: 'Move down: Student loan' })).toHaveCount(0);

    await ui.shot('debt-plan-move-controls');
    ui.noErrors();
  });

  test('recomputes every date from the order the household chose', async ({ page, ui }) => {
    const list = page.getByRole('region', { name: 'Your debts' });

    // Visa, Car, Student → Student, Car, Visa. Three moves, and the server answers each one.
    await list.getByRole('button', { name: 'Move up: Student loan' }).click();
    await expect(page.getByRole('status').first()).toContainText(
      'Student loan is now rung 2 of 3',
    );
    await list.getByRole('button', { name: 'Move up: Student loan' }).click();
    await list.getByRole('button', { name: 'Move down: Visa' }).click();

    // Paying the 4.5% loan first costs $1,670 more and finishes two months later, and the page
    // says so — the figures are the server's, not a local reshuffle of the old ones.
    const comparison = page.getByRole('region', { name: /Avalanche or snowball/ });
    // $4,346.72 exactly: interest is rounded to the cent every month, so the total the server
    // sends is already a figure in cents and the page shows it as it is.
    await expect(comparison).toContainText(
      'Your own order: debt-free June 2029, with $4,346.72 of interest',
    );
    // And both cards still carry their own: the comparison is not a view of the choice.
    await expect(comparison).toContainText('$2,677.02');

    await ui.shot('debt-plan-own-order-reversed');
    expect(await ui.a11y('the plan after rearranging')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});

test.describe('a balance nobody has confirmed lately', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
    await serveBalanceStates(page);
  });

  test('marks an estimate as an estimate and says what it assumed', async ({ page, ui }) => {
    await page.goto('/debt-plan/deb-9001');

    await expect(page.getByRole('heading', { level: 1, name: 'Store card' })).toBeVisible();

    const balance = page.getByRole('region', { name: 'Balance now' });
    // $1,196.64 against an anchor of $1,240.00: the estimate takes the minimum off as well as
    // adding the interest, so it is LOWER than the figure it rolled forward from.
    await expect(balance).toContainText('$1,196.64');
    await expect(balance).toContainText('estimated — not confirmed');

    const terms = page.getByRole('region', { name: 'Loan terms' });
    await expect(terms).toContainText('assuming the minimum was paid and the interest in between');
    await expect(terms).toContainText('$1,240.00');

    await ui.shot('debt-detail-estimated');
    expect(await ui.a11y('a debt whose balance is an estimate')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('opens the sheet that turns an estimate into a figure somebody asserted', async ({
    page,
    ui,
  }) => {
    await page.goto('/debt-plan/deb-9001');
    await page.getByRole('button', { name: 'Confirm the balance' }).first().click();

    const sheet = page.getByRole('dialog');
    await expect(sheet).toBeVisible();
    await expect(sheet).toContainText('Is this still right?');
    await expect(sheet).toContainText('3 months of $45.00 payments');
    await expect(sheet).toContainText('Last confirmed: $1,240.00');
    // Confirm, correct, and the two quieter answers beneath.
    await expect(sheet.getByRole('button', { name: /Confirm \$1,196\.64/ })).toBeVisible();
    await expect(sheet.getByRole('button', { name: 'Correct it' })).toBeVisible();
    await expect(sheet.getByRole('button', { name: /Stop estimating/ })).toBeVisible();
    await expect(sheet.getByRole('button', { name: 'Log a payment instead' })).toBeVisible();

    await ui.shot('debt-detail-confirm-estimate');
    expect(await ui.a11y('the confirm-the-estimate sheet')).toBe(0);
    ui.noErrors();
  });

  test('opens a figure for correcting without a second sheet', async ({ page, ui }) => {
    await page.goto('/debt-plan/deb-9001');
    await page.getByRole('button', { name: 'Confirm the balance' }).first().click();
    await page.getByRole('dialog').getByRole('button', { name: 'Correct it' }).click();

    const sheet = page.getByRole('dialog');
    const figure = sheet.getByLabel('What the statement says');
    await expect(figure).toHaveAttribute('type', 'text');
    await expect(figure).toHaveAttribute('inputmode', 'decimal');
    await expect(figure).toHaveValue('1196.64');
    await expect(sheet).toContainText('recorded as a correction in the history');

    await ui.shot('debt-detail-correct-estimate');
    expect(await ui.a11y('correcting the estimate')).toBe(0);
    ui.noErrors();
  });

  test('stops extrapolating after a year and asks for a statement', async ({ page, ui }) => {
    await page.goto('/debt-plan/deb-9002');

    await expect(page.getByRole('heading', { level: 1, name: 'Old overdraft' })).toBeVisible();

    const balance = page.getByRole('region', { name: 'Balance now' });
    // The figure reverts to the anchor and comes back CONFIRMED; the staleness is the flag.
    await expect(balance).toContainText('$860.00');
    await expect(balance).toContainText('last confirmed over a year ago');

    const terms = page.getByRole('region', { name: 'Loan terms' });
    await expect(terms).toContainText('rather than a year of guesses');
    await expect(terms).toContainText('A statement figure would put it right');

    // A payment that never covers the interest has no projection, so there is no line — and the
    // card says why rather than drawing an axis around an empty box.
    await expect(page.getByRole('img', { name: 'Balance over time' })).toHaveCount(0);
    await expect(page.getByText(/no line to draw/)).toBeVisible();
    await expect(terms).toContainText('Never, at this payment');

    await ui.shot('debt-detail-stale');
    expect(await ui.a11y('a balance that has gone stale')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('says on the plan which balances are estimates and which have gone stale', async ({
    page,
    ui,
  }) => {
    await page.goto('/debt-plan');

    const list = page.getByRole('region', { name: 'Your debts' });
    await expect(list).toContainText('3 months assumed since you last confirmed it');
    await expect(list).toContainText('Estimated for over a year');
    await expect(list).toContainText('Check a statement');

    await ui.shot('debt-plan-balance-states');
    expect(await ui.a11y('the plan, with an estimate and a stale balance')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});

test.describe('the debt screens, in the dark theme', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
    await mockApi(page, { session: 'owner' });
  });

  test('the plan reads correctly in the dark', async ({ page, ui }) => {
    await page.goto('/debt-plan');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.getByRole('img', { name: 'Balance to zero' })).toBeVisible();

    await ui.shot('debt-plan-dark');
    expect(await ui.a11y('the debt plan, dark')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('one debt reads correctly in the dark, chart and splits included', async ({ page, ui }) => {
    await page.goto('/debt-plan/deb-0001');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.getByRole('img', { name: 'Balance over time' })).toBeVisible();

    await ui.shot('debt-detail-dark');
    expect(await ui.a11y('one debt, dark')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});

test.describe('the debt plan, as a viewer', () => {
  test('shows every figure and not one control', async ({ page, ui }) => {
    await mockApi(page, { session: 'viewer' });
    await page.goto('/debt-plan');

    await expect(page.getByRole('heading', { level: 1, name: /Debt plan/ })).toBeVisible();
    await expect(page.getByRole('region', { name: /Balance to zero/ })).toBeVisible();

    // Absent, not disabled-looking: a control that is always on screen and always refused
    // teaches people to ignore refusals.
    await expect(page.getByRole('button', { name: 'Add a debt' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /Use avalanche/ })).toHaveCount(0);
    await expect(page.getByRole('group', { name: /Order of attack/ })).toHaveCount(0);
    await expect(page.getByText(/Your plan uses/)).toBeVisible();

    await ui.shot('debt-plan-viewer');
    expect(await ui.a11y('the debt plan, read-only')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });
});
