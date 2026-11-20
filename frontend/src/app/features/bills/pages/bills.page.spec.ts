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
import { AccountsService } from '../../accounts/data/accounts.service';
import { CategoriesService } from '../../categories/data/categories.service';
import {
  Bill,
  BillOccurrence,
  BillsPage,
  IncomeList,
  IncomeSource,
  PeriodSummary,
} from '../data/bills.models';
import { BillsService } from '../data/bills.service';
import { IncomeService } from '../data/income.service';
import { Schedule } from '../data/schedule.models';
import { PeriodSummaryService } from '../data/summary.service';
import { BillsPage as BillsPageComponent } from './bills.page';

const MONTHLY: Schedule = {
  kind: 'MONTHLY',
  dayOfMonth: 15,
  month: null,
  dayOfWeek: null,
  anchorDate: null,
};

const WEEKLY: Schedule = {
  kind: 'WEEKLY',
  dayOfMonth: null,
  month: null,
  dayOfWeek: 'FRIDAY',
  anchorDate: null,
};

const IRREGULAR: Schedule = {
  kind: 'IRREGULAR',
  dayOfMonth: null,
  month: null,
  dayOfWeek: null,
  anchorDate: null,
};

const due = (overrides: Partial<BillOccurrence> = {}): BillOccurrence => ({
  billId: 'b1',
  dueDate: '2026-08-15',
  expectedAmount: '1200.0000',
  status: 'DUE',
  actualAmount: null,
  paidDate: null,
  transactionId: null,
  statusSource: 'MANUAL',
  recorded: false,
  overdue: false,
  surprising: false,
  version: null,
  ...overrides,
});

const RENT: Bill = {
  id: 'b1',
  name: 'Rent',
  categoryId: null,
  categoryName: null,
  categoryParentName: null,
  expectedAmount: '1200.0000',
  currency: 'GBP',
  schedule: MONTHLY,
  amountVaries: false,
  tolerance: null,
  active: true,
  occurrences: [due()],
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
  version: 1,
};

/** Four Fridays in August 2026 — four separate payments, each ticked off on its own. */
const CLEANER: Bill = {
  ...RENT,
  id: 'b2',
  name: 'Cleaner',
  expectedAmount: '45.0000',
  schedule: WEEKLY,
  occurrences: ['2026-08-07', '2026-08-14', '2026-08-21', '2026-08-28'].map((dueDate) =>
    due({ billId: 'b2', dueDate, expectedAmount: '45.0000' }),
  ),
};

const VET: Bill = {
  ...RENT,
  id: 'b3',
  name: 'Vet',
  expectedAmount: '80.0000',
  schedule: IRREGULAR,
  occurrences: [],
};

const SALARY: IncomeSource = {
  id: 'i1',
  name: 'Salary',
  amount: '1100.0000',
  currency: 'GBP',
  schedule: {
    kind: 'FORTNIGHTLY',
    dayOfMonth: null,
    month: null,
    dayOfWeek: 'WEDNESDAY',
    anchorDate: '2026-08-05',
  },
  accountId: null,
  accountName: null,
  sourceOfTruth: 'MANUAL_ESTIMATE',
  active: true,
  dates: ['2026-08-05', '2026-08-19'],
  expectedForPeriod: '2200.0000',
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
  version: 1,
};

const billsPage = (bills: readonly Bill[]): BillsPage => ({
  content: [...bills],
  total: '1200.0000',
  outstanding: '340.0000',
  overdue: '0.0000',
  currency: 'GBP',
});

const incomeList = (sources: readonly IncomeSource[]): IncomeList => ({
  content: [...sources],
  total: '2200.0000',
  currency: 'GBP',
});

/** A month that does not add up: £2,200 in, £2,000 of bills, £380 set aside. */
const SHORT: PeriodSummary = {
  period: '2026-08',
  income: '2200.0000',
  bills: '2000.0000',
  billsOutstanding: '340.0000',
  billsOverdue: '0.0000',
  envelopesAllocated: '380.0000',
  left: '-180.0000',
  currency: 'GBP',
};

describe('BillsPage', () => {
  let auth: FakeAuth;
  const bills = {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    record: vi.fn(),
    forget: vi.fn(),
  };
  const income = {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  };
  const summaries = { get: vi.fn() };
  const categoriesService = { list: vi.fn() };
  const accountsService = { list: vi.fn() };

  async function render(
    options: {
      role?: HouseholdRole;
      bills?: readonly Bill[];
      income?: readonly IncomeSource[];
      summary?: PeriodSummary;
    } = {},
  ): Promise<ComponentFixture<BillsPageComponent>> {
    auth = fakeAuth({
      ...OWNER,
      household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
    });
    for (const fn of [
      ...Object.values(bills),
      ...Object.values(income),
      summaries.get,
      categoriesService.list,
      accountsService.list,
    ]) {
      fn.mockReset();
    }

    bills.list.mockReturnValue(of(billsPage(options.bills ?? [RENT])));
    income.list.mockReturnValue(of(incomeList(options.income ?? [SALARY])));
    summaries.get.mockReturnValue(of(options.summary ?? SHORT));
    categoriesService.list.mockReturnValue(
      of({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
    );
    accountsService.list.mockReturnValue(
      of({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
    );

    // Reset first: a couple of these tests render twice, to compare two shapes of the same row.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: BillsService, useValue: bills },
        { provide: IncomeService, useValue: income },
        { provide: PeriodSummaryService, useValue: summaries },
        { provide: CategoriesService, useValue: categoriesService },
        { provide: AccountsService, useValue: accountsService },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(BillsPageComponent);
    await settle(fixture);
    return fixture;
  }

  const root = (fixture: ComponentFixture<BillsPageComponent>): HTMLElement =>
    fixture.nativeElement;
  const text = (fixture: ComponentFixture<BillsPageComponent>): string =>
    (fixture.nativeElement as HTMLElement).textContent ?? '';

  function buttonNamedOrNull(host: HTMLElement, name: RegExp): HTMLButtonElement | null {
    return (
      Array.from(host.querySelectorAll('button')).find((button) =>
        name.test(button.textContent ?? ''),
      ) ?? null
    );
  }

  function occurrenceRows(fixture: ComponentFixture<BillsPageComponent>): HTMLElement[] {
    return Array.from(root(fixture).querySelectorAll<HTMLElement>('.occurrence'));
  }

  // ------------------------------------------------------------------------ 1. what's left

  it('leads with what is left, and renders it negative rather than clamping it', async () => {
    const fixture = await render();

    // "You are £180 short this month" is the information. Clamping to zero would hide exactly
    // what somebody needs to see.
    const headline = root(fixture).querySelector('.headline');
    expect(headline?.textContent).toContain('-£180.00');
    expect(headline?.textContent).toContain('short');
    expect(headline?.classList.contains('is-short')).toBe(true);
  });

  it('says "left" and drops the bad-news styling when the month does add up', async () => {
    const fixture = await render({
      summary: { ...SHORT, left: '420.0000' },
    });

    const headline = root(fixture).querySelector('.headline');
    expect(headline?.textContent).toContain('£420.00');
    expect(headline?.textContent).toContain('left');
    expect(headline?.classList.contains('is-short')).toBe(false);
  });

  it('shows the three terms the subtraction is made of', async () => {
    const fixture = await render();

    // So that a plan counted twice — rent as a bill *and* as an envelope — is visible.
    const terms = root(fixture).querySelector('.terms');
    expect(terms?.textContent).toContain('£2,200.00');
    expect(terms?.textContent).toContain('£2,000.00');
    expect(terms?.textContent).toContain('£380.00');
  });

  it('reports what is still to pay without leading with it', async () => {
    const fixture = await render();

    const secondary = root(fixture).querySelector('.secondary-figure');
    expect(secondary?.textContent).toContain('£340.00');
    // Bills count whether or not they are paid, so the headline is not the outstanding figure.
    expect(root(fixture).querySelector('.headline-amount')?.textContent).toContain('-£180.00');
  });

  it('asks the server for the subtraction rather than adding up its own blocks', async () => {
    const fixture = await render();

    // One request for the headline, for the month every other block was asked for: a page that
    // assembled the figure itself could show a headline its own blocks disagree with.
    expect(summaries.get).toHaveBeenCalledTimes(1);
    expect(summaries.get.mock.lastCall?.[0]).toBe(bills.list.mock.lastCall?.[0]);
    expect(text(fixture)).toContain('-£180.00');
  });

  // ---------------------------------------------------------------------------- 2. income

  it('says how many times a fortnightly source arrives this month', async () => {
    const fixture = await render();

    // Two or three arrivals is the month the household actually has, not a figure averaged flat.
    expect(text(fixture)).toContain('2 arrivals this month');
    expect(text(fixture)).toContain('Every other Wednesday');
    // The month's figure and what arrives each time are both shown, and are not the same number.
    expect(text(fixture)).toContain('£2,200.00');
    expect(text(fixture)).toContain('£1,100.00');
  });

  it('explains what an income source is when there are none', async () => {
    const fixture = await render({ income: [] });

    expect(text(fixture)).toContain('money arriving on a schedule');
    expect(buttonNamedOrNull(root(fixture), /Add your first income source/)).not.toBeNull();
  });

  // ----------------------------------------------------------------------------- 3. bills

  it('gives a weekly bill one row per date, each markable on its own', async () => {
    const fixture = await render({ bills: [CLEANER] });

    const rows = occurrenceRows(fixture);
    expect(rows).toHaveLength(4);
    // Each row carries its own one-tap action, keyed on its own date.
    for (const row of rows) {
      expect(buttonNamedOrNull(row, /Mark paid/)).not.toBeNull();
    }

    bills.record.mockReturnValue(of(due({ billId: 'b2', status: 'PAID' })));
    buttonNamed(rows[2], /Mark paid/).click();
    await settle(fixture);

    // The third Friday, and no other: a month is how occurrences are listed, a date is what one is.
    expect(bills.record).toHaveBeenCalledTimes(1);
    expect(bills.record).toHaveBeenCalledWith('b2', '2026-08-21', { status: 'PAID' });
  });

  it('marks a bill paid for what was budgeted, with nothing else to fill in', async () => {
    const fixture = await render();
    bills.record.mockReturnValue(of(due({ status: 'PAID' })));

    buttonNamed(root(fixture), /Mark paid/).click();
    await settle(fixture);

    // No amount and no date: "I paid it" means what was budgeted, today, and the server defaults
    // both. One tap is the whole point of the action.
    expect(bills.record).toHaveBeenCalledWith('b1', '2026-08-15', { status: 'PAID' });
  });

  it('says an occurrence is overdue', async () => {
    const fixture = await render({
      bills: [{ ...RENT, occurrences: [due({ overdue: true })] }],
    });

    const row = occurrenceRows(fixture)[0];
    expect(row.textContent).toContain('Overdue');
    expect(row.classList.contains('is-overdue')).toBe(true);
  });

  it('shows both figures for a payment that surprised', async () => {
    const fixture = await render({
      bills: [
        {
          ...RENT,
          amountVaries: true,
          tolerance: '22.0000',
          occurrences: [
            due({
              status: 'PAID',
              actualAmount: '1300.5000',
              paidDate: '2026-08-14',
              recorded: true,
              surprising: true,
              version: 0,
            }),
          ],
        },
      ],
    });

    const row = occurrenceRows(fixture)[0];
    // The difference is the useful number, so neither figure is dropped.
    expect(row.textContent).toContain('£1,200.00');
    expect(row.textContent).toContain('£1,300.50');
    expect(row.textContent).toContain('Not what was budgeted');
  });

  it('shows one figure for a payment that did not surprise', async () => {
    const fixture = await render({
      bills: [
        {
          ...RENT,
          occurrences: [due({ status: 'PAID', recorded: true, surprising: false, version: 0 })],
        },
      ],
    });

    const row = occurrenceRows(fixture)[0];
    expect(row.textContent).not.toContain('Not what was budgeted');
  });

  it('offers Forget only on an occurrence that has something recorded', async () => {
    const projected = await render();
    const projectedRow = occurrenceRows(projected)[0];
    // Nothing is stored: the occurrence is due because the schedule says so, and there is
    // nothing to forget.
    expect(buttonNamedOrNull(projectedRow, /Forget/)).toBeNull();

    const recorded = await render({
      bills: [{ ...RENT, occurrences: [due({ status: 'SKIPPED', recorded: true, version: 0 })] }],
    });
    const recordedRow = occurrenceRows(recorded)[0];
    expect(buttonNamedOrNull(recordedRow, /Forget/)).not.toBeNull();
    // And it is not the same thing as marking it due, which is also on offer.
    expect(buttonNamedOrNull(recordedRow, /Mark as due/)).not.toBeNull();
  });

  it('says what forgetting is, and is not, before doing it', async () => {
    const fixture = await render({
      bills: [{ ...RENT, occurrences: [due({ status: 'PAID', recorded: true, version: 0 })] }],
    });
    bills.forget.mockReturnValue(of(undefined));

    buttonNamed(occurrenceRows(fixture)[0], /Forget/).click();
    await settle(fixture);

    const dialog = root(fixture).querySelector('dialog');
    expect(dialog?.textContent).toContain('not the same as marking it due');

    root(fixture).querySelector<HTMLButtonElement>('dialog .btn-danger')!.click();
    await settle(fixture);

    expect(bills.forget).toHaveBeenCalledWith('b1', '2026-08-15');
  });

  it('lists an irregular bill that has no occurrence, and explains why', async () => {
    const fixture = await render({ bills: [VET] });

    // A bill nobody can predict is still a bill they want listed.
    expect(text(fixture)).toContain('Vet');
    expect(text(fixture)).toContain('no set date');
    expect(occurrenceRows(fixture)).toHaveLength(0);
    // Recording against a date is what brings its occurrence into being.
    expect(buttonNamedOrNull(root(fixture), /Record a payment/)).not.toBeNull();
  });

  it('records an irregular payment against the date somebody chose', async () => {
    const fixture = await render({ bills: [VET] });
    bills.record.mockReturnValue(of(due({ billId: 'b3', dueDate: '2026-08-09' })));

    buttonNamed(root(fixture), /Record a payment/).click();
    await settle(fixture);

    const dueDate = root(fixture).querySelector<HTMLInputElement>('#record-due-date')!;
    dueDate.value = '2026-08-09';
    dueDate.dispatchEvent(new Event('input'));
    const amount = root(fixture).querySelector<HTMLInputElement>('#record-amount')!;
    amount.value = '96.40';
    amount.dispatchEvent(new Event('input'));
    await settle(fixture);
    buttonNamed(root(fixture), /Record it/).click();
    await settle(fixture);

    expect(bills.record).toHaveBeenCalledWith(
      'b3',
      '2026-08-09',
      expect.objectContaining({ status: 'PAID', actualAmount: '96.40' }),
    );
  });

  it('hands the server an amount as a string, from a keypad-friendly text input', async () => {
    const fixture = await render();
    bills.record.mockReturnValue(of(due({ status: 'PAID' })));

    buttonNamed(occurrenceRows(fixture)[0], /Paid a different amount/).click();
    await settle(fixture);

    const amount = root(fixture).querySelector<HTMLInputElement>('#record-amount')!;
    expect(amount.getAttribute('inputmode')).toBe('decimal');
    // Deliberately not type="number": that hands back a double (ADR-0006).
    expect(amount.getAttribute('type')).toBe('text');

    amount.value = '1300.50';
    amount.dispatchEvent(new Event('input'));
    await settle(fixture);
    buttonNamed(root(fixture), /Record it/).click();
    await settle(fixture);

    const [, , body] = bills.record.mock.lastCall as [string, string, { actualAmount: string }];
    expect(body.actualAmount).toBe('1300.50');
    expect(typeof body.actualAmount).toBe('string');
  });

  it('skips a month a bill does not apply to, without deleting the bill', async () => {
    const fixture = await render();
    bills.record.mockReturnValue(of(due({ status: 'SKIPPED', recorded: true, version: 0 })));

    buttonNamed(occurrenceRows(fixture)[0], /Skip/).click();
    await settle(fixture);

    expect(bills.record).toHaveBeenCalledWith('b1', '2026-08-15', { status: 'SKIPPED' });
    expect(bills.remove).not.toHaveBeenCalled();
  });

  it('says a retired bill keeps what was recorded against it', async () => {
    const fixture = await render();
    bills.update.mockReturnValue(of({ ...RENT, active: false, version: 2 }));

    const section = root(fixture).querySelector<HTMLElement>(
      'section[aria-labelledby="bills-heading"]',
    )!;
    buttonNamed(section, /Retire/).click();
    await settle(fixture);

    const dialog = root(fixture).querySelector('dialog');
    expect(dialog?.textContent).toContain('keeps every payment you have already recorded');

    root(fixture).querySelector<HTMLButtonElement>('dialog .btn-danger')!.click();
    await settle(fixture);

    // Retired, not erased: a PATCH, with the version it read.
    expect(bills.update).toHaveBeenCalledWith('b1', { version: 1, active: false });
  });

  it('lists a retired bill separately, with a way back', async () => {
    const fixture = await render({ bills: [{ ...RENT, active: false }] });

    expect(text(fixture)).toContain('Retired bills');
    expect(buttonNamedOrNull(root(fixture), /Restore/)).not.toBeNull();
    // And it is not offered as something to record against: the server refuses that.
    expect(buttonNamedOrNull(root(fixture), /Mark paid/)).toBeNull();
  });

  it('adds an income source with the amount as a string', async () => {
    const fixture = await render();
    income.create.mockReturnValue(of(SALARY));

    buttonNamed(root(fixture), /Add an income source/).click();
    await settle(fixture);

    const fill = (selector: string, value: string) => {
      const field = root(fixture).querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
      field.value = value;
      field.dispatchEvent(new Event('input'));
      field.dispatchEvent(new Event('change'));
    };
    fill('#income-name', 'Salary');
    fill('#income-amount', '1100.50');
    fill('#income-day-of-month', '15');
    await settle(fixture);
    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    expect(income.create).toHaveBeenCalledWith({
      name: 'Salary',
      amount: '1100.50',
      schedule: { kind: 'MONTHLY', dayOfMonth: 15 },
    });
  });

  it('explains what a bill is when there are none', async () => {
    const fixture = await render({ bills: [] });

    expect(text(fixture)).toContain('something that leaves on a date');
    expect(buttonNamedOrNull(root(fixture), /Add your first bill/)).not.toBeNull();
  });

  it('keeps the page and explains itself when a write is refused', async () => {
    const fixture = await render();
    bills.record.mockReturnValue(throwError(() => apiError(409, 'bill-inactive')));

    buttonNamed(root(fixture), /Mark paid/).click();
    await settle(fixture);

    // A refused write must not empty a screen full of money.
    expect(text(fixture)).toContain('Rent');
    expect(text(fixture)).toContain('-£180.00');
    expect(text(fixture)).toContain('retired');
  });

  // -------------------------------------------------------------------- the schedule editor

  it('clears the parameters that stopped applying when the schedule kind changes', async () => {
    const fixture = await render();
    bills.create.mockReturnValue(of(RENT));

    buttonNamed(root(fixture), /Add a bill/).click();
    await settle(fixture);

    const typeInto = (selector: string, value: string) => {
      const field = root(fixture).querySelector<HTMLInputElement | HTMLSelectElement>(selector)!;
      field.value = value;
      field.dispatchEvent(new Event('input'));
      field.dispatchEvent(new Event('change'));
    };

    typeInto('#bill-name', 'Cleaner');
    typeInto('#bill-amount', '45');
    typeInto('#bill-day-of-month', '15');
    await settle(fixture);

    typeInto('#bill-kind', 'WEEKLY');
    await settle(fixture);

    // The day of the month is gone from the form, not merely hidden: a weekly bill does not have
    // one, and sending a parameter the kind has no use for is a 400.
    expect(root(fixture).querySelector('#bill-day-of-month')).toBeNull();
    typeInto('#bill-day-of-week', 'FRIDAY');
    await settle(fixture);

    buttonNamed(root(fixture), /^\s*Save\s*$/).click();
    await settle(fixture);

    expect(bills.create).toHaveBeenCalledWith(
      expect.objectContaining({ schedule: { kind: 'WEEKLY', dayOfWeek: 'FRIDAY' } }),
    );
  });

  it('asks for a tolerance only when the amount varies', async () => {
    const fixture = await render();

    buttonNamed(root(fixture), /Add a bill/).click();
    await settle(fixture);
    expect(root(fixture).querySelector('#bill-tolerance')).toBeNull();

    const varies = root(fixture).querySelector<HTMLInputElement>('#bill-varies')!;
    varies.click();
    await settle(fixture);

    expect(root(fixture).querySelector('#bill-tolerance')).not.toBeNull();
  });

  it('opens an edit with the schedule the bill actually has', async () => {
    const fixture = await render({ bills: [CLEANER] });

    const section = root(fixture).querySelector<HTMLElement>(
      'section[aria-labelledby="bills-heading"]',
    )!;
    buttonNamed(section, /Edit/).click();
    await settle(fixture);

    // A weekly bill opens on its weekday, with no day-of-the-month field anywhere.
    const weekday = root(fixture).querySelector<HTMLSelectElement>('#bill-day-of-week');
    expect(weekday?.value).toBe('FRIDAY');
    expect(root(fixture).querySelector('#bill-day-of-month')).toBeNull();
  });

  // ------------------------------------------------------------------------------ the month

  it('moves between months and asks the server for the one it moved to', async () => {
    const fixture = await render();
    const asked = bills.list.mock.lastCall?.[0] as string;

    buttonNamed(root(fixture), /Previous month/).click();
    await settle(fixture);

    const now = bills.list.mock.lastCall?.[0] as string;
    expect(now).not.toBe(asked);
    expect(now).toMatch(/^\d{4}-\d{2}$/);
    // Every block follows the month, so the headline cannot describe a different one.
    expect(income.list.mock.lastCall?.[0]).toBe(now);
    expect(summaries.get.mock.lastCall?.[0]).toBe(now);
  });

  // --------------------------------------------------------------------------------- roles

  it('offers a viewer every figure and no write control at all', async () => {
    const fixture = await render({ role: 'VIEWER', bills: [CLEANER] });

    expect(text(fixture)).toContain('-£180.00');
    expect(text(fixture)).toContain('£45.00');
    expect(text(fixture)).toContain('2 arrivals this month');
    expect(occurrenceRows(fixture)).toHaveLength(4);

    expect(buttonNamedOrNull(root(fixture), /Mark paid/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Paid a different amount/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Skip/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Forget/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Add a bill/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Add an income source/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Edit/)).toBeNull();
    expect(buttonNamedOrNull(root(fixture), /Delete/)).toBeNull();
  });

  // ------------------------------------------------------------------------- 4. envelopes

  it('links to the envelopes screen rather than rebuilding it', async () => {
    const fixture = await render();

    const link = Array.from(root(fixture).querySelectorAll('a')).find((anchor) =>
      /Open envelopes/.test(anchor.textContent ?? ''),
    );
    expect(link?.getAttribute('href')).toBe('/envelopes');
    // The figure comes from the summary, so it cannot disagree with the headline's third term.
    expect(text(fixture)).toContain('£380.00');
  });
});
