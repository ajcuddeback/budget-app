import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HouseholdRole } from '../../../core/auth.models';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  OWNER,
  OWNER_MEMBERSHIP,
  apiError,
  buttonNamed,
  FakeAuth,
  fakeAuth,
  settle,
} from '../../../testing/helpers';
import { Account } from '../../accounts/data/account.models';
import { AccountsService } from '../../accounts/data/accounts.service';
import { CategoriesService } from '../../categories/data/categories.service';
import { Category } from '../../categories/data/category.models';
import { Ledger, Transaction } from '../data/transaction.models';
import { TransactionsService } from '../data/transactions.service';
import { TransactionsPage } from './transactions.page';

const CURRENT: Account = {
  id: 'a1',
  name: 'Current',
  lastFour: '4412',
  type: 'CHECKING',
  currency: 'GBP',
  openingBalance: '100.0000',
  balance: '57.5000',
  archived: false,
  createdAt: '2026-10-01T09:00:00Z',
  updatedAt: '2026-10-11T09:00:00Z',
  version: 1,
  empty: false,
};

const RETIRED: Account = { ...CURRENT, id: 'a2', name: 'Old card', archived: true };

const GROCERIES: Category = {
  id: 'c3',
  name: 'Groceries',
  kind: 'EXPENSE',
  parentId: null,
  parentName: null,
  archived: false,
  createdAt: '2026-11-01T09:00:00Z',
  updatedAt: '2026-11-01T09:00:00Z',
  version: 0,
};

const SHOP: Transaction = {
  id: 't1',
  accountId: 'a1',
  accountName: 'Current',
  date: '2026-11-11',
  amount: '-42.5000',
  currency: 'GBP',
  payeeId: 'p1',
  payeeName: 'Tesco',
  categoryId: 'c3',
  categoryName: 'Groceries',
  categoryParentName: null,
  note: 'weekly shop',
  status: 'CLEARED',
  transfer: false,
  transferGroupId: null,
  createdAt: '2026-11-11T09:00:00Z',
  updatedAt: '2026-11-11T09:00:00Z',
  version: 2,
};

const UNFILED: Transaction = {
  ...SHOP,
  id: 't2',
  date: '2026-11-12',
  amount: '-9.9900',
  payeeId: null,
  payeeName: null,
  categoryId: null,
  categoryName: null,
  note: null,
};

const ledger = (transactions: readonly Transaction[], overrides: Partial<Ledger> = {}): Ledger => ({
  content: [...transactions],
  pageTotal: '-52.4900',
  pageTotalCurrency: 'GBP',
  page: 0,
  size: 50,
  totalElements: transactions.length,
  totalPages: 1,
  ...overrides,
});

describe('TransactionsPage', () => {
  let auth: FakeAuth;
  const service = {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    suggestPayees: vi.fn(),
  };
  const accountsService = { list: vi.fn() };
  const categoriesService = { list: vi.fn() };

  async function render(
    options: {
      role?: HouseholdRole;
      transactions?: readonly Transaction[];
      accounts?: readonly Account[];
      ledgerOverrides?: Partial<Ledger>;
      /** The query string the page was opened with, for a link that arrives pre-filtered. */
      query?: Record<string, string>;
    } = {},
  ): Promise<ComponentFixture<TransactionsPage>> {
    auth = fakeAuth({
      ...OWNER,
      household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
    });
    for (const fn of Object.values(service)) fn.mockReset();
    accountsService.list.mockReset();
    categoriesService.list.mockReset();

    service.list.mockReturnValue(
      of(ledger(options.transactions ?? [UNFILED, SHOP], options.ledgerOverrides)),
    );
    service.suggestPayees.mockReturnValue(of([]));
    accountsService.list.mockReturnValue(
      of({
        content: [...(options.accounts ?? [CURRENT])],
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
      }),
    );
    categoriesService.list.mockReturnValue(
      of({ content: [GROCERIES], page: 0, size: 200, totalElements: 1, totalPages: 1 }),
    );

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: TransactionsService, useValue: service },
        { provide: AccountsService, useValue: accountsService },
        { provide: CategoriesService, useValue: categoriesService },
        ...(options.query
          ? [
              {
                provide: ActivatedRoute,
                useValue: { snapshot: { queryParamMap: convertToParamMap(options.query) } },
              },
            ]
          : []),
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(TransactionsPage);
    await settle(fixture);
    return fixture;
  }

  const text = (fixture: ComponentFixture<TransactionsPage>): string =>
    (fixture.nativeElement as HTMLElement).textContent ?? '';
  const root = (fixture: ComponentFixture<TransactionsPage>): HTMLElement => fixture.nativeElement;

  it('lists the ledger with payee, amount and category', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain('Tesco');
    expect(text(fixture)).toContain('Groceries');
    expect(text(fixture)).toContain('weekly shop');
    expect(text(fixture)).toContain('£42.50');
  });

  it('says a transaction needs a category rather than leaving a blank', async () => {
    const fixture = await render();

    // The thing a person is meant to act on, not an absence to skim past.
    expect(root(fixture).querySelector('.needs-category')?.textContent).toContain(
      'Needs a category',
    );
  });

  it('shows the page total the server sent and never computes one', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain('£52.49');
  });

  it('says a page mixes currencies instead of converting (ADR-0022)', async () => {
    const fixture = await render({
      ledgerOverrides: { pageTotal: null, pageTotalCurrency: null },
    });

    expect(text(fixture)).toContain('mixes currencies');
  });

  it('asks the server for only uncategorised transactions when that filter is pressed', async () => {
    const fixture = await render();

    buttonNamed(root(fixture), /Needs a category/).click();
    await settle(fixture);

    expect(service.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ uncategorised: true, page: 0 }),
    );
  });

  it('opens on the "needs a category" queue when the link that brought it asked for it', async () => {
    // Insights' "Give them a category" is /transactions?filter=uncategorised.
    const fixture = await render({ query: { filter: 'uncategorised' } });

    expect(service.list).toHaveBeenCalledWith(expect.objectContaining({ uncategorised: true }));
    expect(buttonNamed(root(fixture), /Needs a category/).getAttribute('aria-pressed')).toBe(
      'true',
    );
  });

  it('clears a chosen category when "needs a category" is pressed, since both is a contradiction', async () => {
    const fixture = await render();
    const select = root(fixture).querySelector<HTMLSelectElement>('#filter-category')!;

    select.value = 'c3';
    select.dispatchEvent(new Event('change'));
    await settle(fixture);
    expect(service.list).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: 'c3' }));

    buttonNamed(root(fixture), /Needs a category/).click();
    await settle(fixture);

    const query = service.list.mock.lastCall?.[0];
    expect(query.uncategorised).toBe(true);
    expect(query.categoryId).toBeUndefined();
  });

  it('goes back to the first page whenever a filter changes', async () => {
    const fixture = await render({ ledgerOverrides: { totalPages: 3, totalElements: 120 } });

    buttonNamed(root(fixture), /Next/).click();
    await settle(fixture);
    expect(service.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }));

    const search = root(fixture).querySelector<HTMLInputElement>('#filter-search')!;
    search.value = 'lidl';
    search.dispatchEvent(new Event('change'));
    await settle(fixture);

    // Page 3 of a different query is not a page at all.
    expect(service.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ search: 'lidl', page: 0 }),
    );
  });

  it('does not offer an archived account for a new transaction', async () => {
    const fixture = await render({ accounts: [CURRENT, RETIRED] });

    buttonNamed(root(fixture), /Record a transaction/).click();
    await settle(fixture);

    const options = Array.from(
      root(fixture).querySelectorAll<HTMLOptionElement>('#transaction-account option'),
    ).map((option) => option.textContent?.trim());
    expect(options.some((label) => label?.includes('Current'))).toBe(true);
    expect(options.some((label) => label?.includes('Old card'))).toBe(false);
  });

  it('tells somebody to add an account before offering an entry form', async () => {
    const fixture = await render({ accounts: [] });

    expect(text(fixture)).toContain('has to go somewhere');
  });

  it('offers a viewer no write buttons', async () => {
    const fixture = await render({ role: 'VIEWER' });

    expect(text(fixture)).not.toContain('Record a transaction');
    expect(root(fixture).querySelectorAll('.row-actions').length).toBe(0);
  });

  it('keeps the list and explains itself when a delete is refused', async () => {
    const fixture = await render();
    service.remove.mockReturnValue(throwError(() => apiError(409, 'conflict')));

    buttonNamed(root(fixture), /Delete/).click();
    await settle(fixture);
    // The confirmation's own button, found by role in the dialog rather than by its label: the
    // row's button and the dialog's both say "Delete".
    root(fixture).querySelector<HTMLButtonElement>('dialog .btn-danger')!.click();
    await settle(fixture);

    // A refused write must not empty a screen full of money.
    expect(text(fixture)).toContain('Tesco');
  });

  it('says nothing matches when a filter is set, and nothing is recorded when none is', async () => {
    const empty = await render({ transactions: [], ledgerOverrides: { totalElements: 0 } });
    expect(text(empty)).toContain('Nothing recorded yet');

    const search = root(empty).querySelector<HTMLInputElement>('#filter-search')!;
    search.value = 'nothing like this';
    search.dispatchEvent(new Event('change'));
    await settle(empty);

    expect(text(empty)).toContain('Nothing matches');
  });

  it('records a transaction with the sign taken from the direction, not from typing', async () => {
    const fixture = await render();
    service.create.mockReturnValue(of(SHOP));

    buttonNamed(root(fixture), /Record a transaction/).click();
    await settle(fixture);

    const host = root(fixture);
    const amount = host.querySelector<HTMLInputElement>('#transaction-amount')!;
    amount.value = '42.50';
    amount.dispatchEvent(new Event('input'));
    const date = host.querySelector<HTMLInputElement>('#transaction-date')!;
    date.value = '2026-11-11';
    date.dispatchEvent(new Event('input'));
    await settle(fixture);

    buttonNamed(host, /Save/).click();
    await settle(fixture);

    expect(service.create).toHaveBeenCalledWith(
      expect.objectContaining({ accountId: 'a1', amount: '-42.50' }),
    );
  });

  it('closes the transaction dialog when it is dismissed, not only when it is saved', async () => {
    const fixture = await render();
    buttonNamed(root(fixture), /Record a transaction/).click();
    await settle(fixture);
    expect(root(fixture).querySelector('dialog')).not.toBeNull();

    // The native event is `close` and the directive emits `dismissed`; the template listened for
    // `closed`, which nothing emits — so pressing Escape left the page holding a dialog it
    // believed was open, and the next Record a transaction opened nothing.
    root(fixture).querySelector('dialog')!.dispatchEvent(new Event('close'));
    await settle(fixture);

    expect(root(fixture).querySelector('dialog')).toBeNull();
  });
});
