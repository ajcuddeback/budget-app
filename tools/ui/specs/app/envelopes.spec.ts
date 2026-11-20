import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * The envelopes screen: `docs/features/envelopes.md`, UI section.
 *
 * Four of these assertions are the feature's rules rather than UI taste, and are here so a later
 * redesign cannot quietly drop them:
 *
 * - **What is LEFT is the headline.** The running total sits underneath it, not above. That
 *   ordering is the whole feature: it is the number somebody acts on while still in the shop.
 * - **An overspent envelope shows a negative figure.** Clamping to zero would hide exactly what
 *   the person needs to know, and there is no rollover to absorb it (ADR-0031).
 * - **Logging spend asks for an amount and nothing else.** This is the 81-times-a-month action.
 * - **The log says where every row came from.** A figure typed and one taken from a transaction
 *   are different kinds of claim and must never be presented identically.
 */

test.describe('envelopes, as an owner', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'owner' });
  });

  test('leads with what is left, and totals the month', async ({ page, ui }) => {
    await page.goto('/envelopes');

    await expect(page.getByRole('heading', { name: 'Envelopes', level: 1 })).toBeVisible();
    const envelopes = page.getByRole('region', { name: "Your envelopes" });

    await expect(envelopes.getByText('Groceries').first()).toBeVisible();
    // The remaining figure, not the spend, is the big number on each row.
    await expect(envelopes.locator('.envelope-remaining').first()).toContainText('$116.70');
    await expect(envelopes.locator('.envelope-running').first()).toContainText('$283.30');
    // Safe to total here where it is not across accounts: one household, one currency. The
    // archived "Summer trip" is deliberately not in it — a retired envelope is not part of the
    // month's plan.
    await expect(envelopes.locator('.totals')).toContainText('$700.00');

    await ui.shot('envelopes-list');
    expect(await ui.a11y('envelopes, populated')).toBe(0);
    await ui.noOverflow();
    ui.noErrors();
  });

  test('shows an overspent envelope as negative rather than clamping it', async ({ page, ui }) => {
    await page.goto('/envelopes');

    const overspent = page.locator('.envelope').filter({ hasText: 'Eating Out' });
    await expect(overspent.locator('.envelope-remaining')).toContainText('-$11.20');
    await expect(overspent.locator('.envelope-remaining')).toContainText('over');
    // Marked, not merely coloured, so it survives greyscale and a colour-blind reader.
    await expect(overspent.locator('.meter.overspent')).toBeVisible();

    await ui.shot('envelopes-overspent');
    expect(await ui.a11y('envelopes, one overspent')).toBe(0);
    ui.noErrors();
  });

  test('logs spend from an amount and nothing else', async ({ page, ui }) => {
    await page.goto('/envelopes');

    await page
      .locator('.envelope')
      .filter({ hasText: 'Groceries' })
      .getByRole('button', { name: 'Log spend' })
      .click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /Log spend in Groceries/ })).toBeVisible();
    // Everything but the amount says it is optional, and nothing else is required to submit.
    await expect(dialog.getByLabel('Amount')).toHaveAttribute('inputmode', 'decimal');

    await dialog.getByLabel('Amount').fill('18.40');

    await ui.shot('envelopes-quick-add');
    expect(await ui.a11y('quick add')).toBe(0);

    await dialog.getByRole('button', { name: 'Log it' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.getByRole('status').filter({ hasText: 'Logged' })).toBeVisible();

    ui.noErrors();
  });

  test('says where every row of the log came from', async ({ page, ui }) => {
    await page.goto('/envelopes');

    await page
      .locator('.envelope')
      .filter({ hasText: 'Groceries' })
      .getByRole('button', { name: /Show 3 entries/ })
      .click();

    const log = page.locator('.log').first();
    await expect(log.getByText('Typed')).toBeVisible();
    await expect(log.getByText('From a transaction').first()).toBeVisible();
    await expect(log.getByText('$104.75')).toBeVisible();

    await ui.shot('envelopes-log');
    expect(await ui.a11y('envelope log')).toBe(0);
    ui.noErrors();
  });

  test('says what deleting an envelope takes and what it keeps', async ({ page, ui }) => {
    await page.goto('/envelopes');

    await page
      .locator('.envelope')
      .filter({ hasText: 'Groceries' })
      .getByRole('button', { name: 'Delete' })
      .click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText(/Your transactions are kept/)).toBeVisible();

    await ui.shot('envelopes-delete-confirm');
    expect(await ui.a11y('envelope delete confirmation')).toBe(0);
    ui.noErrors();
  });

  test('moves to another month and shows its own envelopes', async ({ page, ui }) => {
    await page.goto('/envelopes');

    await page.getByRole('button', { name: 'Previous month' }).click();

    // July has no fixtures, so this is also the empty state — the one most people see first.
    await expect(page.getByText(/an amount you decide on/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add your first envelope' })).toBeVisible();

    await ui.shot('envelopes-empty');
    expect(await ui.a11y('envelopes, empty month')).toBe(0);
    ui.noErrors();
  });

  test('renders on a phone without anything running off the side', async ({ page, ui }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/envelopes');

    await expect(page.getByText('Groceries').first()).toBeVisible();

    await ui.shot('envelopes-list-phone');
    await ui.noOverflow();
    expect(await ui.a11y('envelopes on a phone')).toBe(0);
    ui.noErrors();
  });
});

test.describe('envelopes, as a viewer', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { session: 'viewer' });
  });

  test('offers a viewer the figures and nothing to click', async ({ page, ui }) => {
    await page.goto('/envelopes');

    await expect(page.getByText('$116.70')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Log spend' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Add an envelope' })).toHaveCount(0);
    // Reading the log is not a write, so it stays.
    await expect(page.getByRole('button', { name: /Show 3 entries/ })).toBeVisible();

    await ui.shot('envelopes-viewer');
    expect(await ui.a11y('envelopes as a viewer')).toBe(0);
    ui.noErrors();
  });
});
