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
  FakeAuth,
  fakeAuth,
  settle,
} from '../../../testing/helpers';
import { CategoriesService } from '../../categories/data/categories.service';
import { Envelope, EnvelopeEntry, EnvelopePage } from '../data/envelope.models';
import { EnvelopesService } from '../data/envelopes.service';
import { EnvelopesPage } from './envelopes.page';

const GROCERIES: Envelope = {
  id: 'e1',
  name: 'Groceries',
  period: '2026-08',
  categoryId: null,
  categoryName: null,
  categoryParentName: null,
  amount: '400.0000',
  spent: '191.1500',
  remaining: '208.8500',
  currency: 'GBP',
  entryCount: 2,
  empty: false,
  archived: false,
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
  version: 1,
};

const OVERSPENT: Envelope = {
  ...GROCERIES,
  id: 'e2',
  name: 'Eating out',
  amount: '30.0000',
  spent: '41.2000',
  remaining: '-11.2000',
  entryCount: 1,
};

const ENTRY: EnvelopeEntry = {
  id: 'n1',
  envelopeId: 'e1',
  date: '2026-08-03',
  amount: '86.4000',
  currency: 'GBP',
  payeeId: null,
  payeeName: 'Greenfield Market',
  note: null,
  source: 'MANUAL',
  transactionId: null,
  createdAt: '2026-08-03T16:20:00Z',
  updatedAt: '2026-08-03T16:20:00Z',
  version: 0,
};

const SYNCED: EnvelopeEntry = {
  ...ENTRY,
  id: 'n2',
  date: '2026-08-12',
  amount: '104.7500',
  source: 'SYNCED',
  transactionId: 't1',
};

const page = (envelopes: readonly Envelope[]): EnvelopePage => ({
  content: [...envelopes],
  totalAllocated: '430.0000',
  totalSpent: '232.3500',
  totalRemaining: '197.6500',
  currency: 'GBP',
  page: 0,
  size: 200,
  totalElements: envelopes.length,
  totalPages: 1,
});

describe('EnvelopesPage', () => {
  let auth: FakeAuth;
  const service = {
    list: vi.fn(),
    periods: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    archive: vi.fn(),
    unarchive: vi.fn(),
    remove: vi.fn(),
    log: vi.fn(),
    logEntry: vi.fn(),
    updateEntry: vi.fn(),
    removeEntry: vi.fn(),
  };
  const categoriesService = { list: vi.fn() };

  async function render(
    options: { role?: HouseholdRole; envelopes?: readonly Envelope[] } = {},
  ): Promise<ComponentFixture<EnvelopesPage>> {
    auth = fakeAuth({
      ...OWNER,
      household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
    });
    for (const fn of Object.values(service)) fn.mockReset();
    categoriesService.list.mockReset();

    service.list.mockReturnValue(of(page(options.envelopes ?? [GROCERIES, OVERSPENT])));
    service.periods.mockReturnValue(of(['2026-08']));
    service.log.mockReturnValue(
      of({ content: [SYNCED, ENTRY], page: 0, size: 50, totalElements: 2, totalPages: 1 }),
    );
    categoriesService.list.mockReturnValue(
      of({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
    );

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: EnvelopesService, useValue: service },
        { provide: CategoriesService, useValue: categoriesService },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(EnvelopesPage);
    await settle(fixture);
    return fixture;
  }

  const text = (fixture: ComponentFixture<EnvelopesPage>): string =>
    (fixture.nativeElement as HTMLElement).textContent ?? '';
  const root = (fixture: ComponentFixture<EnvelopesPage>): HTMLElement => fixture.nativeElement;

  it('leads with what is left, not with what was spent', async () => {
    const fixture = await render();

    const headline = root(fixture).querySelector('.envelope-remaining');
    expect(headline?.textContent).toContain('£208.85');
    expect(headline?.textContent).toContain('left');
  });

  it('shows an overspent envelope as negative rather than clamping it to zero', async () => {
    const fixture = await render({ envelopes: [OVERSPENT] });

    // "You are £11.20 over" is the information. Clamping would hide exactly what somebody needs.
    const headline = root(fixture).querySelector('.envelope-remaining');
    expect(headline?.textContent).toContain('-£11.20');
    expect(headline?.textContent).toContain('over');
    expect(headline?.classList.contains('overspent')).toBe(true);
  });

  it('fills the meter but never past full, and marks it overspent', async () => {
    const fixture = await render({ envelopes: [OVERSPENT] });

    const fill = root(fixture).querySelector<HTMLElement>('.meter-fill');
    expect(fill?.style.width).toBe('100%');
    expect(root(fixture).querySelector('.meter')?.classList.contains('overspent')).toBe(true);
  });

  it('totals the period, which is safe because one household has one currency', async () => {
    const fixture = await render();

    const totals = root(fixture).querySelector('.totals');
    expect(totals?.textContent).toContain('£430.00');
    expect(totals?.textContent).toContain('£232.35');
    expect(totals?.textContent).toContain('£197.65');
  });

  it('moves between months and asks the server for the one it moved to', async () => {
    const fixture = await render();
    const asked = service.list.mock.lastCall?.[0] as string;

    buttonNamed(root(fixture), /Previous month/).click();
    await settle(fixture);

    const now = service.list.mock.lastCall?.[0] as string;
    expect(now).not.toBe(asked);
    expect(now).toMatch(/^\d{4}-\d{2}$/);
  });

  it('opens the log and says where every row came from', async () => {
    const fixture = await render({ envelopes: [GROCERIES] });

    buttonNamed(root(fixture), /Show 2 entries/).click();
    await settle(fixture);

    const log = root(fixture).querySelector('.log');
    expect(log?.textContent).toContain('£86.40');
    // A figure typed and one taken from a transaction are different kinds of claim.
    expect(log?.textContent).toContain('Typed');
    expect(log?.textContent).toContain('From a transaction');
  });

  it('logs spend from an amount and nothing else', async () => {
    const fixture = await render({ envelopes: [GROCERIES] });
    service.logEntry.mockReturnValue(of(ENTRY));

    buttonNamed(root(fixture), /Log spend/).click();
    await settle(fixture);

    const amount = root(fixture).querySelector<HTMLInputElement>('#entry-amount')!;
    amount.value = '18.40';
    amount.dispatchEvent(new Event('input'));
    await settle(fixture);
    buttonNamed(root(fixture), /Log it/).click();
    await settle(fixture);

    expect(service.logEntry).toHaveBeenCalledWith(
      'e1',
      expect.objectContaining({ amount: '18.40' }),
    );
  });

  it('opens the keypad on a phone and still hands the server a string', async () => {
    const fixture = await render({ envelopes: [GROCERIES] });

    buttonNamed(root(fixture), /Log spend/).click();
    await settle(fixture);

    const amount = root(fixture).querySelector<HTMLInputElement>('#entry-amount')!;
    expect(amount.getAttribute('inputmode')).toBe('decimal');
    // Deliberately not type="number": that hands back a double (ADR-0006).
    expect(amount.getAttribute('type')).toBe('text');
  });

  it('says what a delete takes and what it keeps', async () => {
    const fixture = await render({ envelopes: [GROCERIES] });

    buttonNamed(root(fixture), /Delete/).click();
    await settle(fixture);

    const dialog = root(fixture).querySelector('dialog');
    expect(dialog?.textContent).toContain('2 logged entries');
    // The thing somebody is actually afraid of, answered before they commit.
    expect(dialog?.textContent).toContain('Your transactions are kept');
  });

  it('offers a viewer nothing to click but still shows the figures', async () => {
    const fixture = await render({ role: 'VIEWER' });

    expect(text(fixture)).toContain('£208.85');
    expect(root(fixture).querySelectorAll('.envelope-actions button').length).toBeGreaterThan(0); // the log toggle is a read action and stays
    expect(buttonNamedOrNull(root(fixture), /Log spend/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Add an envelope/)).toBeNull();
  });

  it('keeps the list and explains itself when a write is refused', async () => {
    const fixture = await render({ envelopes: [GROCERIES] });
    service.archive.mockReturnValue(throwError(() => apiError(409, 'conflict')));

    buttonNamed(root(fixture), /Archive/).click();
    await settle(fixture);
    root(fixture)
      .querySelector<HTMLButtonElement>('dialog .btn-danger, dialog .btn-primary')!
      .click();
    await settle(fixture);

    // A refused write must not empty a screen full of money.
    expect(text(fixture)).toContain('Groceries');
    expect(text(fixture)).toContain('£208.85');
  });

  it('explains what an envelope is when there are none', async () => {
    const fixture = await render({ envelopes: [] });

    expect(text(fixture)).toContain('an amount you decide on');
    expect(buttonNamedOrNull(root(fixture), /Add your first envelope/)).not.toBeNull();
  });

  function buttonNamedOrNull(host: HTMLElement, name: RegExp): HTMLButtonElement | null {
    return (
      Array.from(host.querySelectorAll('button')).find((button) =>
        name.test(button.textContent ?? ''),
      ) ?? null
    );
  }
});
