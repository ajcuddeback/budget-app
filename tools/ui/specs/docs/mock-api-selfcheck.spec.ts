import { test, expect } from '../../helpers/doc-capture.js';
import { DEMO_ACCOUNTS } from '../../fixtures/demo-data.js';

/**
 * Proves the claim in ADR-0013: a capture run needs no backend, no database and no
 * credentials, because the API is served from fixtures in-process.
 *
 * The page below is a stand-in for the real Angular app — it fetches /api/accounts exactly as
 * the app will. Nothing here starts a server or opens a socket to one.
 */

const APP_SHELL = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<title>Accounts</title>
<style>
  body { font: 15px/1.5 system-ui, sans-serif; margin: 0; background: #f9fafb; color: #101828; }
  main { padding: 24px; max-width: 760px; }
  h1 { font-size: 22px; }
  table { width: 100%; border-collapse: collapse; background: #fff;
          border: 1px solid #e4e7ec; border-radius: 10px; overflow: hidden; }
  th, td { text-align: left; padding: 12px 16px; border-bottom: 1px solid #f2f4f7; }
  th { font-size: 12px; text-transform: uppercase; color: #667085; }
  td.amount, th.amount { text-align: right; font-variant-numeric: tabular-nums; }
  tr:last-child td { border-bottom: 0; }
</style></head>
<body><main>
  <h1>Accounts</h1>
  <table><thead><tr><th>Name</th><th>Type</th><th class="amount">Balance</th></tr></thead>
  <tbody id="rows"></tbody></table>
</main>
<script>
  // Built with createElement/textContent rather than innerHTML. The data here is our own
  // fixture, so this is not exploitable — but a fixture is read as an example, and an agent
  // copying an innerHTML-from-response pattern into real Angular code would be a genuine XSS
  // bug. It also keeps CodeQL green, and a permanently red security check is how teams learn
  // to ignore CI. docs/guides/angular-style.md bans innerHTML with response data; a fixture in
  // this repo should not model what the style guide forbids.
  fetch('/api/accounts').then(r => r.json()).then(data => {
    const rows = document.getElementById('rows');
    for (const account of data.content) {
      const tr = document.createElement('tr');
      for (const cell of [
        { value: account.name, className: '' },
        { value: account.type, className: '' },
        { value: account.balance, className: 'amount' },
      ]) {
        const td = document.createElement('td');
        if (cell.className) td.className = cell.className;
        td.textContent = cell.value;
        tr.appendChild(td);
      }
      rows.appendChild(tr);
    }
    document.body.dataset.loaded = 'true';
  });
</script></body></html>`;

test('@doc the app renders from mocked fixtures with no backend', async ({ page, doc }) => {
  doc.guide('mock-selfcheck');

  // Serve the app shell itself. Combined with the automatic /api/** mock installed by the
  // `doc` fixture, the entire application is satisfied from memory.
  //
  // Exact URL, not '**/accounts': Playwright gives precedence to the LAST registered route,
  // so a broad pattern here would shadow the fixture's /api/** mock and serve this HTML in
  // answer to the page's own fetch('/api/accounts').
  await page.route('http://localhost:4200/accounts', (route) =>
    route.fulfill({ status: 200, contentType: 'text/html', body: APP_SHELL }),
  );

  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto('http://localhost:4200/accounts');
  await page.waitForSelector('body[data-loaded="true"]');

  // The rendered values came from the fixture file, not a database.
  for (const account of DEMO_ACCOUNTS) {
    await expect(page.getByText(account.name, { exact: true })).toBeVisible();
  }
  await expect(page.getByText('-318.44')).toBeVisible();

  // The page really did request the API, and the mock really did answer it.
  expect(doc.apiCalls.map((c) => c.path)).toContain('/api/accounts');

  await doc.capture('accounts-from-fixtures', 'Accounts rendered entirely from demo fixtures.');
});
