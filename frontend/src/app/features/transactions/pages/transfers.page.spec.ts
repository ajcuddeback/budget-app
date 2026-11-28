import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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
  settle,
} from '../../../testing/helpers';
import { Account } from '../../accounts/data/account.models';
import { AccountsService } from '../../accounts/data/accounts.service';
import { CategoriesService } from '../../categories/data/categories.service';
import { Category } from '../../categories/data/category.models';
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

/** The reason the second amount field exists at all (ADR-0022, ADR-0033). */
const EUROS: Account = { ...SAVINGS, id: 'a3', name: 'Holiday euros', currency: 'EUR' };

const RETIRED: Account = { ...SAVINGS, id: 'a4', name: 'Old card', archived: true };

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

/**
 * The leg in the account the money left: negative, no payee, no category, and marked.
 *
 * `transferGroupId` is what lets the row act on the pair rather than on the half of it that was
 * clicked. Both legs carry the same one.
 */
const LEG_OUT: Transaction = {
  ...SHOP,
  id: 't2',
  accountId: 'a1',
  accountName: 'Current',
  amount: '-200.0000',
  payeeId: null,
  payeeName: null,
  categoryId: null,
  categoryName: null,
  note: 'to the pot',
  transfer: true,
  transferGroupId: 'g1',
};

/** The other half, in the account it arrived in. Both halves are in the ledger, by design. */
const LEG_IN: Transaction = {
  ...LEG_OUT,
  id: 't3',
  accountId: 'a2',
  accountName: 'Savings',
  amount: '200.0000',
};

const SAME_CURRENCY: Transfer = {
  id: 'g1',
  fromAccountId: 'a1',
  fromAccountName: 'Current',
  toAccountId: 'a2',
  toAccountName: 'Savings',
  fromDate: '2026-11-11',
  toDate: '2026-11-11',
  sent: '200.0000',
  sentCurrency: 'GBP',
  received: '200.0000',
  receivedCurrency: 'GBP',
  crossCurrency: false,
  impliedRate: null,
  fromNote: 'to the pot',
  toNote: 'to the pot',
  createdAt: '2026-11-11T09:00:00Z',
  updatedAt: '2026-11-11T09:00:00Z',
  version: 2,
};

const CROSS_CURRENCY: Transfer = {
  ...SAME_CURRENCY,
  id: 'g2',
  toAccountId: 'a3',
  toAccountName: 'Holiday euros',
  received: '232.4000',
  receivedCurrency: 'EUR',
  crossCurrency: true,
  impliedRate: '1.162000',
};

const ledger = (transactions: readonly Transaction[], overrides: Partial<Ledger> = {}): Ledger => ({
  content: [...transactions],
  pageTotal: null,
  pageTotalCurrency: null,
  page: 0,
  size: 50,
  totalElements: transactions.length,
  totalPages: 1,
  ...overrides,
});

describe('TransactionsPage — transfers', () => {
  let auth: FakeAuth;
  const service = {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    suggestPayees: vi.fn(),
  };
  const transfers = { get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() };
  const accountsService = { list: vi.fn() };
  const categoriesService = { list: vi.fn() };

  async function render(
    options: {
      role?: HouseholdRole;
      transactions?: readonly Transaction[];
      accounts?: readonly Account[];
    } = {},
  ): Promise<ComponentFixture<TransactionsPage>> {
    auth = fakeAuth({
      ...OWNER,
      household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
    });
    for (const fn of [
      ...Object.values(service),
      ...Object.values(transfers),
      accountsService.list,
      categoriesService.list,
    ]) {
      fn.mockReset();
    }

    service.list.mockReturnValue(of(ledger(options.transactions ?? [LEG_OUT, LEG_IN, SHOP])));
    service.suggestPayees.mockReturnValue(of([]));
    const accounts = options.accounts ?? [CURRENT, SAVINGS, EUROS];
    accountsService.list.mockReturnValue(
      of({
        content: [...accounts],
        page: 0,
        size: 200,
        totalElements: accounts.length,
        totalPages: 1,
      }),
    );
    categoriesService.list.mockReturnValue(
      of({ content: [GROCERIES], page: 0, size: 200, totalElements: 1, totalPages: 1 }),
    );

    // Reset first: a few of these render twice, to compare two shapes of the same row.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
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
  const text = (fixture: ComponentFixture<TransactionsPage>): string =>
    (fixture.nativeElement as HTMLElement).textContent ?? '';

  function buttonNamedOrNull(host: HTMLElement, name: RegExp): HTMLButtonElement | null {
    return (
      Array.from(host.querySelectorAll('button')).find((button) =>
        name.test(button.textContent ?? ''),
      ) ?? null
    );
  }

  function rows(fixture: ComponentFixture<TransactionsPage>): HTMLElement[] {
    return Array.from(root(fixture).querySelectorAll<HTMLElement>('.ledger-row'));
  }

  function fill(
    fixture: ComponentFixture<TransactionsPage>,
    selector: string,
    value: string,
  ): void {
    const field = root(fixture).querySelector<HTMLInputElement | HTMLSelectElement>(selector);
    if (!field) {
      throw new Error(`nothing matches ${selector}`);
    }
    field.value = value;
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new Event('change'));
  }

  async function openSheet(fixture: ComponentFixture<TransactionsPage>): Promise<void> {
    buttonNamed(root(fixture), /Move money/).click();
    await settle(fixture);
  }

  // ------------------------------------------------------------------- reaching the sheet

  it('offers Move money on the ledger, next to recording a transaction', async () => {
    const fixture = await render();

    expect(buttonNamedOrNull(root(fixture), /Move money/)).not.toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Record a transaction/)).not.toBeNull();
  });

  it('does not offer Move money when there is only one account to move between', async () => {
    const fixture = await render({ accounts: [CURRENT] });

    // A transfer from an account to itself is two rows that cancel out, and is refused.
    expect(buttonNamedOrNull(root(fixture), /Move money/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Record a transaction/)).not.toBeNull();
  });

  // ------------------------------------------------------------------------- one currency

  it('asks for one figure when both accounts share a currency, and sends no second one', async () => {
    const fixture = await render();
    transfers.create.mockReturnValue(of(SAME_CURRENCY));

    await openSheet(fixture);
    fill(fixture, '#transfer-from', 'a1');
    fill(fixture, '#transfer-to', 'a2');
    await settle(fixture);

    // Two accounts in one currency need one figure: the legs are equal and opposite.
    expect(root(fixture).querySelector('#transfer-amount-received')).toBeNull();

    fill(fixture, '#transfer-amount', '200');
    fill(fixture, '#transfer-date', '2026-11-11');
    fill(fixture, '#transfer-note', 'to the pot');
    await settle(fixture);
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    expect(transfers.create).toHaveBeenCalledWith({
      fromAccountId: 'a1',
      toAccountId: 'a2',
      date: '2026-11-11',
      amount: '200',
      note: 'to the pot',
    });
    // Sending a second figure here is a 400, so the key is absent rather than empty.
    const [body] = transfers.create.mock.lastCall as [Record<string, unknown>];
    expect(Object.hasOwn(body, 'amountReceived')).toBe(false);
    expect(text(fixture)).toContain('Money moved.');
  });

  it('hands the server an amount as a string, from a keypad-friendly text input', async () => {
    const fixture = await render();
    transfers.create.mockReturnValue(of(SAME_CURRENCY));

    await openSheet(fixture);
    const amount = root(fixture).querySelector<HTMLInputElement>('#transfer-amount')!;
    // Deliberately not type="number": that hands back a double (ADR-0006).
    expect(amount.getAttribute('type')).toBe('text');
    expect(amount.getAttribute('inputmode')).toBe('decimal');

    fill(fixture, '#transfer-from', 'a1');
    fill(fixture, '#transfer-to', 'a2');
    fill(fixture, '#transfer-amount', '1200.5000');
    await settle(fixture);
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    const [body] = transfers.create.mock.lastCall as [{ amount: string }];
    expect(body.amount).toBe('1200.5000');
    expect(typeof body.amount).toBe('string');
    // Unsigned: which way it went is the two accounts, not a minus sign.
    expect(body.amount.startsWith('-')).toBe(false);
  });

  // ----------------------------------------------------------------- across two currencies

  it('asks what arrived when the two accounts are in different currencies, and says why', async () => {
    const fixture = await render();
    transfers.create.mockReturnValue(of(CROSS_CURRENCY));

    await openSheet(fixture);
    fill(fixture, '#transfer-from', 'a1');
    fill(fixture, '#transfer-to', 'a3');
    await settle(fixture);

    const received = root(fixture).querySelector<HTMLInputElement>('#transfer-amount-received');
    expect(received).not.toBeNull();
    expect(received!.getAttribute('type')).toBe('text');
    // Explains itself: we are not hiding a conversion we could have done — nobody but the
    // household knows what landed.
    expect(text(fixture)).toContain('different currencies');
    expect(text(fixture)).toContain('EUR');

    fill(fixture, '#transfer-amount', '200');
    fill(fixture, '#transfer-amount-received', '232.40');
    await settle(fixture);
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    expect(transfers.create).toHaveBeenCalledWith(
      expect.objectContaining({ amount: '200', amountReceived: '232.40' }),
    );
    const [body] = transfers.create.mock.lastCall as [{ amountReceived: string }];
    expect(typeof body.amountReceived).toBe('string');
  });

  it('takes the second figure away again when the pair stops being cross-currency', async () => {
    const fixture = await render();
    transfers.create.mockReturnValue(of(SAME_CURRENCY));

    await openSheet(fixture);
    fill(fixture, '#transfer-from', 'a1');
    fill(fixture, '#transfer-to', 'a3');
    fill(fixture, '#transfer-amount', '200');
    await settle(fixture);
    fill(fixture, '#transfer-amount-received', '232.40');
    await settle(fixture);
    expect(root(fixture).querySelector('#transfer-amount-received')).not.toBeNull();

    fill(fixture, '#transfer-to', 'a2');
    await settle(fixture);

    // Gone from the form, not merely hidden: the figure typed against the euro account would be
    // a 400 against a sterling one.
    expect(root(fixture).querySelector('#transfer-amount-received')).toBeNull();
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    const [body] = transfers.create.mock.lastCall as [Record<string, unknown>];
    expect(Object.hasOwn(body, 'amountReceived')).toBe(false);
  });

  it('refuses two figures within one currency before the server has to', async () => {
    const fixture = await render({ accounts: [CURRENT, SAVINGS] });

    await openSheet(fixture);
    fill(fixture, '#transfer-from', 'a1');
    fill(fixture, '#transfer-to', 'a2');
    await settle(fixture);

    // There is no field to type it into at all, which is the only way to be sure of this.
    expect(root(fixture).querySelector('#transfer-amount-received')).toBeNull();
  });

  // ----------------------------------------------------------------------- what is refused

  it('refuses the same account on both sides as it is chosen, not on submit', async () => {
    const fixture = await render();

    await openSheet(fixture);
    fill(fixture, '#transfer-from', 'a1');
    fill(fixture, '#transfer-to', 'a1');
    await settle(fixture);

    expect(text(fixture)).toContain("Money can't move from an account to itself");

    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    // Nothing was sent, so nothing can come back saying no.
    expect(transfers.create).not.toHaveBeenCalled();
  });

  it('does not offer an archived account on either side', async () => {
    const fixture = await render({ accounts: [CURRENT, SAVINGS, RETIRED] });

    await openSheet(fixture);

    for (const picker of ['#transfer-from', '#transfer-to']) {
      const options = Array.from(
        root(fixture).querySelectorAll<HTMLOptionElement>(`${picker} option`),
      ).map((option) => option.textContent ?? '');
      // Either side being archived is a 409, so it is not offered rather than refused.
      expect(options.some((label) => label.includes('Current'))).toBe(true);
      expect(options.some((label) => label.includes('Old card'))).toBe(false);
    }
  });

  it('has no category picker and no payee field anywhere on the sheet', async () => {
    const fixture = await render();

    await openSheet(fixture);
    const dialog = root(fixture).querySelector<HTMLElement>('dialog')!;

    // A transfer is not spending, and the API refuses a category rather than ignoring one.
    expect(dialog.textContent).not.toContain('Groceries');
    expect(dialog.querySelector('#transfer-category')).toBeNull();
    expect(dialog.querySelector('#transfer-payee')).toBeNull();
    expect(dialog.textContent).toContain('no category');
  });

  // --------------------------------------------------------------- the row in the ledger

  it('reads a transfer row as a transfer rather than as a payee-less spend', async () => {
    const fixture = await render();
    const [out, into] = rows(fixture);

    expect(out.textContent).toContain('Transfer out');
    expect(into.textContent).toContain('Transfer in');
    expect(out.textContent).toContain('Between your accounts');
    // "Needs a category" would be an unanswerable task: a transfer can never have one.
    expect(out.querySelector('.needs-category')).toBeNull();
    expect(out.textContent).not.toContain('No payee');
    // Both halves are in the ledger, each in its own account: a balance that moved for no
    // visible reason is a bug report.
    expect(out.textContent).toContain('Current');
    expect(into.textContent).toContain('Savings');
  });

  it('edits the transfer from either row, never the leg', async () => {
    const fixture = await render();
    transfers.get.mockReturnValue(of(SAME_CURRENCY));
    transfers.update.mockReturnValue(of({ ...SAME_CURRENCY, sent: '250.0000', version: 4 }));

    buttonNamed(rows(fixture)[1], /Edit/).click();
    await settle(fixture);

    // The pair is fetched by its group id — the row's own id names a leg, which cannot be edited.
    expect(transfers.get).toHaveBeenCalledWith('g1');

    fill(fixture, '#transfer-amount', '250');
    await settle(fixture);
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    expect(transfers.update).toHaveBeenCalledWith('g1', { version: 2, amount: '250' });
    // Patching a leg directly is a 409 transfer-leg-not-separate.
    expect(service.update).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('Transfer saved.');
  });

  it('offers no account pickers on an edit, and says what to do instead', async () => {
    const fixture = await render();
    transfers.get.mockReturnValue(of(SAME_CURRENCY));

    buttonNamed(rows(fixture)[0], /Edit/).click();
    await settle(fixture);

    const dialog = root(fixture).querySelector<HTMLElement>('dialog')!;
    expect(dialog.querySelector('#transfer-from')).toBeNull();
    expect(dialog.querySelector('#transfer-to')).toBeNull();
    // Named, so it is clear which transfer this is, and the way out is written down rather than
    // left to somebody hunting for a picker that is not there.
    expect(dialog.textContent).toContain('Current');
    expect(dialog.textContent).toContain('Savings');
    expect(dialog.textContent).toContain('delete it and make it again');
    // The things an edit does offer.
    expect(dialog.querySelector('#transfer-amount')).not.toBeNull();
    expect(dialog.querySelector('#transfer-date')).not.toBeNull();
    expect(dialog.querySelector('#transfer-note')).not.toBeNull();
  });

  it('shows an implied rate beside a cross-currency transfer, labelled as derived', async () => {
    const fixture = await render();
    transfers.get.mockReturnValue(of(CROSS_CURRENCY));

    buttonNamed(rows(fixture)[0], /Edit/).click();
    await settle(fixture);

    const dialog = root(fixture).querySelector<HTMLElement>('dialog')!;
    expect(dialog.textContent).toContain('1.162');
    // Never "the rate you got": the bank's rate had its fee inside it (ADR-0022).
    expect(dialog.textContent).toContain("isn't the rate your bank gave you");
    // Both real figures are still what is being edited.
    expect(dialog.querySelector<HTMLInputElement>('#transfer-amount')!.value).toBe('200.0000');
    expect(dialog.querySelector<HTMLInputElement>('#transfer-amount-received')!.value).toBe(
      '232.4000',
    );
  });

  it('sends only what changed when correcting a cross-currency transfer', async () => {
    const fixture = await render();
    transfers.get.mockReturnValue(of(CROSS_CURRENCY));
    transfers.update.mockReturnValue(of({ ...CROSS_CURRENCY, received: '230.0000', version: 4 }));

    buttonNamed(rows(fixture)[0], /Edit/).click();
    await settle(fixture);
    fill(fixture, '#transfer-amount-received', '230.00');
    await settle(fixture);
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    // What left did not change, so it is not restated: an absent field means "leave that leg
    // alone", and the receiving leg is the only one being corrected.
    expect(transfers.update).toHaveBeenCalledWith('g2', { version: 2, amountReceived: '230.00' });
  });

  it('deletes both halves, and says so before doing it', async () => {
    const fixture = await render();
    transfers.remove.mockReturnValue(of(undefined));

    buttonNamed(rows(fixture)[0], /Delete/).click();
    await settle(fixture);

    const confirmation = root(fixture).querySelector('dialog');
    expect(confirmation?.textContent).toContain('Both halves go');

    root(fixture).querySelector<HTMLButtonElement>('dialog .btn-danger')!.click();
    await settle(fixture);

    expect(transfers.remove).toHaveBeenCalledWith('g1');
    // Deleting a leg on its own is refused, and would leave money nowhere if it were not.
    expect(service.remove).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('Transfer deleted.');
  });

  it('refreshes the ledger and the balances after money moves', async () => {
    const fixture = await render();
    transfers.remove.mockReturnValue(of(undefined));
    const listedBefore = accountsService.list.mock.calls.length;

    buttonNamed(rows(fixture)[0], /Delete/).click();
    await settle(fixture);
    root(fixture).querySelector<HTMLButtonElement>('dialog .btn-danger')!.click();
    await settle(fixture);

    // Two balances moved, not one, so the account list this page shows names from is re-read.
    expect(accountsService.list.mock.calls.length).toBeGreaterThan(listedBefore);
    expect(service.list.mock.calls.length).toBeGreaterThan(1);
  });

  it('offers nothing on a transfer row that arrives without its pair named', async () => {
    // The server sends `transferGroupId` on both legs, so this is a response this client should
    // not act on the word of. There is nothing honest to offer: a guessed group id would delete a
    // transfer chosen by guesswork, and a leg cannot be written to alone.
    const unnamed: Transaction = { ...LEG_OUT, transferGroupId: null };
    const fixture = await render({ transactions: [unnamed, SHOP] });
    const transferRow = rows(fixture)[0];

    expect(buttonNamedOrNull(transferRow, /Edit/)).toBeNull();
    expect(buttonNamedOrNull(transferRow, /Delete/)).toBeNull();
    expect(transferRow.textContent).toContain('Both halves of a transfer change together');
    // An ordinary row is untouched by any of this.
    expect(buttonNamedOrNull(rows(fixture)[1], /Edit/)).not.toBeNull();
  });

  // ------------------------------------------------------------------------- when it fails

  it('keeps the sheet and the ledger when the server refuses a transfer', async () => {
    const fixture = await render();
    transfers.create.mockReturnValue(throwError(() => apiError(409, 'account-archived')));

    await openSheet(fixture);
    fill(fixture, '#transfer-from', 'a1');
    fill(fixture, '#transfer-to', 'a2');
    fill(fixture, '#transfer-amount', '200');
    await settle(fixture);
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    // The sentence comes from the code, never from the response body (ADR-0023), and what was
    // typed is still on screen to correct.
    expect(text(fixture)).toContain('archived');
    expect(root(fixture).querySelector<HTMLInputElement>('#transfer-amount')!.value).toBe('200');
    expect(text(fixture)).toContain('Tesco');
  });

  it('names the field the server rejected, for the figure only a statement knows', async () => {
    const fixture = await render();
    transfers.create.mockReturnValue(
      throwError(() => apiError(400, 'validation-failed', {}, ['amountReceived'])),
    );

    await openSheet(fixture);
    fill(fixture, '#transfer-from', 'a1');
    fill(fixture, '#transfer-to', 'a3');
    fill(fixture, '#transfer-amount', '200');
    await settle(fixture);
    fill(fixture, '#transfer-amount-received', '232.40');
    await settle(fixture);
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    expect(root(fixture).querySelector('#transfer-received-error')?.textContent?.trim()).not.toBe(
      '',
    );
  });

  it('says nothing to the server when nothing was changed', async () => {
    const fixture = await render();
    transfers.get.mockReturnValue(of(SAME_CURRENCY));

    buttonNamed(rows(fixture)[0], /Edit/).click();
    await settle(fixture);
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    // A patch would bump the pair's version for nothing, and make somebody else's edit stale.
    expect(transfers.update).not.toHaveBeenCalled();
    expect(root(fixture).querySelector('dialog')).toBeNull();
  });

  // --------------------------------------------------------------------------------- roles

  it('offers a viewer the transfer and no write control at all', async () => {
    const fixture = await render({ role: 'VIEWER' });

    // Both halves, both figures, read-only.
    expect(rows(fixture)).toHaveLength(3);
    expect(text(fixture)).toContain('Transfer out');
    expect(text(fixture)).toContain('£200.00');

    expect(buttonNamedOrNull(root(fixture), /Move money/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Record a transaction/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Edit/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Delete/)).toBeNull();
    expect(root(fixture).querySelectorAll('.row-actions')).toHaveLength(0);
  });
});
