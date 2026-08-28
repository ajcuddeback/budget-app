import { test, expect } from '../../helpers/doc-capture.js';

/**
 * Proves the user-guide capture pipeline works — annotated screenshots land in
 * userguide/images/ with a manifest — before the real app exists.
 *
 * Run with:  tools/userguide-capture.sh --selfcheck
 */

const FIXTURE = `data:text/html,${encodeURIComponent(`
<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Accounts</title>
<style>
  * { box-sizing: border-box; }
  body { font: 15px/1.5 system-ui, sans-serif; margin: 0; color: #101828; background: #f9fafb; }
  header { background: #fff; border-bottom: 1px solid #e4e7ec; padding: 14px 24px;
           display: flex; align-items: center; gap: 24px; }
  .brand { font-weight: 700; }
  nav a { color: #475467; text-decoration: none; margin-right: 18px; }
  nav a.active { color: #101828; font-weight: 600; }
  main { padding: 24px; max-width: 900px; }
  .row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
  h1 { font-size: 22px; margin: 0; }
  .btn { background: #175cd3; color: #fff; border: 0; border-radius: 8px;
         padding: 10px 16px; font-size: 14px; font-weight: 600; cursor: pointer; }
  table { width: 100%; border-collapse: collapse; background: #fff;
          border: 1px solid #e4e7ec; border-radius: 10px; overflow: hidden; }
  th, td { text-align: left; padding: 12px 16px; border-bottom: 1px solid #f2f4f7; }
  th { font-size: 12px; text-transform: uppercase; letter-spacing: .04em; color: #667085; }
  td.amount, th.amount { text-align: right; font-variant-numeric: tabular-nums; }
  tr:last-child td { border-bottom: 0; }
</style></head>
<body>
  <header>
    <span class="brand">Budget</span>
    <nav><a href="#" class="active">Accounts</a><a href="#">Transactions</a><a href="#">Budgets</a></nav>
  </header>
  <main>
    <div class="row">
      <h1>Accounts</h1>
      <button class="btn" type="button">Add account</button>
    </div>
    <table>
      <thead><tr><th>Name</th><th>Type</th><th class="amount">Balance</th></tr></thead>
      <tbody>
        <tr><td>Everyday Checking</td><td>Checking</td><td class="amount">2,480.15</td></tr>
        <tr><td>Emergency Fund</td><td>Savings</td><td class="amount">6,120.00</td></tr>
        <tr><td>Travel Card</td><td>Credit card</td><td class="amount">−318.44</td></tr>
      </tbody>
    </table>
  </main>
</body></html>`)}`;

test('@doc capture pipeline produces an annotated screenshot', async ({ page, doc }) => {
  doc.guide('selfcheck');
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto(FIXTURE);

  // Plain screenshot of a screen.
  const plain = await doc.capture('accounts-list', 'The Accounts screen listing your accounts.');
  expect(plain).toContain('.png');

  // Annotated: ring + numbered badge on the element a step tells the reader to click.
  const addButton = page.getByRole('button', { name: 'Add account' });
  const annotated = await doc.capture(
    'accounts-add-button',
    'Select Add account in the top right.',
    { highlight: addButton, step: 1 },
  );
  expect(annotated).toContain('.png');

  // Element-scoped: crop to one region rather than the whole viewport.
  const focused = await doc.capture('accounts-table', 'Your accounts and their balances.', {
    focus: page.locator('table'),
  });
  expect(focused).toContain('.png');
});
