import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HouseholdRole } from '../../../core/auth.models';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  OWNER,
  OWNER_MEMBERSHIP,
  apiError,
  buttonNamed,
  fakeAuth,
  settle,
} from '../../../testing/helpers';
import { Account } from '../../accounts/data/account.models';
import { AccountsService } from '../../accounts/data/accounts.service';
import { CategoriesService } from '../../categories/data/categories.service';
import { Ledger, Transaction } from '../data/transaction.models';
import { TransactionsService } from '../data/transactions.service';
import { Transfer } from '../data/transfer.models';
import { TransfersService } from '../data/transfers.service';
import { TransactionsPage } from './transactions.page';

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

const SAVINGS: Account = { ...CURRENT, id: 'a2', name: 'Savings', type: 'SAVINGS', lastFour: null };

/** The row somebody starts from: money left Current, imported from its statement. */
const OUT: Transaction = {
  id: 't1',
  accountId: 'a1',
  accountName: 'Current',
  date: '2026-08-03',
  amount: '-200.0000',
  currency: 'GBP',
  payeeId: 'p1',
  payeeName: 'TFR TO SAVINGS',
  categoryId: 'c3',
  categoryName: 'Groceries',
  categoryParentName: null,
  note: null,
  status: 'CLEARED',
  transfer: false,
  transferGroupId: null,
  createdAt: '2026-08-03T09:00:00Z',
  updatedAt: '2026-08-03T09:00:00Z',
  version: 4,
};

/** Its other half, in Savings' statement, two days later. */
const IN: Transaction = {
  ...OUT,
  id: 't2',
  accountId: 'a2',
  accountName: 'Savings',
  date: '2026-08-05',
  amount: '200.0000',
  payeeName: 'TFR FROM CURRENT',
  version: 7,
};

/** Another Current row: never a candidate, because a transfer needs two accounts. */
const SAME_ACCOUNT: Transaction = { ...OUT, id: 't3', amount: '200.0000', payeeName: 'Refund' };

/** An existing leg: never a candidate, because it is already half of something. */
const LEG: Transaction = {
  ...IN,
  id: 't4',
  payeeName: null,
  categoryId: null,
  categoryName: null,
  transfer: true,
  transferGroupId: 'g9',
};

const PAIRED: Transfer = {
  id: 'g1',
  fromAccountId: 'a1',
  fromAccountName: 'Current',
  toAccountId: 'a2',
  toAccountName: 'Savings',
  fromDate: '2026-08-03',
  toDate: '2026-08-05',
  sent: '200.0000',
  sentCurrency: 'GBP',
  received: '200.0000',
  receivedCurrency: 'GBP',
  crossCurrency: false,
  impliedRate: null,
  fromNote: null,
  toNote: null,
  createdAt: '2026-08-03T09:00:00Z',
  updatedAt: '2026-11-25T09:00:00Z',
  version: 13,
};

const ledger = (transactions: readonly Transaction[]): Ledger => ({
  content: [...transactions],
  pageTotal: null,
  pageTotalCurrency: null,
  page: 0,
  size: 50,
  totalElements: transactions.length,
  totalPages: 1,
});

describe('TransactionsPage — pairing two rows into a transfer', () => {
  const service = {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    suggestPayees: vi.fn(),
  };
  const transfers = {
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    pair: vi.fn(),
    split: vi.fn(),
  };
  const accountsService = { list: vi.fn() };
  const categoriesService = { list: vi.fn() };

  async function render(
    role: HouseholdRole = 'OWNER',
    rows: readonly Transaction[] = [OUT, IN, SAME_ACCOUNT, LEG],
  ): Promise<ComponentFixture<TransactionsPage>> {
    for (const fn of [
      ...Object.values(service),
      ...Object.values(transfers),
      accountsService.list,
      categoriesService.list,
    ]) {
      fn.mockReset();
    }
    service.list.mockReturnValue(of(ledger(rows)));
    accountsService.list.mockReturnValue(
      of({ content: [CURRENT, SAVINGS], page: 0, size: 200, totalElements: 2, totalPages: 1 }),
    );
    categoriesService.list.mockReturnValue(
      of({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
    );

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: fakeAuth({
            ...OWNER,
            household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role },
          }),
        },
        { provide: TransactionsService, useValue: service },
        { provide: TransfersService, useValue: transfers },
        { provide: AccountsService, useValue: accountsService },
        { provide: CategoriesService, useValue: categoriesService },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(TransactionsPage);
    await settle(fixture);
    return fixture;
  }

  const root = (fixture: ComponentFixture<TransactionsPage>): HTMLElement => fixture.nativeElement;

  function rowOf(fixture: ComponentFixture<TransactionsPage>, payee: string): HTMLElement {
    const row = Array.from(root(fixture).querySelectorAll<HTMLElement>('.ledger-row')).find(
      (candidate) => candidate.textContent?.includes(payee),
    );
    if (!row) {
      throw new Error(`no row for ${payee}`);
    }
    return row;
  }

  const buttons = (host: HTMLElement, name: RegExp): HTMLButtonElement[] =>
    Array.from(host.querySelectorAll('button')).filter((button) =>
      name.test(button.textContent ?? ''),
    );

  async function openSheet(fixture: ComponentFixture<TransactionsPage>): Promise<HTMLElement> {
    buttonNamed(rowOf(fixture, 'TFR TO SAVINGS'), /This is a transfer/).click();
    await settle(fixture);
    const dialog = root(fixture).querySelector<HTMLElement>('app-pair-dialog');
    if (!dialog) {
      throw new Error('the pairing sheet did not open');
    }
    return dialog;
  }

  // --------------------------------------------------------------------------- who is offered

  it('offers "This is a transfer…" on an ordinary row and "Split into two rows" on a leg', async () => {
    const fixture = await render();

    expect(buttons(rowOf(fixture, 'TFR TO SAVINGS'), /This is a transfer/)).toHaveLength(1);
    expect(buttons(rowOf(fixture, 'TFR TO SAVINGS'), /Split into two rows/)).toHaveLength(0);
    const leg = Array.from(root(fixture).querySelectorAll<HTMLElement>('.ledger-row')).find((row) =>
      row.textContent?.includes('Transfer'),
    )!;
    expect(buttons(leg, /Split into two rows/)).toHaveLength(1);
    expect(buttons(leg, /This is a transfer/)).toHaveLength(0);
  });

  it('offers a viewer neither pairing nor splitting', async () => {
    const fixture = await render('VIEWER');

    expect(buttons(root(fixture), /This is a transfer/)).toHaveLength(0);
    expect(buttons(root(fixture), /Split into two rows/)).toHaveLength(0);
  });

  // ------------------------------------------------------------------------------ the sheet

  it('preselects nothing, and will not pair until the person picks', async () => {
    const fixture = await render();

    const sheet = await openSheet(fixture);

    const radios = Array.from(sheet.querySelectorAll<HTMLInputElement>('input[type="radio"]'));
    expect(radios.length).toBeGreaterThan(0);
    expect(radios.some((radio) => radio.checked)).toBe(false);
    expect(sheet.querySelector<HTMLButtonElement>('[data-testid="pair-confirm"]')?.disabled).toBe(
      true,
    );
    expect(transfers.pair).not.toHaveBeenCalled();
  });

  it('lists only rows that could be the other half: another account, not already a leg', async () => {
    const fixture = await render();

    const sheet = await openSheet(fixture);

    const choices = sheet.querySelector('fieldset')?.textContent ?? '';
    expect(choices).toContain('TFR FROM CURRENT');
    expect(choices).not.toContain('Refund');
    expect(choices).not.toContain('TFR TO SAVINGS');
    expect(sheet.querySelectorAll('input[type="radio"]')).toHaveLength(1);
  });

  it('says both rows lose their category and payee, before the confirm button', async () => {
    const fixture = await render();

    const sheet = await openSheet(fixture);

    const loss = sheet.querySelector('[data-testid="pair-loss"]');
    const confirm = sheet.querySelector('[data-testid="pair-confirm"]');
    expect(loss?.textContent).toContain('lose their category and payee');
    expect(loss?.textContent).toContain('stop counting as income or spending');
    // Earlier in the document than the button, so it is read before the button is reached.
    expect(loss!.compareDocumentPosition(confirm!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("filters with the ledger's account and date filters, and ranks nothing", async () => {
    const fixture = await render();
    const sheet = await openSheet(fixture);
    service.list.mockClear();

    const account = sheet.querySelector<HTMLSelectElement>('#pair-account')!;
    account.value = 'a2';
    account.dispatchEvent(new Event('change'));
    await settle(fixture);

    expect(service.list).toHaveBeenLastCalledWith({
      accountId: 'a2',
      from: undefined,
      to: undefined,
      page: 0,
    });
    // The row's own account is not offered as the other side.
    const offered = Array.from(account.options).map((option) => option.value);
    expect(offered).toEqual(['', 'a2']);
  });

  it('pairs the two rows with both versions as read', async () => {
    const fixture = await render();
    transfers.pair.mockReturnValue(of(PAIRED));
    const sheet = await openSheet(fixture);

    sheet.querySelector<HTMLInputElement>('input[type="radio"]')!.click();
    await settle(fixture);
    buttonNamed(sheet, /Pair these two rows/).click();
    await settle(fixture);

    expect(transfers.pair).toHaveBeenCalledWith('t1', {
      withTransactionId: 't2',
      version: 4,
      withVersion: 7,
    });
    expect(root(fixture).querySelector('app-pair-dialog')).toBeNull();
    expect(root(fixture).textContent).toContain('Paired into a transfer.');
  });

  it('renders a refusal from its code and reason, and keeps the sheet open', async () => {
    const fixture = await render();
    transfers.pair.mockReturnValue(
      throwError(() => apiError(422, 'transfer-pair-mismatch', { reason: 'amounts-differ' })),
    );
    const sheet = await openSheet(fixture);

    sheet.querySelector<HTMLInputElement>('input[type="radio"]')!.click();
    await settle(fixture);
    buttonNamed(sheet, /Pair these two rows/).click();
    await settle(fixture);

    expect(root(fixture).querySelector('app-pair-dialog')?.textContent).toContain(
      "The two amounts aren't equal",
    );
  });

  // ---------------------------------------------------------------------------------- split

  it('asks before splitting, says the rows come back with no category, and sends the version', async () => {
    const fixture = await render();
    transfers.get.mockReturnValue(of(PAIRED));
    transfers.split.mockReturnValue(of({ transactions: [OUT, IN] }));
    const leg = Array.from(root(fixture).querySelectorAll<HTMLElement>('.ledger-row')).find((row) =>
      row.textContent?.includes('Transfer'),
    )!;

    buttonNamed(leg, /Split into two rows/).click();
    await settle(fixture);

    expect(transfers.get).toHaveBeenCalledWith('g9');
    const dialog = root(fixture).querySelector('app-confirm-dialog');
    expect(dialog?.textContent).toContain('no category and no payee');
    expect(transfers.split).not.toHaveBeenCalled();

    buttonNamed(dialog as HTMLElement, /^\s*Split\s*$/).click();
    await settle(fixture);

    expect(transfers.split).toHaveBeenCalledWith('g1', 13);
    expect(root(fixture).textContent).toContain('Split into two rows. Both need a category.');
  });
});
