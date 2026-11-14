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
  FakeAuth,
  settle,
  type as typeInto,
} from '../../../testing/helpers';
import { Account } from '../data/account.models';
import { AccountsService } from '../data/accounts.service';
import { AccountsPage } from './accounts.page';

const CHASE: Account = {
  id: 'a1',
  name: 'Chase',
  lastFour: '4412',
  type: 'CREDIT_CARD',
  currency: 'GBP',
  openingBalance: '-400.0000',
  balance: '-425.5000',
  archived: false,
  createdAt: '2026-10-01T09:00:00Z',
  updatedAt: '2026-10-11T09:00:00Z',
  version: 3,
};

const SAVINGS: Account = {
  ...CHASE,
  id: 'a2',
  name: 'Savings',
  lastFour: null,
  type: 'SAVINGS',
  openingBalance: '1000.0000',
  balance: '1000.0000',
  version: 0,
};

const BERLIN: Account = {
  ...SAVINGS,
  id: 'a3',
  name: 'Berlin account',
  currency: 'EUR',
  openingBalance: '250.5000',
  balance: '250.5000',
};

const CLOSED: Account = {
  ...SAVINGS,
  id: 'a4',
  name: 'Old current account',
  type: 'CHECKING',
  balance: '0.0000',
  openingBalance: '0.0000',
  archived: true,
};

function page(accounts: readonly Account[], totalElements = accounts.length) {
  return of({
    content: [...accounts],
    page: 0,
    size: 200,
    totalElements,
    totalPages: 1,
  });
}

describe('AccountsPage', () => {
  let auth: FakeAuth;
  const service = {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    archive: vi.fn(),
    unarchive: vi.fn(),
    remove: vi.fn(),
  };

  async function render(
    options: { role?: HouseholdRole; accounts?: readonly Account[]; totalElements?: number } = {},
  ): Promise<ComponentFixture<AccountsPage>> {
    const accounts = options.accounts ?? [CHASE, SAVINGS];
    auth = fakeAuth({
      ...OWNER,
      household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
    });
    for (const fn of Object.values(service)) fn.mockReset();
    service.list.mockReturnValue(page(accounts, options.totalElements ?? accounts.length));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: AccountsService, useValue: service },
      ],
    });
    // A fixed locale, so an assertion about "£574.50" is about the formatting and not about
    // whatever locale the test machine happens to have.
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(AccountsPage);
    await settle(fixture);
    return fixture;
  }

  const text = (fixture: ComponentFixture<AccountsPage>): string =>
    (fixture.nativeElement as HTMLElement).textContent ?? '';
  const root = (fixture: ComponentFixture<AccountsPage>): HTMLElement => fixture.nativeElement;
  const dialog = (fixture: ComponentFixture<AccountsPage>): HTMLElement => {
    const found = root(fixture).querySelector<HTMLElement>('dialog');
    if (!found) {
      throw new Error('no dialog is open');
    }
    return found;
  };
  const rows = (fixture: ComponentFixture<AccountsPage>): HTMLElement[] =>
    Array.from(root(fixture).querySelectorAll('.row'));

  function select(host: HTMLElement, selector: string, value: string): void {
    const element = host.querySelector<HTMLSelectElement>(selector);
    if (!element) {
      throw new Error(`nothing matches ${selector}`);
    }
    element.value = value;
    element.dispatchEvent(new Event('change', { bubbles: true }));
  }

  it('composes the name and the digits, and says what each account is', async () => {
    const fixture = await render();

    expect(rows(fixture)).toHaveLength(2);
    expect(rows(fixture)[0].textContent).toContain('Chase •4412');
    expect(rows(fixture)[0].textContent).toContain('Credit card');
    expect(rows(fixture)[0].textContent).toContain('-£425.50');
    // No digits, no bullet — and nothing from the server was pre-joined (ADR-0023).
    expect(rows(fixture)[1].textContent).toContain('Savings');
    expect(rows(fixture)[1].textContent).not.toContain('•');
  });

  it('shows the balance the server computed, not the opening balance it was given', async () => {
    const fixture = await render({ accounts: [CHASE] });

    // They are different fields and will stop being equal the moment transactions land.
    expect(text(fixture)).toContain('-£425.50');
    expect(text(fixture)).not.toContain('-£400.00');
  });

  it('groups by currency and subtotals each group, and never totals across them', async () => {
    const fixture = await render({ accounts: [CHASE, SAVINGS, BERLIN] });
    const body = text(fixture);

    expect(body).toContain('British Pound (GBP)');
    expect(body).toContain('Euro (EUR)');
    expect(root(fixture).querySelectorAll('.group-subtotal')).toHaveLength(2);
    expect(body).toContain('£574.50'); // -425.50 + 1000.00, exactly
    expect(body).toContain('€250.50');
    // The sum across currencies is 825.00 in no currency at all. It must appear nowhere, in
    // either currency's symbol, and there must be no row claiming to be a household total.
    expect(body).not.toContain('825');
    expect(body.match(/Subtotal/g)).toHaveLength(2);
    expect(body).not.toContain('Total');
  });

  it('shows no subtotal at all when there is only one currency to keep apart', async () => {
    const fixture = await render({ accounts: [CHASE, SAVINGS] });

    // One currency means a subtotal would be a household total, which is an open question in the
    // feature doc rather than something to invent here.
    expect(text(fixture)).not.toContain('Subtotal');
    expect(root(fixture).querySelectorAll('.group-subtotal')).toHaveLength(0);
  });

  it('keeps every digit of an amount a double would have rounded', async () => {
    const huge: Account = {
      ...SAVINGS,
      openingBalance: '12345678901234.5678',
      balance: '12345678901234.5678',
    };
    const fixture = await render({ accounts: [huge] });

    expect(text(fixture)).toContain('£12,345,678,901,234.57');
    buttonNamed(root(fixture), 'Edit').click();
    await settle(fixture);

    // The form holds the string the server sent, unparsed and unrounded.
    expect(dialog(fixture).querySelector<HTMLInputElement>('#account-opening-balance')?.value).toBe(
      '12345678901234.5678',
    );
  });

  it('greets a first run with one line and one thing to do', async () => {
    const fixture = await render({ accounts: [] });

    expect(text(fixture)).toContain('An account is a place money sits');
    expect(root(fixture).querySelector('[role=alert]')).toBeNull();
    expect(buttonNamed(root(fixture), 'Add your first account')).toBeTruthy();
    // One primary action, not two.
    expect(root(fixture).querySelectorAll('.btn-primary')).toHaveLength(1);
  });

  it('offers a viewer nothing to press on an empty instance', async () => {
    const empty = await render({ role: 'VIEWER', accounts: [] });

    expect(text(empty)).toContain('read-only');
    expect(root(empty).querySelectorAll('button')).toHaveLength(0);
  });

  it('shows a viewer the figures and no way to change them', async () => {
    const full = await render({ role: 'VIEWER', accounts: [CHASE, SAVINGS, CLOSED] });
    expect(rows(full)).toHaveLength(2);
    expect(text(full)).toContain('-£425.50');
    for (const label of ['Add an account', 'Edit', 'Archive', 'Delete', 'Bring back']) {
      expect(() => buttonNamed(root(full), label)).toThrow();
    }
    // The one button a viewer does get is the archived toggle, which only reveals rows.
    expect(root(full).querySelectorAll('button')).toHaveLength(1);
  });

  it('says so when the server is holding more accounts than it sent', async () => {
    const fixture = await render({ accounts: [CHASE, SAVINGS], totalElements: 205 });

    expect(text(fixture)).toContain('203 more accounts are not shown');
  });

  it('counts the last one in words that read properly', async () => {
    const fixture = await render({ accounts: [CHASE, SAVINGS], totalElements: 3 });

    expect(text(fixture)).toContain('1 more account is not shown');
  });

  it('adds an account, sending every amount as a string', async () => {
    const fixture = await render({ accounts: [] });
    const created: Account = { ...SAVINGS, id: 'new', name: 'Tin on the shelf', type: 'CASH' };
    service.create.mockReturnValue(of(created));

    buttonNamed(root(fixture), 'Add your first account').click();
    await settle(fixture);
    // The household's base currency is the default, never a conversion.
    expect(dialog(fixture).querySelector<HTMLSelectElement>('#account-currency')?.value).toBe(
      'GBP',
    );

    typeInto(dialog(fixture), '#account-name', 'Tin on the shelf');
    typeInto(dialog(fixture), '#account-opening-balance', '-0.5000');
    select(dialog(fixture), '#account-type', 'CASH');
    await settle(fixture);
    buttonNamed(dialog(fixture), 'Save account').click();
    await settle(fixture);

    expect(service.create).toHaveBeenCalledWith({
      name: 'Tin on the shelf',
      type: 'CASH',
      currency: 'GBP',
      openingBalance: '-0.5000',
    });
    const [[request]] = service.create.mock.calls;
    expect(typeof request.openingBalance).toBe('string');
    expect(request).not.toHaveProperty('lastFour');
    expect(text(fixture)).toContain('Account added.');
    expect(root(fixture).querySelector('dialog')).toBeNull();
  });

  it('keeps the digits when only the name is corrected', async () => {
    const fixture = await render({ accounts: [CHASE] });
    service.update.mockReturnValue(of({ ...CHASE, name: 'Chase Visa', version: 4 }));

    buttonNamed(root(fixture), 'Edit').click();
    await settle(fixture);
    typeInto(dialog(fixture), '#account-name', 'Chase Visa');
    buttonNamed(dialog(fixture), 'Save account').click();
    await settle(fixture);

    expect(service.update).toHaveBeenCalledWith('a1', { version: 3, name: 'Chase Visa' });
    const [[, patch]] = service.update.mock.calls;
    expect('lastFour' in patch).toBe(false);
    expect(text(fixture)).toContain('Chase Visa •4412');
  });

  it('clears the digits with an explicit null when the box is emptied', async () => {
    const fixture = await render({ accounts: [CHASE] });
    service.update.mockReturnValue(of({ ...CHASE, lastFour: null, version: 4 }));

    buttonNamed(root(fixture), 'Edit').click();
    await settle(fixture);
    typeInto(dialog(fixture), '#account-last-four', '');
    buttonNamed(dialog(fixture), 'Save account').click();
    await settle(fixture);

    expect(service.update).toHaveBeenCalledWith('a1', { version: 3, lastFour: null });
    expect(text(fixture)).not.toContain('•4412');
  });

  it('re-reads and says what changed when somebody else saved first', async () => {
    const fixture = await render({ accounts: [CHASE] });
    const theirs: Account = { ...CHASE, name: 'Chase Visa', lastFour: '9999', version: 4 };
    service.update.mockReturnValue(throwError(() => apiError(409, 'stale-version')));
    service.get.mockReturnValue(of(theirs));

    buttonNamed(root(fixture), 'Edit').click();
    await settle(fixture);
    typeInto(dialog(fixture), '#account-name', 'My own name');
    buttonNamed(dialog(fixture), 'Save account').click();
    await settle(fixture);

    expect(service.get).toHaveBeenCalledWith('a1');
    const shown = dialog(fixture).textContent ?? '';
    expect(shown).toContain('Somebody else saved first');
    expect(shown).toContain('Name: Chase Visa');
    expect(shown).toContain('Last four digits: 9999');
    expect(shown).not.toContain('Type:');
    // What they typed is still there, and nothing has been written.
    expect(dialog(fixture).querySelector<HTMLInputElement>('#account-name')?.value).toBe(
      'My own name',
    );
    expect(service.update).toHaveBeenCalledTimes(1);
    expect(dialog(fixture).querySelector<HTMLButtonElement>('button[type=submit]')?.disabled).toBe(
      true,
    );
    // The list behind the dialog already shows the new truth.
    expect(text(fixture)).toContain('Chase Visa •9999');
  });

  it('lets the loser of a conflict take the latest copy instead', async () => {
    const fixture = await render({ accounts: [CHASE] });
    service.update.mockReturnValue(throwError(() => apiError(409, 'stale-version')));
    service.get.mockReturnValue(of({ ...CHASE, name: 'Chase Visa', version: 4 }));

    buttonNamed(root(fixture), 'Edit').click();
    await settle(fixture);
    typeInto(dialog(fixture), '#account-name', 'My own name');
    buttonNamed(dialog(fixture), 'Save account').click();
    await settle(fixture);
    buttonNamed(dialog(fixture), 'Use theirs').click();
    await settle(fixture);

    expect(dialog(fixture).querySelector<HTMLInputElement>('#account-name')?.value).toBe(
      'Chase Visa',
    );
    expect(dialog(fixture).textContent).not.toContain('Somebody else saved first');
  });

  it('only overwrites a conflict on the version it has shown the person', async () => {
    const fixture = await render({ accounts: [CHASE] });
    service.update.mockReturnValueOnce(throwError(() => apiError(409, 'stale-version')));
    service.get.mockReturnValue(of({ ...CHASE, name: 'Chase Visa', version: 4 }));

    buttonNamed(root(fixture), 'Edit').click();
    await settle(fixture);
    typeInto(dialog(fixture), '#account-name', 'My own name');
    buttonNamed(dialog(fixture), 'Save account').click();
    await settle(fixture);

    service.update.mockReturnValue(of({ ...CHASE, name: 'My own name', version: 5 }));
    buttonNamed(dialog(fixture), 'Keep mine and save').click();
    await settle(fixture);

    expect(service.update).toHaveBeenLastCalledWith('a1', { version: 4, name: 'My own name' });
    expect(text(fixture)).toContain('Account saved.');
  });

  it('explains a refused edit without throwing away what was typed', async () => {
    const fixture = await render({ accounts: [CHASE] });
    service.update.mockReturnValue(throwError(() => apiError(409, 'account-currency-immutable')));

    buttonNamed(root(fixture), 'Edit').click();
    await settle(fixture);
    select(dialog(fixture), '#account-currency', 'EUR');
    await settle(fixture);
    buttonNamed(dialog(fixture), 'Save account').click();
    await settle(fixture);

    expect(dialog(fixture).querySelector('[role=alert]')?.textContent).toContain(
      'currency cannot be changed',
    );
    expect(dialog(fixture).querySelector<HTMLSelectElement>('#account-currency')?.value).toBe(
      'EUR',
    );
  });

  it('marks the field the server named when it refuses one this form allowed', async () => {
    const fixture = await render({ accounts: [CHASE] });
    service.update.mockReturnValue(
      throwError(() => apiError(400, 'validation-failed', { field: 'currency' }, ['currency'])),
    );

    buttonNamed(root(fixture), 'Edit').click();
    await settle(fixture);
    typeInto(dialog(fixture), '#account-name', 'Chase Visa');
    buttonNamed(dialog(fixture), 'Save account').click();
    await settle(fixture);

    expect(dialog(fixture).querySelector('#account-currency-error')?.textContent).toContain(
      'This value was not accepted',
    );
  });

  it('disables a locked currency and leaves the reason beside it', async () => {
    const fixture = await render({ accounts: [{ ...CHASE, empty: false }] });

    buttonNamed(root(fixture), 'Edit').click();
    await settle(fixture);
    const currency = dialog(fixture).querySelector<HTMLSelectElement>('#account-currency');

    expect(currency?.disabled).toBe(true);
    // Disabled, not vanished: the reason is in the flow and tied to the control.
    expect(dialog(fixture).textContent).toContain('cannot be changed once an account has');
    expect(currency?.getAttribute('aria-describedby')).toContain('account-currency-hint');
  });

  it('asks before archiving, in words that say what archiving does', async () => {
    const fixture = await render({ accounts: [CHASE, SAVINGS] });
    service.archive.mockReturnValue(of({ ...CHASE, archived: true, version: 4 }));

    buttonNamed(rows(fixture)[0], 'Archive').click();
    await settle(fixture);
    expect(dialog(fixture).textContent).toContain('Archive Chase?');
    expect(dialog(fixture).textContent).toContain('keeps its history, hides it from lists');
    expect(service.archive).not.toHaveBeenCalled();

    buttonNamed(dialog(fixture), 'Archive').click();
    await settle(fixture);

    expect(service.archive).toHaveBeenCalledWith('a1');
    expect(text(fixture)).toContain('That account has been archived.');
    // Gone from the list it was in, and behind a toggle rather than lost.
    expect(rows(fixture)).toHaveLength(1);
    expect(text(fixture)).toContain('Show 1 archived');
  });

  it('reveals archived accounts with their balances, and brings one back', async () => {
    const fixture = await render({ accounts: [CHASE, CLOSED] });
    service.unarchive.mockReturnValue(of({ ...CLOSED, archived: false, version: 1 }));

    expect(text(fixture)).not.toContain('Old current account');
    buttonNamed(root(fixture), 'Show 1 archived').click();
    await settle(fixture);

    expect(text(fixture)).toContain('Old current account');
    expect(text(fixture)).toContain('£0.00');

    buttonNamed(root(fixture), 'Bring back').click();
    await settle(fixture);

    expect(service.unarchive).toHaveBeenCalledWith('a4');
    expect(text(fixture)).toContain('That account is back in your lists.');
  });

  it('says "permanently" before deleting, and only offers it for an account with no history', async () => {
    const fixture = await render({ accounts: [CHASE] });
    service.remove.mockReturnValue(of(undefined));

    buttonNamed(root(fixture), 'Delete').click();
    await settle(fixture);
    expect(dialog(fixture).textContent).toContain('Delete Chase?');
    expect(dialog(fixture).textContent).toContain('permanently');

    buttonNamed(dialog(fixture), 'Delete').click();
    await settle(fixture);

    expect(service.remove).toHaveBeenCalledWith('a1');
    expect(text(fixture)).toContain('That account has been deleted.');
    expect(text(fixture)).toContain('An account is a place money sits');
  });

  it('does not offer to delete an account the server says has history', async () => {
    const fixture = await render({ accounts: [{ ...CHASE, empty: false }] });

    expect(() => buttonNamed(root(fixture), 'Delete')).toThrow();
    expect(buttonNamed(root(fixture), 'Archive')).toBeTruthy();
  });

  it('points at archiving when the server refuses a delete', async () => {
    const fixture = await render({ accounts: [CHASE] });
    service.remove.mockReturnValue(throwError(() => apiError(409, 'account-not-empty')));

    buttonNamed(root(fixture), 'Delete').click();
    await settle(fixture);
    buttonNamed(dialog(fixture), 'Delete').click();
    await settle(fixture);

    const alert = root(fixture).querySelector('[role=alert]')?.textContent ?? '';
    expect(alert).toContain('cannot be deleted');
    expect(alert).toContain('Archive it instead');
    expect(rows(fixture)).toHaveLength(1);
  });

  it('tells a viewer who reached a write anyway what their role is', async () => {
    const fixture = await render({ accounts: [CHASE] });
    service.archive.mockReturnValue(throwError(() => apiError(403, 'read-only-role')));

    buttonNamed(root(fixture), 'Archive').click();
    await settle(fixture);
    buttonNamed(dialog(fixture), 'Archive').click();
    await settle(fixture);

    expect(root(fixture).querySelector('[role=alert]')?.textContent).toContain('read-only');
    expect(rows(fixture)).toHaveLength(1);
  });

  it('shows the invitation prompt, and asks the server nothing, for a user with no household', async () => {
    auth = fakeAuth({ ...OWNER, household: null });
    for (const fn of Object.values(service)) fn.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: AccountsService, useValue: service },
      ],
    });
    const fixture = TestBed.createComponent(AccountsPage);
    await settle(fixture);

    expect(text(fixture)).toContain('You are not part of a household yet');
    expect(service.list).not.toHaveBeenCalled();
  });

  it('shows the same prompt when the server says 403, rather than an error', async () => {
    const fixture = await render();
    service.list.mockReturnValue(throwError(() => apiError(403, 'not-a-member')));
    await fixture.componentInstance['load']();
    await settle(fixture);

    expect(text(fixture)).toContain('You are not part of a household yet');
    expect(root(fixture).querySelector('[role=alert]')).toBeNull();
  });

  it('shows a failure to load, with a way to retry', async () => {
    const fixture = await render();
    service.list.mockReturnValue(throwError(() => apiError(500, 'internal-error')));
    await fixture.componentInstance['load']();
    await settle(fixture);
    expect(root(fixture).querySelector('[role=alert]')?.textContent).toContain('went wrong');

    service.list.mockReturnValue(page([CHASE]));
    buttonNamed(root(fixture), 'Try again').click();
    await settle(fixture);

    expect(rows(fixture)).toHaveLength(1);
  });

  it('refuses to save a form the server would reject, and says which field', async () => {
    const fixture = await render({ accounts: [] });

    buttonNamed(root(fixture), 'Add your first account').click();
    await settle(fixture);
    typeInto(dialog(fixture), '#account-last-four', '12');
    typeInto(dialog(fixture), '#account-opening-balance', '1,00');
    buttonNamed(dialog(fixture), 'Save account').click();
    await settle(fixture);

    expect(service.create).not.toHaveBeenCalled();
    expect(dialog(fixture).textContent).toContain('Enter exactly four digits');
    expect(dialog(fixture).textContent).toContain('Enter an amount using digits and a dot');
    expect(dialog(fixture).textContent).toContain('This field is required.');
  });
});
