import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { HouseholdRole } from '../../../core/auth.models';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  FakeAuth,
  OWNER,
  OWNER_MEMBERSHIP,
  apiError,
  buttonNamed,
  fakeAuth,
} from '../../../testing/helpers';
import { Account } from '../../accounts/data/account.models';
import { AccountsService } from '../../accounts/data/accounts.service';
import { ImportBatch, ImportPreview } from '../data/import.models';
import { ImportsService } from '../data/imports.service';
import { ImportPage } from './import.page';

const CURRENT: Account = {
  id: 'a1',
  name: 'Current',
  lastFour: '4412',
  type: 'CHECKING',
  currency: 'GBP',
  openingBalance: '1000.0000',
  balance: '820.0000',
  archived: false,
  createdAt: '2026-10-01T09:00:00Z',
  updatedAt: '2026-11-11T09:00:00Z',
  version: 1,
  empty: false,
};

const SAVINGS: Account = { ...CURRENT, id: 'a2', name: 'Savings', lastFour: null };
const OLD: Account = { ...CURRENT, id: 'a3', name: 'Old card', lastFour: '0001', archived: true };

const DIGEST = 'd'.repeat(64);

/** An OFX preview: two new rows, one that looks typed, one already imported. */
const PREVIEW: ImportPreview = {
  previewDigest: DIGEST,
  account: { id: 'a1', name: 'Current', lastFour: '4412', currency: 'GBP' },
  format: 'OFX',
  decodedBytes: 2048,
  charset: 'UTF-8',
  charsetSource: 'DECLARED',
  charsetCandidates: [],
  charsetsAllowed: ['UTF-8', 'windows-1252'],
  suggestedCharset: null,
  dateFormat: null,
  dateFormatSource: null,
  dateFormatCandidates: [],
  needs: [],
  statement: {
    from: '2026-08-01',
    to: '2026-08-31',
    bankClosingBalance: '-700.79',
    accountHint: 'MATCHES',
  },
  counts: { read: 4, new: 2, duplicate: 1, suspected: 1, unreadable: 0, zero: 0 },
  totals: { moneyIn: '1200.0000', moneyOut: '-3.5000', currency: 'GBP' },
  balanceNow: '820.0000',
  balanceAfter: '-700.7900',
  balanceAfterWithSuspected: '-704.2900',
  attention: [
    {
      row: 3,
      kind: 'SUSPECTED_DUPLICATE',
      date: '2026-08-03',
      amount: '-3.5000',
      payeeName: 'Blue Bottle',
      code: null,
      column: null,
      text: null,
      resembles: { transactionId: 't9', date: '2026-08-02' },
    },
    {
      row: 4,
      kind: 'DUPLICATE',
      date: '2026-08-04',
      amount: '-12.0000',
      payeeName: 'Bakery',
      code: null,
      column: null,
      text: null,
      resembles: null,
    },
  ],
  attentionTruncated: false,
  sample: [
    {
      row: 1,
      date: '2026-08-01',
      amount: '1200.0000',
      currency: 'GBP',
      payeeName: '<img src=x onerror="alert(1)">',
      note: null,
      payeeCut: false,
      noteCut: false,
    },
  ],
  identical: [],
  reusedIdentifier: false,
  textCut: false,
  csv: null,
};

/** A CSV that has not been mapped yet: the server asks before it reads a row. */
const CSV_ASKING: ImportPreview = {
  ...PREVIEW,
  previewDigest: null,
  format: 'CSV',
  statement: null,
  counts: null,
  totals: null,
  balanceNow: null,
  balanceAfter: null,
  balanceAfterWithSuspected: null,
  needs: ['MAPPING'],
  attention: [],
  sample: [],
  csv: {
    columnCount: 3,
    headerLabels: ['Date', 'Description', 'Amount'],
    firstRecords: [['03/08/2026', 'Blue Bottle', '-3.50']],
    suggested: {
      delimiter: ',',
      headerRow: 1,
      dateFormat: 'dd/MM/uuuu',
      dateFormatCandidates: ['dd/MM/uuuu'],
      decimalSeparator: '.',
      groupingSeparator: null,
      columns: { '0': 'DATE', '1': 'PAYEE', '2': 'AMOUNT' },
    },
    mappingMatchesSaved: null,
  },
};

const BATCH: ImportBatch = {
  id: 'b1',
  accountId: 'a1',
  accountName: 'Current',
  format: 'OFX',
  imported: 2,
  duplicate: 1,
  excluded: 1,
  unreadable: 0,
  zero: 0,
  importedBy: 'u1',
  createdAt: '2026-11-25T09:00:00Z',
  removable: 2,
};

/** A file the page can read: only what `File` offers that the page uses. */
function fakeFile(name: string, bytes: number[], size = bytes.length): File {
  return {
    name,
    size,
    arrayBuffer: () => Promise.resolve(new Uint8Array(bytes).buffer),
  } as unknown as File;
}

describe('ImportPage', () => {
  let auth: FakeAuth;
  const imports = {
    preview: vi.fn(),
    commit: vi.fn(),
    list: vi.fn(),
    get: vi.fn(),
    undo: vi.fn(),
    mapping: vi.fn(),
  };
  const accountsService = { list: vi.fn() };
  let harness: RouterTestingHarness;

  async function render(
    options: { role?: HouseholdRole; url?: string; accounts?: readonly Account[] } = {},
  ): Promise<HTMLElement> {
    auth = fakeAuth({
      ...OWNER,
      household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
    });
    for (const fn of [...Object.values(imports), accountsService.list]) {
      fn.mockReset();
    }
    const accounts = options.accounts ?? [CURRENT, SAVINGS, OLD];
    accountsService.list.mockReturnValue(
      of({ content: [...accounts], page: 0, size: 200, totalElements: 3, totalPages: 1 }),
    );
    imports.list.mockReturnValue(
      of({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }),
    );
    imports.mapping.mockReturnValue(of(null));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'import', component: ImportPage },
          { path: 'transactions', children: [] },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: ImportsService, useValue: imports },
        { provide: AccountsService, useValue: accountsService },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(options.url ?? '/import', ImportPage);
    await stable();
    return harness.routeNativeElement as HTMLElement;
  }

  async function stable(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
    await harness.fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    harness.detectChanges();
    await harness.fixture.whenStable();
  }

  function choose(root: HTMLElement, selector: string, value: string): void {
    const field = root.querySelector<HTMLSelectElement | HTMLInputElement>(selector);
    if (!field) {
      throw new Error(`nothing matches ${selector}`);
    }
    field.value = value;
    field.dispatchEvent(new Event('change'));
  }

  async function pickFile(root: HTMLElement, file: File): Promise<void> {
    const input = root.querySelector<HTMLInputElement>('#import-file');
    if (!input) {
      throw new Error('no file input');
    }
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: { length: 1, item: () => file },
    });
    input.dispatchEvent(new Event('change'));
    await stable();
  }

  async function previewOfx(root: HTMLElement, preview: ImportPreview = PREVIEW): Promise<void> {
    imports.preview.mockReturnValue(of(preview));
    choose(root, '#import-account', 'a1');
    await pickFile(root, fakeFile('Chase-Aug.QFX', [0x4f, 0x46, 0x58]));
    buttonNamed(root, /Preview the import/).click();
    await stable();
  }

  const text = (root: HTMLElement): string => root.textContent ?? '';

  // ---------------------------------------------------------------------------- who sees what

  it('tells a viewer plainly, instead of showing a form that would be refused', async () => {
    const root = await render({ role: 'VIEWER' });

    expect(text(root)).toContain("Viewers can't import statements");
    expect(root.querySelector('#import-file')).toBeNull();
    expect(imports.preview).not.toHaveBeenCalled();
    expect(accountsService.list).not.toHaveBeenCalled();
  });

  it('offers only open accounts, as name •1234', async () => {
    const root = await render();

    const options = Array.from(root.querySelectorAll('#import-account option')).map((option) =>
      option.textContent?.trim(),
    );
    expect(options).toContain('Current •4412');
    expect(options).toContain('Savings');
    expect(options).not.toContain('Old card •0001');
  });

  it("preselects the account an account row's Import action named", async () => {
    const root = await render({ url: '/import?accountId=a2' });

    expect(root.querySelector<HTMLSelectElement>('#import-account')?.value).toBe('a2');
  });

  it('does not preselect an account the household cannot import into', async () => {
    const root = await render({ url: '/import?accountId=a3' });

    expect(root.querySelector<HTMLSelectElement>('#import-account')?.value).toBe('');
  });

  it('uses a native file input that accepts the four extensions', async () => {
    const root = await render();

    const input = root.querySelector<HTMLInputElement>('#import-file');
    expect(input?.type).toBe('file');
    expect(input?.accept).toBe('.csv,.ofx,.qfx,.qif');
    expect(root.querySelector('label[for="import-file"]')).not.toBeNull();
  });

  // ------------------------------------------------------------------------------- step one

  it('refuses a file over 5 MB before reading it, as a courtesy', async () => {
    const root = await render();
    const arrayBuffer = vi.fn();
    const huge = { name: 'big.csv', size: 5 * 1024 * 1024 + 1, arrayBuffer } as unknown as File;

    await pickFile(root, huge);

    expect(text(root)).toContain('larger than 5 MB');
    expect(arrayBuffer).not.toHaveBeenCalled();
  });

  it('sends the file as base64 with the format from its name, and never its name', async () => {
    const root = await render();

    await previewOfx(root);

    expect(imports.preview).toHaveBeenCalledTimes(1);
    const [request] = imports.preview.mock.lastCall as [Record<string, unknown>];
    expect(request).toEqual({ accountId: 'a1', format: 'OFX', file: btoa('OFX') });
    expect(JSON.stringify(request)).not.toContain('Chase-Aug');
  });

  it('keeps nothing about the file in browser storage', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const root = await render();

    await previewOfx(root);

    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });

  // ------------------------------------------------------------------------------- the preview

  it('shows the counts, the money, and a confirm button naming the count and the account', async () => {
    const root = await render();

    await previewOfx(root);

    const summary = root.querySelector('[data-testid="import-preview"]');
    expect(summary?.textContent).toContain('New');
    expect(summary?.textContent).toContain('Already in your ledger');
    expect(summary?.textContent).toContain('£1,200.00');
    expect(buttonNamed(root, /Import 2 transactions into Current •4412/)).toBeTruthy();
  });

  it('says when the bank balance and ours agree after the import', async () => {
    const root = await render();

    await previewOfx(root);

    const bank = root.querySelector('[data-testid="bank-balance"]');
    expect(bank?.textContent).toContain('Your bank says the balance is -£700.79');
    expect(bank?.textContent).toContain('They match.');
  });

  it('calls out a difference between the bank balance and ours', async () => {
    const root = await render();

    await previewOfx(root, {
      ...PREVIEW,
      statement: { ...PREVIEW.statement!, bankClosingBalance: '-700.80' },
    });

    const bank = root.querySelector('[data-testid="bank-balance"]');
    expect(bank?.textContent).toContain('Those two differ.');
    expect(bank?.textContent).not.toContain('They match.');
  });

  it('warns, naming the account, when the file looks like a different one', async () => {
    const root = await render();

    await previewOfx(root, {
      ...PREVIEW,
      statement: { ...PREVIEW.statement!, accountHint: 'DIFFERS' },
    });

    const warning = root.querySelector('[data-testid="account-differs"]');
    expect(warning?.textContent).toContain('different account from Current •4412');
  });

  it('shows no warning when the account matches', async () => {
    const root = await render();

    await previewOfx(root);

    expect(root.querySelector('[data-testid="account-differs"]')).toBeNull();
  });

  it('renders a hostile payee as text, never as markup', async () => {
    const root = await render();

    await previewOfx(root);

    const sample = root.querySelector('[data-testid="import-sample"]');
    expect(sample?.querySelector('img')).toBeNull();
    expect(sample?.textContent).toContain('<img src=x onerror="alert(1)">');
    expect(sample?.querySelector('bdi')?.getAttribute('dir')).toBe('auto');
  });

  it('leaves suspected rows out unless ticked, and sends an override only for that row', async () => {
    const root = await render();
    imports.commit.mockReturnValue(of(BATCH));
    await previewOfx(root);

    const box = root.querySelector<HTMLInputElement>('input[type="checkbox"]');
    expect(box?.checked).toBe(false);
    expect(box?.closest('label')?.textContent).toContain('Blue Bottle');

    box!.click();
    await stable();
    buttonNamed(root, /Import 3 transactions into Current •4412/).click();
    await stable();

    const [request] = imports.commit.mock.lastCall as [Record<string, unknown>];
    expect(request).toEqual({
      accountId: 'a1',
      format: 'OFX',
      file: btoa('OFX'),
      previewDigest: DIGEST,
      suspectedDuplicates: 'EXCLUDE',
      overrides: [{ row: 3, include: true }],
    });
  });

  it('"Include all" sends the default, with no overrides', async () => {
    const root = await render();
    imports.commit.mockReturnValue(of(BATCH));
    await previewOfx(root);

    buttonNamed(root, /Include all/).click();
    await stable();
    buttonNamed(root, /Import 3 transactions/).click();
    await stable();

    const [request] = imports.commit.mock.lastCall as [Record<string, unknown>];
    expect(request['suspectedDuplicates']).toBe('INCLUDE');
    expect(request).not.toHaveProperty('overrides');
  });

  // ----------------------------------------------------------------------------- stale preview

  it('previews again instead of committing when the ledger moved, and says what changed', async () => {
    const root = await render();
    await previewOfx(root);
    imports.commit.mockReturnValue(throwError(() => apiError(409, 'import-preview-stale')));
    imports.preview.mockReturnValue(
      of({ ...PREVIEW, counts: { ...PREVIEW.counts!, new: 0, duplicate: 3 } }),
    );

    buttonNamed(root, /Import 2 transactions/).click();
    await stable();

    expect(imports.commit).toHaveBeenCalledTimes(1);
    expect(imports.preview).toHaveBeenCalledTimes(2);
    // The same request again: same file, same options.
    expect(imports.preview.mock.calls[1]).toEqual(imports.preview.mock.calls[0]);
    const notice = root.querySelector('[data-testid="import-stale"]');
    expect(notice?.textContent).toContain('nothing was imported');
    expect(notice?.textContent).toContain('2 of these were added by somebody else');
    expect(root.querySelector('[data-testid="import-done"]')).toBeNull();
  });

  // ----------------------------------------------------------------------------------- CSV

  it('shows a CSV mapping first, and applies it only on Update preview', async () => {
    const root = await render();
    imports.preview.mockReturnValue(of(CSV_ASKING));
    choose(root, '#import-account', 'a1');
    await pickFile(root, fakeFile('export.csv', [0x44]));
    buttonNamed(root, /Preview the import/).click();
    await stable();

    // The suggestion is on screen, not acted on: the first request carried no mapping.
    expect(root.querySelector('[data-testid="import-mapping"]')).not.toBeNull();
    expect(root.querySelector<HTMLSelectElement>('#import-role-0')?.value).toBe('DATE');
    expect(imports.preview.mock.calls[0][0]).not.toHaveProperty('options');
    expect(root.querySelector('[data-testid="import-confirm"]')).toBeNull();

    imports.preview.mockReturnValue(of({ ...PREVIEW, format: 'CSV', csv: CSV_ASKING.csv }));
    buttonNamed(root, /Update preview/).click();
    await stable();

    expect(imports.preview.mock.calls[1][0]).toMatchObject({
      format: 'CSV',
      options: {
        delimiter: ',',
        headerRow: 1,
        dateFormat: 'dd/MM/uuuu',
        columns: { '0': 'DATE', '1': 'PAYEE', '2': 'AMOUNT' },
      },
    });
    expect(root.querySelector('[data-testid="import-confirm"]')).not.toBeNull();
  });

  it('holds the confirm button once a setting changes, until the preview is updated', async () => {
    const root = await render();
    imports.preview.mockReturnValue(of(CSV_ASKING));
    choose(root, '#import-account', 'a1');
    await pickFile(root, fakeFile('export.csv', [0x44]));
    buttonNamed(root, /Preview the import/).click();
    await stable();
    imports.preview.mockReturnValue(of({ ...PREVIEW, format: 'CSV', csv: CSV_ASKING.csv }));
    buttonNamed(root, /Update preview/).click();
    await stable();

    choose(root, '#import-role-1', 'NOTE');
    await stable();

    const confirm = root.querySelector<HTMLButtonElement>('[data-testid="import-confirm"]');
    expect(confirm?.disabled).toBe(true);
    expect(text(root)).toContain('Update the preview to see what that does');
  });

  // ------------------------------------------------------------------------- success and undo

  it('links to the ledger for that account and offers undo, naming the count and account', async () => {
    const root = await render();
    imports.commit.mockReturnValue(of(BATCH));
    imports.undo.mockReturnValue(of(undefined));
    await previewOfx(root);

    buttonNamed(root, /Import 2 transactions/).click();
    await stable();

    const done = root.querySelector('[data-testid="import-done"]');
    expect(done?.textContent).toContain('Imported 2 transactions into Current •4412');
    expect(done?.querySelector('a')?.getAttribute('href')).toBe('/transactions?accountId=a1');

    buttonNamed(root, /Undo this import/).click();
    await stable();
    const dialog = document.querySelector('dialog');
    expect(dialog?.textContent).toContain(
      'removes the 2 transactions this import added to Current',
    );
    expect(dialog?.textContent).toContain('lose that link');
    expect(imports.undo).not.toHaveBeenCalled();

    buttonNamed(dialog as HTMLElement, /Undo this import/).click();
    await stable();
    expect(imports.undo).toHaveBeenCalledWith('b1');
    expect(text(root)).toContain('Import undone. 2 transactions removed.');
  });

  it('renders a refusal from its code', async () => {
    const root = await render();
    imports.preview.mockReturnValue(
      throwError(() => apiError(400, 'import-file-malformed', { reason: 'dtd' })),
    );
    choose(root, '#import-account', 'a1');
    await pickFile(root, fakeFile('x.ofx', [0x3c]));
    buttonNamed(root, /Preview the import/).click();
    await stable();

    expect(text(root)).toContain('contains a document type declaration');
  });
});
