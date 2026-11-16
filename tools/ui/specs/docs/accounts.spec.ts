import type { Page } from '@playwright/test';
import { DEMO_ACCOUNTS, DEMO_PROBLEMS } from '../../fixtures/demo-data.js';
import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';

/**
 * Screens for the slice 3 guides: add-an-account and archive-or-delete-an-account.
 *
 * Everything renders from tools/ui/fixtures/demo-data.ts (ADR-0013). Two states the fixtures
 * cannot reach on their own are staged here with a route override, the same way
 * specs/app/accounts.spec.ts stages them, and both are honest fixture-shaped answers:
 *
 *  - a household with no accounts yet, which is the first thing most people see;
 *  - `empty: false`, which is what the server says once an account has transactions, and which
 *    is what hides Delete and locks the currency.
 */

/** Answer `GET /api/accounts` with something other than the fixtures, leaving the rest mocked. */
async function serveAccounts(page: Page, rows: readonly unknown[]): Promise<void> {
    await page.route(
        (url) => url.pathname === '/api/accounts',
        (route) =>
            route.request().method() === 'GET'
                ? route.fulfill({
                      status: 200,
                      contentType: 'application/json',
                      body: JSON.stringify({ content: rows, page: 0, size: 200, totalElements: rows.length, totalPages: rows.length === 0 ? 0 : 1 }),
                  })
                : route.fallback(),
    );
}

test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
});

test('@doc the accounts screen, and adding one', async ({ page, doc }) => {
    doc.guide('add-an-account');
    await page.goto('/accounts');
    await expect(page.getByRole('heading', { name: 'Accounts', level: 1 })).toBeVisible();
    await doc.capture('accounts', 'The Accounts screen: accounts grouped by currency, each group with its own subtotal.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: 'Add an account' }),
        step: 2,
    });

    await page.getByRole('button', { name: 'Add an account' }).click();
    const dialog = page.getByRole('dialog', { name: 'Add an account' });
    await expect(dialog).toBeVisible();
    await doc.capture('add-form', 'The Add an account window, before anything is filled in.');

    await dialog.getByLabel('Name', { exact: true }).fill('Metro Credit Card');
    await dialog.getByLabel('Last four digits').fill('3317');
    await dialog.getByLabel('Type').selectOption('CREDIT_CARD');
    await dialog.getByLabel('Opening balance').fill('-420.50');
    // The window scrolls its own content: at this height the last field sits below the fold, and
    // `fill` does not move it, because Playwright counts an element the sticky footer covers as
    // already in view. Scroll it ourselves, which is what a reader has to do.
    await dialog.evaluate((element: HTMLElement) => element.scrollTo(0, element.scrollHeight));
    await doc.capture('opening-balance', 'The Opening balance field, reached by scrolling down inside the window, with a negative amount in it.', {
        highlight: dialog.getByLabel('Opening balance'),
        step: 7,
    });
    await doc.capture('add-filled', 'The Add an account window filled in for a credit card, with Save account at the bottom.', {
        highlight: dialog.getByRole('button', { name: 'Save account' }),
        step: 8,
    });

    await dialog.getByRole('button', { name: 'Save account' }).click();
    await expect(page.getByText('Account added.')).toBeVisible();
    await expect(page.getByText('Metro Credit Card •3317')).toBeVisible();
    await doc.capture('added', 'The Accounts screen after adding the card: "Account added." and the new row in the US Dollar group.', {
        fullPage: true,
    });
});

test('@doc the first account on a new household', async ({ page, doc }) => {
    doc.guide('add-an-account');
    await serveAccounts(page, []);
    await page.goto('/accounts');
    await expect(page.getByRole('button', { name: 'Add your first account' })).toBeVisible();
    await doc.capture('empty', 'The Accounts screen with no accounts yet, offering Add your first account.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: 'Add your first account' }),
        step: 2,
    });
});

test('@doc a household whose accounts are all in one currency', async ({ page, doc }) => {
    doc.guide('add-an-account');
    // The common case, and the one the fixtures cannot show alongside their second currency:
    // with a single currency there are no grouping headings and no subtotal (ADR-0022).
    await serveAccounts(page, DEMO_ACCOUNTS.filter((one) => one.currency === 'USD'));
    await page.goto('/accounts');
    await expect(page.getByRole('heading', { name: 'Accounts', level: 1 })).toBeVisible();
    await doc.capture('one-currency', 'The Accounts screen for a household with one currency: a plain list, no currency headings and no subtotal.', {
        fullPage: true,
    });
});

test('@doc correcting an account', async ({ page, doc }) => {
    doc.guide('add-an-account');
    await page.goto('/accounts');
    await page.getByRole('button', { name: /Edit\s+Everyday Checking/ }).click();
    const dialog = page.getByRole('dialog', { name: 'Edit account' });
    await expect(dialog).toBeVisible();
    await doc.capture('edit-form', 'The Edit account window, filled in with what the account says now.');

    await dialog.getByLabel('Name', { exact: true }).fill('Everyday Spending');
    await dialog.getByRole('button', { name: 'Save account' }).click();
    await expect(page.getByText('Account saved.')).toBeVisible();
    await doc.capture('saved', 'The Accounts screen after saving: "Account saved." and the new name in the list.', {
        fullPage: true,
    });
});

test('@doc a currency that can no longer be changed', async ({ page, doc }) => {
    doc.guide('add-an-account');
    // `empty: false` is what the server sends once an account has transactions recorded in it.
    const withHistory = DEMO_ACCOUNTS.map((one) =>
        one.id === DEMO_ACCOUNTS[0].id ? { ...one, empty: false } : one,
    );
    await serveAccounts(page, withHistory);
    await page.goto('/accounts');
    await page.getByRole('button', { name: /Edit\s+Everyday Checking/ }).click();
    const dialog = page.getByRole('dialog', { name: 'Edit account' });
    await expect(dialog.getByLabel('Currency')).toBeDisabled();
    await doc.capture('currency-locked', 'The Edit account window with Currency greyed out and the reason printed under it.', {
        highlight: dialog.getByText(/currency cannot be changed once an account has transactions/),
    });
});

test('@doc the accounts screen as a viewer', async ({ page, doc }) => {
    doc.guide('add-an-account');
    await mockApi(page, { session: 'viewer' });
    await page.goto('/accounts');
    await expect(page.getByRole('heading', { name: 'Accounts', level: 1 })).toBeVisible();
    await doc.capture('viewer', 'The Accounts screen for someone whose role is Viewer: the balances, and nothing to click.', {
        fullPage: true,
    });
});

test('@doc archiving an account and bringing it back', async ({ page, doc }) => {
    doc.guide('archive-or-delete-an-account');
    await page.goto('/accounts');
    await doc.capture('archive-button', 'The Travel Card row, with its Archive button.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: /Archive\s+Travel Card/ }),
        step: 2,
    });

    await page.getByRole('button', { name: /Archive\s+Travel Card/ }).click();
    const confirm = page.getByRole('dialog', { name: 'Archive Travel Card?' });
    await expect(confirm).toBeVisible();
    await doc.capture('archive-confirm', 'The window asking whether to archive Travel Card.', {
        highlight: confirm.getByRole('button', { name: 'Archive' }),
        step: 3,
    });

    await confirm.getByRole('button', { name: 'Archive' }).click();
    await expect(page.getByText('That account has been archived.')).toBeVisible();
    await doc.capture('archived', 'The Accounts screen after archiving: the card has left the list and the Archived section now says Show 2 archived.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: /Show 2 archived/ }),
        step: 4,
    });

    await page.getByRole('button', { name: /Show 2 archived/ }).click();
    await expect(page.getByText('Travel Card •8820')).toBeVisible();
    await doc.capture('archived-shown', 'The Archived section opened, listing the archived accounts with a Bring back button each.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: /Bring back\s+Travel Card/ }),
        step: 2,
    });

    await page.getByRole('button', { name: /Bring back\s+Travel Card/ }).click();
    await expect(page.getByText('That account is back in your lists.')).toBeVisible();
    await doc.capture('brought-back', 'The Accounts screen after bringing the card back: "That account is back in your lists."', {
        fullPage: true,
    });
});

test('@doc deleting an account', async ({ page, doc }) => {
    doc.guide('archive-or-delete-an-account');
    await page.goto('/accounts');
    await page.getByRole('button', { name: /Delete\s+Travel Card/ }).click();
    const confirm = page.getByRole('dialog', { name: 'Delete Travel Card?' });
    await expect(confirm).toBeVisible();
    await doc.capture('delete-confirm', 'The window asking whether to delete Travel Card, saying it cannot be undone.', {
        highlight: confirm.getByRole('button', { name: 'Delete' }),
        step: 3,
    });

    await confirm.getByRole('button', { name: 'Delete' }).click();
    await expect(page.getByText('That account has been deleted.')).toBeVisible();
    await doc.capture('deleted', 'The Accounts screen after deleting: "That account has been deleted." and the row gone.', {
        fullPage: true,
    });
});

test('@doc a delete the app will not do', async ({ page, doc }) => {
    doc.guide('archive-or-delete-an-account');
    await page.route(
        (url) => url.pathname === `/api/accounts/${DEMO_ACCOUNTS[2].id}`,
        (route) =>
            route.request().method() === 'DELETE'
                ? route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify(DEMO_PROBLEMS.accountNotEmpty) })
                : route.fallback(),
    );
    await page.goto('/accounts');
    await page.getByRole('button', { name: /Delete\s+Travel Card/ }).click();
    await page.getByRole('dialog', { name: 'Delete Travel Card?' }).getByRole('button', { name: 'Delete' }).click();
    await expect(page.getByRole('alert')).toContainText('Archive it instead');
    await doc.capture('delete-refused', 'The message shown when an account has transactions: it cannot be deleted, and archiving is offered instead.', {
        fullPage: true,
        highlight: page.getByRole('alert'),
    });
});

test('@doc an account with transactions is offered no Delete', async ({ page, doc }) => {
    doc.guide('archive-or-delete-an-account');
    const withHistory = DEMO_ACCOUNTS.map((one) =>
        one.id === DEMO_ACCOUNTS[0].id ? { ...one, empty: false } : one,
    );
    await serveAccounts(page, withHistory);
    await page.goto('/accounts');
    await expect(page.getByRole('button', { name: /Delete\s+Everyday Checking/ })).toHaveCount(0);
    await doc.capture('delete-not-offered', 'The Everyday Checking row, which has transactions: Edit and Archive, and no Delete.', {
        fullPage: true,
        highlight: page.getByRole('button', { name: /Archive\s+Everyday Checking/ }),
    });
});
