import type { Page } from '@playwright/test';
import { DEMO_ACCOUNTS, DEMO_PROBLEMS } from '../../fixtures/demo-data.js';
import { mockApi } from '../../helpers/mock-api.js';
import { expect, test } from '../../helpers/ui-test.js';

/**
 * The accounts screen: `docs/features/accounts.md`, UI section.
 *
 * Three of these assertions are the feature's rules rather than UI taste, and are here so a
 * later redesign cannot quietly drop them:
 *
 * - **Grouped by currency, subtotalled per group, never totalled across** (ADR-0022). The
 *   `noCrossCurrencyTotal` helper below looks for the combined figure anywhere on the page,
 *   because the way this rule gets broken is somebody adding a "Total" row that looks tidy.
 * - **The empty state is a first run, not a fault.** It is the state most users see first and
 *   the one nobody screenshots, so it is checked in both viewports and in the dark theme.
 * - **A VIEWER is offered nothing to click.** The server refuses them anyway; the UI's job is
 *   not to offer a button that is going to be refused.
 */

/** The first-run answer: the same page shape, with nothing in it. */
const NO_ACCOUNTS = { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 };

/** Every active balance added up regardless of currency — the figure that must never appear. */
const FORBIDDEN_CROSS_CURRENCY_TOTAL = '9,481.71';

/** Answer `GET /api/accounts` with something other than the fixtures, leaving the rest mocked. */
async function serveAccounts(page: Page, body: unknown): Promise<void> {
    await page.route(
        (url) => url.pathname === '/api/accounts',
        (route) =>
            route.request().method() === 'GET'
                ? route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
                : route.fallback(),
    );
}

/**
 * Nothing on the page adds two currencies together.
 *
 * Checked two ways, because either on its own is weak: the combined figure must not be printed,
 * and no label may say "total" except the per-currency "Subtotal" that is allowed to.
 */
async function noCrossCurrencyTotal(page: Page): Promise<void> {
    const shown = await page.getByRole('main').innerText();
    expect(shown, 'a cross-currency total is on the page').not.toContain(FORBIDDEN_CROSS_CURRENCY_TOTAL);
    expect(shown.replace(/Subtotal/g, ''), 'something is labelled as a total').not.toMatch(/total/i);
}

test.describe('accounts, as an owner', () => {
    test.beforeEach(async ({ page }) => {
        await mockApi(page, { session: 'owner' });
    });

    test('groups the accounts by currency, subtotals each group, and totals nothing across them', async ({ page, ui }) => {
        await page.goto('/accounts');

        await expect(page.getByRole('heading', { name: 'Accounts', level: 1 })).toBeVisible();
        const accounts = page.getByRole('region', { name: 'Your accounts' });

        // Two currencies, so each gets its own heading and its own subtotal.
        await expect(accounts.getByRole('heading', { name: 'Euro (EUR)', level: 3 })).toBeVisible();
        await expect(accounts.getByRole('heading', { name: 'US Dollar (USD)', level: 3 })).toBeVisible();
        await expect(accounts.getByText('€1,200.00').first()).toBeVisible();
        await expect(accounts.getByText('$8,281.71')).toBeVisible();

        // Name and digits composed, type named in words, balance in the account's own currency.
        await expect(accounts.getByText('Everyday Checking •4417')).toBeVisible();
        await expect(accounts.getByRole('listitem').filter({ hasText: 'Emergency Fund' })).toHaveCount(1);
        await expect(accounts.getByRole('listitem')).toHaveCount(4);
        await expect(accounts.getByText('Current account').first()).toBeVisible();
        await expect(accounts.getByText('-$318.44')).toBeVisible();

        // The archived account is not among them until it is asked for.
        await expect(accounts.getByText('Old Joint Account •0041')).toHaveCount(0);

        await noCrossCurrencyTotal(page);

        await ui.shot('accounts-list');
        expect(await ui.a11y('accounts, populated list')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('the empty state explains what an account is for and offers one thing to do', async ({ page, ui }) => {
        await serveAccounts(page, NO_ACCOUNTS);
        await page.goto('/accounts');

        await expect(page.getByText(/An account is a place money sits/)).toBeVisible();
        await expect(page.getByRole('button', { name: 'Add your first account' })).toBeVisible();
        // A first run is not a fault: nothing here may be an alert or a zero-row table.
        await expect(page.getByRole('alert')).toHaveCount(0);
        await expect(page.getByRole('listitem')).toHaveCount(0);

        await ui.shot('accounts-empty');
        expect(await ui.a11y('accounts, empty')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('the add dialog opens focused on the first field, and Escape returns focus to the button', async ({ page, ui }) => {
        await page.goto('/accounts');
        const opener = page.getByRole('button', { name: 'Add an account' });
        await opener.click();

        const dialog = page.getByRole('dialog', { name: 'Add an account' });
        await expect(dialog).toBeVisible();
        await expect(dialog.getByLabel('Name', { exact: true })).toBeFocused();
        // The household's base currency is the default, and it is a choice, never a conversion.
        await expect(dialog.getByLabel('Currency')).toHaveValue('USD');
        await expect(dialog.getByLabel('Type')).toHaveValue('CHECKING');
        await expect(dialog.getByText(/Nothing is ever converted behind your back/)).toBeVisible();

        await ui.shot('accounts-add-dialog');
        expect(await ui.a11y('accounts, add dialog')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();

        await page.keyboard.press('Escape');
        await expect(page.getByRole('dialog')).toHaveCount(0);
        await expect(opener).toBeFocused();
    });

    test('adds an account and says so, without converting anything', async ({ page, ui }) => {
        await page.goto('/accounts');
        await page.getByRole('button', { name: 'Add an account' }).click();

        const dialog = page.getByRole('dialog', { name: 'Add an account' });
        await dialog.getByLabel('Name', { exact: true }).fill('Holiday Fund');
        await dialog.getByLabel('Last four digits').fill('0190');
        await dialog.getByLabel('Type').selectOption('SAVINGS');
        // A credit card you owe on opens negative, and nothing warns about it.
        await dialog.getByLabel('Opening balance').fill('-125.50');
        await dialog.getByRole('button', { name: 'Save account' }).click();

        await expect(page.getByText('Account added.')).toBeVisible();
        await expect(page.getByText('Holiday Fund •0190')).toBeVisible();
        await ui.shot('accounts-added');
        ui.noErrors();
    });

    test('the edit dialog opens on the account that was clicked, with its own figures', async ({ page, ui }) => {
        await page.goto('/accounts');
        const opener = page.getByRole('button', { name: /Edit\s+Travel Card/ });
        await opener.click();

        const dialog = page.getByRole('dialog', { name: 'Edit account' });
        await expect(dialog.getByLabel('Name', { exact: true })).toHaveValue('Travel Card');
        await expect(dialog.getByLabel('Last four digits')).toHaveValue('8820');
        await expect(dialog.getByLabel('Type')).toHaveValue('CREDIT_CARD');
        // The string the server sent, unparsed — not a number that has been through a double.
        await expect(dialog.getByLabel('Opening balance')).toHaveValue(DEMO_ACCOUNTS[2].openingBalance);

        await ui.shot('accounts-edit-dialog');
        expect(await ui.a11y('accounts, edit dialog')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();

        await dialog.getByRole('button', { name: 'Cancel' }).click();
        await expect(page.getByRole('dialog')).toHaveCount(0);
        await expect(opener).toBeFocused();
    });

    test('archiving asks first and says what archiving does', async ({ page, ui }) => {
        await page.goto('/accounts');
        await page.getByRole('button', { name: /Archive\s+Everyday Checking/ }).click();

        const confirm = page.getByRole('dialog', { name: 'Archive Everyday Checking?' });
        await expect(confirm).toBeVisible();
        await expect(confirm.getByText(/keeps its history, hides it from lists/)).toBeVisible();
        await ui.shot('accounts-archive-confirm');
        expect(await ui.a11y('accounts, archive confirm')).toBe(0);
        await ui.noOverflow();

        await confirm.getByRole('button', { name: 'Archive' }).click();
        await expect(page.getByText('That account has been archived.')).toBeVisible();
        const accounts = page.getByRole('region', { name: 'Your accounts' });
        await expect(accounts.getByText('Everyday Checking •4417')).toHaveCount(0);
        await ui.shot('accounts-after-archive');
        ui.noErrors();
    });

    test('deleting asks first, says permanently, and offers archiving instead', async ({ page, ui }) => {
        await page.goto('/accounts');
        await page.getByRole('button', { name: /Delete\s+Travel Card/ }).click();

        const confirm = page.getByRole('dialog', { name: 'Delete Travel Card?' });
        await expect(confirm).toBeVisible();
        await expect(confirm.getByText(/permanently/)).toBeVisible();
        await expect(confirm.getByText(/archive it instead/i)).toBeVisible();
        await ui.shot('accounts-delete-confirm');
        expect(await ui.a11y('accounts, delete confirm')).toBe(0);
        await ui.noOverflow();

        await confirm.getByRole('button', { name: 'Delete' }).click();
        await expect(page.getByText('That account has been deleted.')).toBeVisible();
        await expect(page.getByText('Travel Card •8820')).toHaveCount(0);
        ui.noErrors();
    });

    test('a refused delete is explained beside the list and the list is left alone', async ({ page, ui }) => {
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
        await expect(page.getByText('Travel Card •8820')).toBeVisible();
        await ui.shot('accounts-delete-refused');
        await ui.noOverflow();
    });

    test('reveals the archived accounts, muted, labelled, and with no subtotal of their own', async ({ page, ui }) => {
        await page.goto('/accounts');

        const archived = page.getByRole('region', { name: 'Archived' });
        const toggle = archived.getByRole('button', { name: 'Show 1 archived' });
        await expect(toggle).toHaveAttribute('aria-expanded', 'false');
        await toggle.click();

        await expect(archived.getByText('Old Joint Account •0041')).toBeVisible();
        await expect(archived.getByText('Archived', { exact: true })).toBeVisible();
        // Still its own balance: archiving hides an account, it does not zero it.
        await expect(archived.getByText('$84.60')).toBeVisible();
        await expect(archived.getByRole('button', { name: /Bring back\s+Old Joint Account/ })).toBeVisible();
        // Revealing them must not move a subtotal, so this section has no grouping heading.
        await expect(archived.getByRole('heading', { level: 3 })).toHaveCount(0);
        // And the active group's subtotal is untouched by the reveal.
        await expect(page.getByRole('region', { name: 'Your accounts' }).getByText('$8,281.71')).toBeVisible();
        await noCrossCurrencyTotal(page);

        await ui.shot('accounts-archived-shown');
        expect(await ui.a11y('accounts, archived shown')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('an account with history is offered no Delete, and a currency locked with the reason', async ({ page, ui }) => {
        // The one state the fixtures cannot reach on their own: `empty: false` is what the server
        // says once an account has transactions, and it is what gates both of these.
        const withHistory = DEMO_ACCOUNTS.map((one) =>
            one.id === DEMO_ACCOUNTS[0].id ? { ...one, empty: false } : one,
        );
        await serveAccounts(page, { content: withHistory, page: 0, size: 200, totalElements: withHistory.length, totalPages: 1 });
        await page.goto('/accounts');

        // Delete would be refused, so it is not offered; archiving still is.
        await expect(page.getByRole('button', { name: /Delete\s+Everyday Checking/ })).toHaveCount(0);
        await expect(page.getByRole('button', { name: /Archive\s+Everyday Checking/ })).toBeVisible();
        await expect(page.getByRole('button', { name: /Delete\s+Emergency Fund/ })).toBeVisible();

        await page.getByRole('button', { name: /Edit\s+Everyday Checking/ }).click();
        const dialog = page.getByRole('dialog', { name: 'Edit account' });
        // Disabled with the reason beside it, never hidden: the person learns why.
        await expect(dialog.getByLabel('Currency')).toBeDisabled();
        await expect(dialog.getByText(/currency cannot be changed once an account has transactions/)).toBeVisible();

        await ui.shot('accounts-currency-locked');
        expect(await ui.a11y('accounts, currency locked')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('a concurrent edit stops and names what changed, rather than overwriting it', async ({ page, ui }) => {
        const theirs = { ...DEMO_ACCOUNTS[0], name: 'Everyday Current', openingBalance: '1700.00', balance: '2538.40', version: 9 };
        await page.route(
            (url) => url.pathname === `/api/accounts/${DEMO_ACCOUNTS[0].id}`,
            (route) => {
                const method = route.request().method();
                if (method === 'PATCH') {
                    return route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify(DEMO_PROBLEMS.staleVersion) });
                }
                if (method === 'GET') {
                    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(theirs) });
                }
                return route.fallback();
            },
        );
        await page.goto('/accounts');
        await page.getByRole('button', { name: /Edit\s+Everyday Checking/ }).click();

        const dialog = page.getByRole('dialog', { name: 'Edit account' });
        await dialog.getByLabel('Name', { exact: true }).fill('Everyday Spending');
        await dialog.getByRole('button', { name: 'Save account' }).click();

        await expect(dialog.getByText('Somebody else saved first')).toBeVisible();
        await expect(dialog.getByText('Name: Everyday Current')).toBeVisible();
        await expect(dialog.getByText('Opening balance: $1,700.00')).toBeVisible();
        // Nothing may be saved while the conflict stands; the two ways out are explicit.
        await expect(dialog.getByRole('button', { name: 'Save account' })).toBeDisabled();
        await expect(dialog.getByRole('button', { name: 'Use theirs' })).toBeVisible();
        await expect(dialog.getByRole('button', { name: 'Keep mine and save' })).toBeVisible();

        await ui.shot('accounts-conflict');
        expect(await ui.a11y('accounts, stale version')).toBe(0);
        await ui.noOverflow();
    });
});

test.describe('accounts, as a viewer', () => {
    test.beforeEach(async ({ page }) => {
        await mockApi(page, { session: 'viewer' });
    });

    test('shows every balance and offers nothing to click', async ({ page, ui }) => {
        await page.goto('/accounts');

        await expect(page.getByRole('heading', { name: 'Accounts', level: 1 })).toBeVisible();
        await expect(page.getByText('Everyday Checking •4417')).toBeVisible();
        await expect(page.getByText('$8,281.71')).toBeVisible();

        await expect(page.getByRole('button', { name: 'Add an account' })).toHaveCount(0);
        await expect(page.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
        await expect(page.getByRole('button', { name: /^Archive/ })).toHaveCount(0);
        await expect(page.getByRole('button', { name: /^Delete/ })).toHaveCount(0);

        // They may still look at the archived ones; they may not bring one back.
        await page.getByRole('button', { name: 'Show 1 archived' }).click();
        await expect(page.getByText('Old Joint Account •0041')).toBeVisible();
        await expect(page.getByRole('button', { name: /^Bring back/ })).toHaveCount(0);
        await noCrossCurrencyTotal(page);

        await ui.shot('accounts-viewer');
        expect(await ui.a11y('accounts, viewer')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('is told why the empty screen is empty, rather than being offered a refused button', async ({ page, ui }) => {
        await serveAccounts(page, NO_ACCOUNTS);
        await page.goto('/accounts');

        await expect(page.getByText(/your role in this household is read-only/i)).toBeVisible();
        await expect(page.getByRole('button', { name: /^Add/ })).toHaveCount(0);

        await ui.shot('accounts-empty-viewer');
        expect(await ui.a11y('accounts, empty for a viewer')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });
});

test.describe('accounts, in the dark theme', () => {
    test.beforeEach(async ({ page }) => {
        await mockApi(page, { session: 'owner' });
        await page.addInitScript(() => localStorage.setItem('budget-owl.theme', 'dark'));
    });

    test('the list, the groups and the archived rows stay legible', async ({ page, ui }) => {
        await page.goto('/accounts');
        await expect(page.getByText('Everyday Checking •4417')).toBeVisible();
        await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

        await page.getByRole('button', { name: 'Show 1 archived' }).click();
        await expect(page.getByText('Old Joint Account •0041')).toBeVisible();

        await ui.shot('accounts-list-dark');
        expect(await ui.a11y('accounts, list in dark')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('the add dialog and the delete confirmation stay legible', async ({ page, ui }) => {
        await page.goto('/accounts');
        await page.getByRole('button', { name: 'Add an account' }).click();
        await expect(page.getByRole('dialog', { name: 'Add an account' })).toBeVisible();
        await ui.shot('accounts-add-dialog-dark');
        expect(await ui.a11y('accounts, add dialog in dark')).toBe(0);

        await page.keyboard.press('Escape');
        await page.getByRole('button', { name: /Delete\s+Travel Card/ }).click();
        await expect(page.getByRole('dialog', { name: 'Delete Travel Card?' })).toBeVisible();
        await ui.shot('accounts-delete-confirm-dark');
        expect(await ui.a11y('accounts, delete confirm in dark')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });

    test('the empty state is a welcome in the dark too, not a void', async ({ page, ui }) => {
        await serveAccounts(page, NO_ACCOUNTS);
        await page.goto('/accounts');

        await expect(page.getByRole('button', { name: 'Add your first account' })).toBeVisible();
        await ui.shot('accounts-empty-dark');
        expect(await ui.a11y('accounts, empty in dark')).toBe(0);
        await ui.noOverflow();
        ui.noErrors();
    });
});
