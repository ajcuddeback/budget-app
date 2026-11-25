import { expect, test } from '../../helpers/doc-capture.js';
import { mockApi } from '../../helpers/mock-api.js';
import type { Page } from '@playwright/test';

/**
 * Screens for the slice 11 guides: save-towards-a-goal and change-what-you-set-aside.
 *
 * Every figure comes out of the GENERATED goal fixtures (`goal-api.generated.ts`), so these
 * captures show the real application's arithmetic: Japan, $890.00 of $3,500.00 by November 2027,
 * reaching it in December 2027 at $110.00 and then $165.00 from September, and $163.13 a month to
 * make November. The dialogs' previews are the server's answers for exactly the figures typed
 * here; the mock refuses anything it was not given, so a capture cannot show an invented one.
 */

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await mockApi(page, { session: 'owner' });
});

const region = (page: Page, heading: string) =>
  page.locator(`section.card[aria-labelledby="${heading}"]`);

test('@doc the goals page and adding a goal', async ({ page, doc }) => {
  doc.guide('save-towards-a-goal');

  await page.goto('/goals');
  await expect(page.getByRole('heading', { name: 'Goals', level: 1 })).toBeVisible();
  await doc.capture(
    'list',
    'The Goals page: New laptop, Japan and Emergency fund, each with a bar, how much is saved of the target, and a sentence saying when it gets there.',
    { fullPage: true },
  );

  const add = page.getByRole('button', { name: 'Add a goal' }).first();
  await doc.capture('add-button', 'The Add a goal button at the top right of the Goals page.', {
    highlight: add,
    step: 1,
  });

  await add.click();
  const dialog = page.getByRole('dialog', { name: 'Add a goal' });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('What it is for').fill('New bike');
  await dialog.getByLabel('How much it needs').fill('900');
  await expect(dialog.getByText('No monthly amount set.')).toBeVisible();
  await dialog.getByLabel('Month you want it by (optional)').fill('2027-06');
  await expect(dialog.getByText(/a month gets you there by June 2027/)).toBeVisible();
  await dialog.getByLabel('Each month (optional)').fill('75');
  await expect(dialog.getByText(/At \$75\.00 a month you reach it in/)).toBeVisible();
  await doc.capture(
    'add-dialog',
    'The Add a goal window filled in for a new bike: $900 by June 2027 at $75 a month, with the answer underneath saying when that gets there and what it would take to make June.',
    { focus: dialog },
  );

  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Show archived goals' }).click();
  await expect(page.getByRole('link', { name: /Open.*Wedding gift/ })).toBeVisible();
  await doc.capture(
    'archived',
    'The Archived goals section, shown under the list, with the Wedding gift goal and the note that an archived goal is no longer part of the month’s plan.',
    { focus: page.locator('#goals-archived') },
  );
});

test('@doc one goal, setting money aside and taking it out', async ({ page, doc }) => {
  doc.guide('save-towards-a-goal');

  await page.goto('/goals/gol-0002');
  await expect(page.getByRole('heading', { name: 'Emergency fund', level: 1 })).toBeVisible();
  await doc.capture(
    'detail',
    'The Emergency fund page: how far along it is, the account it is kept in, the plan, and the history with a withdrawal for a vet bill.',
    { fullPage: true },
  );
  await doc.capture(
    'progress',
    'The How far along card: $2,340.00 of $6,000.00 with its bar, $3,660.00 to go, and the Emergency Fund account’s own balance labelled as the account’s.',
    { focus: region(page, 'goal-progress-heading') },
  );
  await doc.capture(
    'history',
    'The History card: what was already saved when tracking started, then each amount set aside or taken out, with the vet bill marked Withdrawal.',
    { focus: region(page, 'goal-history-heading') },
  );

  const setAside = page.getByRole('button', { name: 'Set money aside' });
  await doc.capture('set-aside-button', 'The Set money aside button under the goal’s name.', {
    highlight: setAside,
    step: 1,
  });
  await setAside.click();
  const aside = page.getByRole('dialog', { name: 'Set money aside for Emergency fund' });
  await expect(aside.getByLabel('Amount')).toHaveValue('100');
  await doc.capture(
    'set-aside-dialog',
    'The Set money aside window, filled in with the $100.00 still to come for August 2026, today’s date, and a note saying no account is touched.',
    { focus: aside },
  );
  await aside.getByRole('button', { name: 'Set aside' }).click();
  await expect(page.getByText('Money set aside.')).toBeVisible();
  await doc.capture('set-aside-done', 'The message Money set aside. at the top of the goal page.', {
    focus: page.getByRole('status').filter({ hasText: 'Money set aside.' }),
  });

  await page.getByRole('button', { name: 'Take money out' }).click();
  const out = page.getByRole('dialog', { name: 'Take money out of Emergency fund' });
  await out.getByLabel('Amount').fill('250');
  await out.getByLabel('Note (optional)').fill('New tyres');
  await doc.capture(
    'take-out-dialog',
    'The Take money out window with $250 and the note New tyres.',
    { focus: out },
  );
});

test('@doc changing what you set aside each month', async ({ page, doc }) => {
  doc.guide('change-what-you-set-aside');

  await page.goto('/goals/gol-0001');
  await expect(page.getByRole('heading', { name: 'Japan', level: 1 })).toBeVisible();
  const plan = region(page, 'goal-plan-heading');
  await doc.capture(
    'plan',
    'The plan card for Japan: $110.00 a month in August 2026, $165.00 a month from September 2026, when that reaches the target, and $163.13 a month to make November 2027.',
    { focus: plan },
  );

  const change = plan.getByRole('button', { name: 'Change' });
  await doc.capture('change-button', 'The Change button on the plan card.', {
    highlight: change,
    step: 1,
  });
  await change.click();
  const dialog = page.getByRole('dialog', { name: 'Change the plan for Japan' });
  await expect(dialog.getByText(/At \$110\.00 a month you reach it in/)).toBeVisible();
  await dialog.getByLabel('Each month').fill('165');
  await expect(dialog.getByText(/At \$165\.00 a month you reach it in/)).toBeVisible();
  await doc.capture(
    'dialog',
    'The Change the plan window with $165 a month starting this month, and the answer underneath saying when that reaches the target.',
    { focus: dialog },
  );

  await dialog.getByLabel(/Next month/).check();
  await expect(dialog.getByText(/stays as it is/)).toBeVisible();
  await doc.capture(
    'next-month',
    'The same window with Next month, September 2026, chosen, and the sentence saying August stays as it is.',
    { focus: dialog },
  );
});

test('@doc a goal as a viewer', async ({ page, doc }) => {
  doc.guide('save-towards-a-goal');
  await mockApi(page, { session: 'viewer' });

  await page.goto('/goals/gol-0001');
  await expect(page.getByRole('heading', { name: 'Japan', level: 1 })).toBeVisible();
  await doc.capture(
    'viewer',
    'The Japan goal as a viewer: every figure, the plan and the history, and no buttons to change anything.',
    { fullPage: true },
  );
});
