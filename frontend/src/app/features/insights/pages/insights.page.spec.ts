import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../core/api-error';
import { HouseholdRole } from '../../../core/auth.models';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { OWNER, OWNER_MEMBERSHIP, fakeAuth, settle } from '../../../testing/helpers';
import {
  CategorySpending,
  CurrencyIncomeAndSpending,
  IncomeAndSpending,
  MonthFigures,
  SpendingByCategory,
} from '../data/reports.models';
import { ReportsService } from '../data/reports.service';
import { InsightsPage } from './insights.page';

function month(period: string, overrides: Partial<MonthFigures> = {}): MonthFigures {
  return {
    period,
    income: '5240.0000',
    spending: '4334.0000',
    uncategorisedIn: '0.0000',
    uncategorisedOut: '0.0000',
    kept: '906.0000',
    complete: true,
    ...overrides,
  };
}

const GBP: CurrencyIncomeAndSpending = {
  currency: 'GBP',
  months: [
    month('2026-06', { income: '5240.0000', spending: '3830.0000', kept: '1410.0000' }),
    month('2026-07', { spending: '-50.0000', kept: '5290.0000', uncategorisedOut: '54.8000' }),
    month('2026-08', {
      income: '5610.0000',
      spending: '200.0000',
      kept: '5410.0000',
      complete: false,
    }),
  ],
  totals: {
    income: '16090.0000',
    spending: '3980.0000',
    uncategorisedIn: '0.0000',
    uncategorisedOut: '54.8000',
    kept: '12055.2000',
  },
  // Deliberately not the mean of the months above: the page shows the server's figure and never
  // computes its own.
  averageKept: '906.0000',
  bestMonth: { period: '2026-06', kept: '1410.0000' },
  completeMonths: 2,
  uncategorisedCount: 3,
  transactionCount: 41,
};

const EUR: CurrencyIncomeAndSpending = {
  ...GBP,
  currency: 'EUR',
  months: GBP.months.map((m) => ({ ...m, income: '777.0000' })),
  averageKept: '12.0000',
  bestMonth: { period: '2026-07', kept: '33.0000' },
  uncategorisedCount: 0,
};

const INCOME: IncomeAndSpending = {
  from: '2026-06',
  to: '2026-08',
  baseCurrency: 'GBP',
  currencies: [GBP],
};

function category(name: string, overrides: Partial<CategorySpending> = {}): CategorySpending {
  return {
    categoryId: `cat-${name}`,
    name,
    archived: false,
    spending: '100.0000',
    comparedSpending: '100.0000',
    change: '0.0000',
    share: '0.1000',
    direct: '100.0000',
    children: [],
    ...overrides,
  };
}

const WHERE: SpendingByCategory = {
  from: '2026-07',
  to: '2026-07',
  comparedFrom: '2026-06',
  comparedTo: '2026-06',
  complete: true,
  baseCurrency: 'GBP',
  currencies: [
    {
      currency: 'GBP',
      spending: '3131.0000',
      comparedSpending: '3070.5000',
      categories: [
        // change is the server's own: 612 against 527.60 is 0.15996…, and the page shows 16%
        // because the server said "0.1600" — not because it divided anything.
        category('Groceries', {
          spending: '612.0000',
          comparedSpending: '527.6000',
          change: '0.1600',
          share: '0.1955',
        }),
        category('Bills', {
          spending: '1450.0000',
          change: '-0.2200',
          children: [category('Rent', { spending: '1450.0000', change: '0.0000' })],
        }),
        category('Hobbies', { comparedSpending: '0.0000', change: null }),
        category('Eating Out'),
        category('Shopping', { spending: '-50.0000', change: null, share: null }),
        category('Archived thing', { archived: true }),
        category('Seventh'),
      ],
      uncategorised: { spending: '54.8000', count: 3 },
    },
  ],
};

describe('InsightsPage', () => {
  let reports: {
    incomeAndSpending: ReturnType<typeof vi.fn>;
    spendingByCategory: ReturnType<typeof vi.fn>;
    csv: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    // The device's own date decides the ranges: 20 August 2026, local time.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 7, 20, 12));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  async function render(
    options: {
      role?: HouseholdRole;
      income?: IncomeAndSpending;
      where?: SpendingByCategory;
      failWith?: ApiError;
    } = {},
  ): Promise<ComponentFixture<InsightsPage>> {
    const auth = fakeAuth({
      ...OWNER,
      household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
    });
    reports = {
      incomeAndSpending: vi
        .fn()
        .mockReturnValue(
          options.failWith ? throwError(() => options.failWith) : of(options.income ?? INCOME),
        ),
      spendingByCategory: vi.fn().mockReturnValue(of(options.where ?? WHERE)),
      csv: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: ReportsService, useValue: reports },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(InsightsPage);
    await settle(fixture);
    return fixture;
  }

  const root = (fixture: ComponentFixture<InsightsPage>): HTMLElement => fixture.nativeElement;
  const text = (fixture: ComponentFixture<InsightsPage>): string => root(fixture).textContent ?? '';
  const squash = (value: string): string => value.replace(/\s+/g, ' ').trim();

  /** A button by what a screen reader announces: its aria-label, or else its text. */
  function buttonNamed(container: HTMLElement, name: RegExp): HTMLButtonElement {
    const found = Array.from(container.querySelectorAll('button')).find((button) =>
      name.test(squash(button.getAttribute('aria-label') ?? button.textContent ?? '')),
    );
    if (!found) {
      throw new Error(`no button named ${String(name)}`);
    }
    return found;
  }

  it('builds both requests from the device date: the last twelve months, and last month', async () => {
    await render();

    expect(reports.incomeAndSpending).toHaveBeenCalledWith('2025-09', '2026-08');
    expect(reports.spendingByCategory).toHaveBeenCalledWith('2026-07', '2026-07');
  });

  it('shows both cards', async () => {
    const fixture = await render();

    const headings = Array.from(root(fixture).querySelectorAll('h2')).map((h) =>
      squash(h.textContent ?? ''),
    );
    expect(headings).toEqual(['Income vs spending', 'Where July 2026 went']);
  });

  it('says the server’s average and best month, and how many months it covers', async () => {
    const fixture = await render();

    expect(squash(text(fixture))).toContain('Avg kept £906.00/mo');
    expect(squash(text(fixture))).toContain(
      'Kept an average of £906.00 a month over the 2 complete months. Best month was June 2026, at £1,410.00.',
    );
  });

  it('shows no insight at all, rather than £0, when no month is complete', async () => {
    const fixture = await render({
      income: {
        ...INCOME,
        currencies: [{ ...GBP, averageKept: null, bestMonth: null, completeMonths: 0 }],
      },
    });

    expect(text(fixture)).not.toContain('Avg kept');
    expect(text(fixture)).not.toContain('Kept an average');
  });

  it('has a real table behind the chart with the same figures as the chart’s sentences', async () => {
    const fixture = await render();

    const description = squash(
      root(fixture).querySelector('#insights-income-chart-description')?.textContent ?? '',
    );
    expect(description).toContain('Income: £5,240.00 in June 2026, to £5,610.00 in August 2026.');
    expect(description).toContain('Spending: £3,830.00 in June 2026, to £200.00 in August 2026.');

    const table = root(fixture).querySelector('table')!;
    expect(table.querySelector('caption')?.textContent).toContain('in GBP');
    const columns = Array.from(table.querySelectorAll('thead th')).map((th) =>
      th.getAttribute('scope'),
    );
    expect(columns.every((scope) => scope === 'col')).toBe(true);
    const rows = Array.from(table.querySelectorAll('tbody tr'));
    expect(rows).toHaveLength(3);
    expect(rows[0].querySelector('th[scope="row"]')?.textContent).toContain('June 2026');
    expect(rows[0].textContent).toContain('£5,240.00');
    expect(rows[2].textContent).toContain('£5,610.00');
    expect(rows[2].querySelector('th')?.textContent).toContain('August 2026 (so far)');
  });

  it('shows negative spending as negative in the table, and says the chart holds it at zero', async () => {
    const fixture = await render();

    const july = Array.from(root(fixture).querySelectorAll('tbody tr'))[1];
    expect(july.textContent).toContain('-£50.00');
    expect(text(fixture)).toContain('is drawn at zero');
  });

  it('draws income and spending as peers that differ by dash as well as colour', async () => {
    const fixture = await render();

    const [income, spending] = Array.from(root(fixture).querySelectorAll('polyline'));
    expect(income.classList).toContain('tone-accent-2');
    expect(income.classList).not.toContain('is-compared');
    expect(spending.classList).toContain('tone-accent');
    expect(spending.classList).toContain('is-compared');
  });

  it('says what moved in words: up, down, flat and new', async () => {
    const fixture = await render();

    const rows = Array.from(root(fixture).querySelectorAll('.categories > li')).map((li) =>
      squash(li.querySelector('.category-line')?.textContent ?? ''),
    );
    expect(rows[0]).toBe('Groceries £612.00 up 16%');
    expect(rows[1]).toBe('Bills £1,450.00 down 22%');
    expect(rows[2]).toBe('Hobbies £100.00 new');
    expect(rows[3]).toBe('Eating Out £100.00 flat');
  });

  it('shows a refund-heavy category’s spending as negative, with no direction to claim', async () => {
    const fixture = await render();

    const shopping = Array.from(root(fixture).querySelectorAll('.category-line')).find((line) =>
      line.textContent?.includes('Shopping'),
    );
    expect(squash(shopping?.textContent ?? '')).toBe('Shopping -£50.00');
  });

  it('puts rows past the sixth behind "Show all"', async () => {
    const fixture = await render();

    expect(text(fixture)).not.toContain('Seventh');
    const showAll = buttonNamed(root(fixture), /Show all 7 categories/);
    expect(showAll.getAttribute('aria-expanded')).toBe('false');

    showAll.click();
    await settle(fixture);

    expect(text(fixture)).toContain('Seventh');
  });

  it('opens a parent to its children', async () => {
    const fixture = await render();

    expect(root(fixture).querySelector('.children')).toBeNull();
    buttonNamed(root(fixture), /Show what's in Bills/).click();
    await settle(fixture);

    expect(squash(root(fixture).querySelector('.children')?.textContent ?? '')).toBe(
      'Rent £1,450.00 flat',
    );
  });

  it('marks an archived category as archived in text', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain('Archived');
  });

  it('points at the "Needs a category" queue, with the server’s own uncategorised figures', async () => {
    const fixture = await render();

    expect(squash(text(fixture))).toContain(
      "3 transactions need a category, so £54.80 isn't counted as income or spending.",
    );
    const links = Array.from(root(fixture).querySelectorAll<HTMLAnchorElement>('a')).filter((a) =>
      a.textContent?.includes('Give them a category'),
    );
    expect(links.length).toBeGreaterThan(0);
    expect(links[0].getAttribute('href')).toBe('/transactions?filter=uncategorised');
  });

  it('shows the empty state, not a flat line and a zero average, when nothing was recorded', async () => {
    const fixture = await render({
      income: {
        ...INCOME,
        currencies: [
          {
            ...GBP,
            months: GBP.months.map((m) =>
              month(m.period, { income: '0.0000', spending: '0.0000', kept: '0.0000' }),
            ),
            averageKept: '0.0000',
            transactionCount: 0,
            uncategorisedCount: 0,
          },
        ],
      },
    });

    expect(squash(text(fixture))).toContain(
      'Nothing recorded between June 2026 and August 2026 yet.',
    );
    expect(root(fixture).querySelector('svg')).toBeNull();
    expect(root(fixture).querySelector('table')).toBeNull();
    expect(text(fixture)).not.toContain('£0.00');
  });

  it('says that amounts logged straight into an envelope do not appear here', async () => {
    const fixture = await render({
      income: { ...INCOME, currencies: [{ ...GBP, transactionCount: 0 }] },
    });

    expect(text(fixture)).toContain("Amounts logged straight into an envelope don't appear here.");
  });

  it('offers a currency switcher when there are several, and never a combined figure', async () => {
    const fixture = await render({ income: { ...INCOME, currencies: [GBP, EUR] } });

    const gbp = buttonNamed(root(fixture), /^GBP$/);
    expect(gbp.getAttribute('aria-pressed')).toBe('true');
    expect(text(fixture)).toContain('£906.00');

    buttonNamed(root(fixture), /^EUR$/).click();
    await settle(fixture);

    expect(buttonNamed(root(fixture), /^EUR$/).getAttribute('aria-pressed')).toBe('true');
    expect(text(fixture)).toContain('€12.00');
    expect(text(fixture)).toContain('€777.00');
    expect(text(fixture)).not.toContain('£906.00');
  });

  it('offers no switcher for one currency', async () => {
    const fixture = await render();

    expect(
      root(fixture).querySelector('[aria-labelledby="insights-currency-label"][role="group"]'),
    ).toBeNull();
  });

  it('re-asks report 1 with a new range when a chip is pressed, and saves nothing', async () => {
    const fixture = await render();

    const three = buttonNamed(root(fixture), /Last 3 months/);
    expect(three.getAttribute('aria-pressed')).toBe('false');
    three.click();
    await settle(fixture);

    expect(reports.incomeAndSpending).toHaveBeenLastCalledWith('2026-06', '2026-08');
    expect(buttonNamed(root(fixture), /Last 3 months/).getAttribute('aria-pressed')).toBe('true');
  });

  it('steps "where it went" back a month, and never past the current one', async () => {
    const fixture = await render();

    buttonNamed(root(fixture), /Previous month/).click();
    await settle(fixture);
    expect(reports.spendingByCategory).toHaveBeenLastCalledWith('2026-06', '2026-06');

    buttonNamed(root(fixture), /Next month/).click();
    await settle(fixture);
    buttonNamed(root(fixture), /Next month/).click();
    await settle(fixture);
    expect(reports.spendingByCategory).toHaveBeenLastCalledWith('2026-08', '2026-08');
    expect(buttonNamed(root(fixture), /Next month/).hasAttribute('disabled')).toBe(true);
  });

  it('says "so far" for the month in progress', async () => {
    const fixture = await render({
      where: { ...WHERE, from: '2026-08', to: '2026-08', complete: false },
    });

    expect(text(fixture)).toContain('has gone so far');
  });

  it('shows a VIEWER everything an OWNER sees', async () => {
    const owner = squash(text(await render({ role: 'OWNER' })));
    TestBed.resetTestingModule();
    const viewer = squash(text(await render({ role: 'VIEWER' })));

    expect(viewer).toBe(owner);
    expect(viewer).toContain('Download CSV');
  });

  it('asks the server for the CSV rather than building one', async () => {
    const fixture = await render();
    reports.csv.mockReturnValue(of(new Blob(['x'], { type: 'text/csv' })));
    const created = vi.fn().mockReturnValue('blob:report');
    const revoked = vi.fn();
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL: created, revokeObjectURL: revoked }),
    );
    let attachedWhenClicked: boolean | undefined;
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      attachedWhenClicked = this.isConnected;
    });

    buttonNamed(root(fixture), /Download CSV of income and spending/).click();
    await settle(fixture);

    expect(reports.csv).toHaveBeenCalledWith('income-and-spending', '2026-06', '2026-08');
    expect(click).toHaveBeenCalled();
    // Clicked while attached, then gone; the URL is revoked after the click's task, not in it.
    expect(attachedWhenClicked).toBe(true);
    expect(document.querySelector('a[download]')).toBeNull();
    await vi.waitFor(() => expect(revoked).toHaveBeenCalledWith('blob:report'));
    click.mockRestore();
    vi.unstubAllGlobals();
  });

  it('offers a retry when the reports cannot be read', async () => {
    const fixture = await render({
      failWith: new ApiError(500, 'internal-error'),
    });

    expect(buttonNamed(root(fixture), /Try again/)).toBeTruthy();
  });
});
