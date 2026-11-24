import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AuthService } from '../../../core/auth.service';
import { HouseholdRole } from '../../../core/auth.models';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  FakeAuth,
  OWNER,
  OWNER_MEMBERSHIP,
  buttonNamed,
  fakeAuth,
  settle,
} from '../../../testing/helpers';
import { AccountsService } from '../../accounts/data/accounts.service';
import { BillsService } from '../../bills/data/bills.service';
import { CurrencyGroup, Debt, DebtList, Plan, Rung, StrategyProjection } from '../data/debt.models';
import { DebtPlanService } from '../data/debt-plan.service';
import { DebtsService } from '../data/debts.service';
import { DebtPlanPage } from './debt-plan.page';

/**
 * The designs' own household, with the designs' own figures where they reconcile: three debts
 * with minimums of 185, 342 and 118, which total 645.
 */
const CARD: Debt = {
  id: 'd1',
  name: 'Visa',
  kind: 'CREDIT_CARD',
  servicer: 'Chase',
  currency: 'GBP',
  balance: '6180.0000',
  balanceSource: 'CONFIRMED',
  balanceAsOf: '2026-09-01',
  estimatedMonths: 0,
  balanceStale: false,
  anchorBalance: '6180.0000',
  anchorDate: '2026-09-01',
  apr: '22.900',
  aprSource: 'ENTERED',
  aprDerivable: true,
  minimumPayment: '185.0000',
  originalBalance: '10650.0000',
  paidOffSoFar: '4470.0000',
  progressPercent: '41.972',
  payment: '445.0000',
  paymentSource: 'FROM_PLAN',
  monthsLeft: 16,
  payoffMonth: '2027-04',
  horizonSource: 'SOLVED',
  totalInterestLeft: '890.0000',
  projectionReason: null,
  rung: 1,
  planMinimum: '185.0000',
  planExtra: '260.0000',
  accountId: null,
  billId: null,
  customOrder: null,
  archived: false,
  paidOffAt: null,
  paymentCount: 7,
  version: 3,
};

const CAR: Debt = {
  ...CARD,
  id: 'd2',
  name: 'Car loan',
  kind: 'INSTALLMENT',
  servicer: 'Alpine Auto Finance',
  balance: '10650.0000',
  apr: '6.400',
  minimumPayment: '342.0000',
  originalBalance: null,
  paidOffSoFar: null,
  progressPercent: null,
  payoffMonth: '2029-02',
  rung: 2,
  planMinimum: '342.0000',
  planExtra: '0.0000',
  billId: 'b7',
  version: 1,
};

const STUDENT: Debt = {
  ...CARD,
  id: 'd3',
  name: 'Student loan',
  kind: 'INSTALLMENT',
  servicer: 'Nelnet',
  balance: '9650.0000',
  apr: '4.500',
  minimumPayment: '118.0000',
  originalBalance: null,
  paidOffSoFar: null,
  progressPercent: null,
  payoffMonth: '2029-06',
  rung: 3,
  planMinimum: '118.0000',
  planExtra: '0.0000',
  version: 1,
};

function rungOf(debt: Debt, extra: string, allocated: string): Rung {
  return {
    rung: debt.rung ?? 1,
    debtId: debt.id,
    name: debt.name,
    kind: debt.kind,
    servicer: debt.servicer,
    balance: debt.balance,
    apr: debt.apr,
    aprDerivable: debt.aprDerivable,
    minimum: debt.minimumPayment,
    extra,
    monthlyAmount: allocated,
    payoffMonth: debt.payoffMonth,
    totalInterest: '890.0000',
  };
}

function projectionOf(
  strategy: StrategyProjection['strategy'],
  options: Partial<StrategyProjection> = {},
): StrategyProjection {
  return {
    strategy,
    debtFreeMonth: '2029-06',
    firstWinMonth: '2027-04',
    totalInterest: '3120.0000',
    reason: null,
    rungs: [],
    series: [
      { month: '2026-11', balance: '26480.0000' },
      { month: '2028-05', balance: '13000.0000' },
      { month: '2029-06', balance: '0.0000' },
    ],
    seriesMonthStride: 1,
    ...options,
  };
}

function groupOf(options: Partial<CurrencyGroup> = {}): CurrencyGroup {
  return {
    currency: 'GBP',
    planCurrency: true,
    monthlyAmount: '905.0000',
    minimums: '645.0000',
    surplus: '260.0000',
    owed: '26480.0000',
    belowMinimums: false,
    selected: projectionOf('AVALANCHE', {
      rungs: [
        rungOf(CARD, '260.0000', '445.0000'),
        rungOf(CAR, '0.0000', '342.0000'),
        rungOf(STUDENT, '0.0000', '118.0000'),
      ],
    }),
    avalanche: projectionOf('AVALANCHE'),
    snowball: projectionOf('SNOWBALL', {
      debtFreeMonth: '2029-09',
      firstWinMonth: '2027-01',
      totalInterest: '3900.0000',
    }),
    ...options,
  };
}

function planOf(options: Partial<Plan> = {}): Plan {
  return {
    strategy: 'AVALANCHE',
    currency: 'GBP',
    monthlyAmount: '905.0000',
    minimums: '645.0000',
    debtCount: 3,
    mixedCurrency: false,
    currencies: [groupOf()],
    version: 4,
    ...options,
  };
}

function listOf(debts: readonly Debt[], options: Partial<DebtList> = {}): DebtList {
  return {
    debts: [...debts],
    strategy: 'AVALANCHE',
    currency: 'GBP',
    owed: '26480.0000',
    debtMinimums: '645.0000',
    debtCount: debts.length,
    ...options,
  };
}

describe('DebtPlanPage', () => {
  let auth: FakeAuth;
  const debtsService = {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    archive: vi.fn(),
    unarchive: vi.fn(),
    confirmBalance: vi.fn(),
    payments: vi.fn(),
    logPayment: vi.fn(),
    correctPayment: vi.fn(),
    removePayment: vi.fn(),
  };
  const plans = { get: vi.fn(), update: vi.fn(), projection: vi.fn() };
  const accountsService = { list: vi.fn() };
  const billsService = { list: vi.fn() };

  async function render(
    options: {
      role?: HouseholdRole;
      plan?: Plan;
      debts?: readonly Debt[];
      list?: DebtList;
      projection?: Plan;
      /** The hypothetical is a separate request, and it is allowed to fail on its own. */
      projectionFails?: boolean;
    } = {},
  ): Promise<ComponentFixture<DebtPlanPage>> {
    auth = fakeAuth({
      ...OWNER,
      household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
    });
    for (const fn of [
      ...Object.values(debtsService),
      ...Object.values(plans),
      accountsService.list,
      billsService.list,
    ]) {
      fn.mockReset();
    }

    const debts = options.debts ?? [CARD, CAR, STUDENT];
    plans.get.mockReturnValue(of(options.plan ?? planOf()));
    if (options.projectionFails) {
      plans.projection.mockReturnValue(throwError(() => ({ status: 500 })));
    } else {
      plans.projection.mockReturnValue(
        of(
          options.projection ??
            planOf({
              currencies: [
                groupOf({ selected: projectionOf('AVALANCHE', { debtFreeMonth: '2029-03' }) }),
              ],
            }),
        ),
      );
    }
    debtsService.list.mockReturnValue(of(options.list ?? listOf(debts)));
    accountsService.list.mockReturnValue(
      of({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 }),
    );
    billsService.list.mockReturnValue(
      of({ content: [], total: '0', outstanding: '0', overdue: '0', currency: 'GBP' }),
    );

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: DebtsService, useValue: debtsService },
        { provide: DebtPlanService, useValue: plans },
        { provide: AccountsService, useValue: accountsService },
        { provide: BillsService, useValue: billsService },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(DebtPlanPage);
    await settle(fixture);
    return fixture;
  }

  const text = (fixture: ComponentFixture<DebtPlanPage>): string =>
    (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('shows both strategies whichever one is selected', async () => {
    const fixture = await render({ plan: planOf({ strategy: 'SNOWBALL' }) });

    // Both cards, both with their own date and their own interest total — the comparison is a
    // product requirement, not a view of the selection.
    expect(text(fixture)).toContain('Avalanche');
    expect(text(fixture)).toContain('Snowball');
    expect(text(fixture)).toContain('June 2029');
    expect(text(fixture)).toContain('September 2029');
    expect(text(fixture)).toContain('£3,120.00');
    expect(text(fixture)).toContain('£3,900.00');
  });

  it('says which order costs less and which clears the first debt sooner, inventing no difference', async () => {
    const fixture = await render();

    const shown = text(fixture);
    expect(shown).toContain('Avalanche costs less in interest');
    expect(shown).toContain('Snowball clears your first debt 3 months sooner');
    // The difference between the two totals is never computed in the browser: both figures are on
    // the cards, and £780 is nowhere on the page.
    expect(shown).not.toContain('£780');
  });

  it('carries the counted-once rule with the TOTAL of the minimums', async () => {
    const fixture = await render();

    // 645 — every minimum, which is what "each counted once" is a claim about. The smaller
    // figure (the part no bill row carries) belongs on Bills & Income, with the term it feeds.
    const footer = (fixture.nativeElement as HTMLElement).querySelector('.footer-line');
    expect(footer?.textContent).toContain('£645.00');
    expect(footer?.textContent).toContain('counted once');
  });

  it('explains itself and offers nothing to look at when there is no debt', async () => {
    const fixture = await render({
      debts: [],
      list: listOf([], { owed: '0.0000', debtMinimums: '0.0000' }),
      plan: planOf({ debtCount: 0, currencies: [] }),
    });

    const shown = text(fixture);
    expect(shown).toContain('Nothing owed here yet');
    expect(buttonNamed(fixture.nativeElement as HTMLElement, 'Add a debt')).toBeTruthy();

    // No $0 balance, no chart of nothing, and no debt-free date of today.
    expect(shown).not.toContain('£0.00');
    expect(shown).not.toContain('Debt-free');
    expect(shown).not.toContain('Balance to zero');
    expect((fixture.nativeElement as HTMLElement).querySelector('svg')).toBeNull();
  });

  it('gives a VIEWER every figure and not one control', async () => {
    const fixture = await render({ role: 'VIEWER' });
    const root = fixture.nativeElement as HTMLElement;

    // Everything a member sees.
    expect(text(fixture)).toContain('£26,480.00');
    expect(text(fixture)).toContain('Avalanche');
    expect(text(fixture)).toContain('Visa');
    expect(text(fixture)).toContain('June 2029');

    // And no write control anywhere — absent, not merely disabled-looking.
    const buttons = Array.from(root.querySelectorAll('button')).map((button) =>
      button.textContent?.trim(),
    );
    expect(buttons).toEqual([]);
    expect(root.querySelector('[role="group"]')).toBeNull();
    expect(text(fixture)).toContain('Your plan uses Avalanche');
  });

  it('offers a member the three orders, and saves the one they press with the plan’s version', async () => {
    const fixture = await render({ role: 'MEMBER' });
    plans.update.mockReturnValue(of(planOf({ strategy: 'SNOWBALL', version: 5 })));

    buttonNamed(fixture.nativeElement as HTMLElement, 'Snowball').click();
    await settle(fixture);

    expect(plans.update).toHaveBeenCalledWith({ version: 4, strategy: 'SNOWBALL' });
  });

  it('keeps the page as it was when a refused write comes back, and says why', async () => {
    const fixture = await render();
    plans.update.mockReturnValue(throwError(() => ({ status: 409 })));

    buttonNamed(fixture.nativeElement as HTMLElement, 'Snowball').click();
    await settle(fixture);

    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')).toBeTruthy();
    expect(text(fixture)).toContain('Visa');
  });

  it('words a negative extra as clearing the debt rather than as a minus beside "extra"', async () => {
    // ADR-0036: a debt owing less than its own minimum clears in month one, so what the plan
    // allocates is LESS than the minimum and `extra` comes back negative.
    const nearlyGone: Debt = { ...CARD, balance: '120.0000', planExtra: '-65.0000' };
    const fixture = await render({
      debts: [nearlyGone],
      list: listOf([nearlyGone]),
      plan: planOf({
        debtCount: 1,
        currencies: [
          groupOf({
            selected: projectionOf('AVALANCHE', {
              rungs: [rungOf(nearlyGone, '-65.0000', '120.0000')],
            }),
          }),
        ],
      }),
    });

    const paying = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.rung-paying'),
    ).map((cell) => cell.textContent?.trim());
    expect(paying).toEqual(['£120.00 — all that is left to clear it']);
    // Not "£185.00 minimum + -£65.00 extra", which is what the allocation literally is.
    expect(text(fixture)).not.toContain('-£65.00');
    expect(text(fixture)).not.toContain('minimum +');
  });

  it('says a rate is not available rather than printing 0%', async () => {
    const noRate: Debt = { ...CARD, apr: null, aprDerivable: false, aprSource: 'SOLVED' };
    const fixture = await render({
      debts: [noRate],
      list: listOf([noRate]),
      plan: planOf({
        debtCount: 1,
        currencies: [
          groupOf({
            selected: projectionOf('AVALANCHE', {
              rungs: [
                { ...rungOf(noRate, '260.0000', '445.0000'), apr: null, aprDerivable: false },
              ],
            }),
          }),
        ],
      }),
    });

    expect(text(fixture)).toContain('Rate not available');
    expect(text(fixture)).not.toContain('0%');
  });

  it('asks for a statement instead of showing a year of extrapolation', async () => {
    const stale: Debt = {
      ...CARD,
      balanceSource: 'ESTIMATED',
      balanceStale: true,
      estimatedMonths: 14,
      anchorDate: '2025-09-01',
    };
    const fixture = await render({
      debts: [stale],
      list: listOf([stale]),
      plan: planOf({
        debtCount: 1,
        currencies: [
          groupOf({
            selected: projectionOf('AVALANCHE', { rungs: [rungOf(stale, '260.0000', '445.0000')] }),
          }),
        ],
      }),
    });

    const shown = text(fixture);
    expect(shown).toContain('Estimated for over a year');
    expect(shown).toContain('Check a statement');
    expect(shown).not.toContain('14 months assumed');
  });

  it('marks an estimate as an estimate, and never as a confirmed figure', async () => {
    const estimated: Debt = { ...CARD, balanceSource: 'ESTIMATED', estimatedMonths: 3 };
    const fixture = await render({
      debts: [estimated],
      list: listOf([estimated]),
      plan: planOf({
        debtCount: 1,
        currencies: [
          groupOf({
            selected: projectionOf('AVALANCHE', {
              rungs: [rungOf(estimated, '260.0000', '445.0000')],
            }),
          }),
        ],
      }),
    });

    expect(text(fixture)).toContain('3 months assumed since you last confirmed it');
    expect(text(fixture)).not.toContain('Confirmed 1');
  });

  it('gives a debt that never clears a reason rather than a blank', async () => {
    const underwater: Debt = {
      ...CARD,
      payoffMonth: null,
      monthsLeft: null,
      totalInterestLeft: null,
      projectionReason: 'never-clears',
    };
    const fixture = await render({
      debts: [underwater],
      list: listOf([underwater]),
      plan: planOf({
        debtCount: 1,
        currencies: [
          groupOf({
            selected: projectionOf('AVALANCHE', {
              debtFreeMonth: null,
              totalInterest: null,
              reason: 'never-clears',
              rungs: [{ ...rungOf(underwater, '260.0000', '445.0000'), payoffMonth: null }],
            }),
            avalanche: projectionOf('AVALANCHE', {
              debtFreeMonth: null,
              totalInterest: null,
              reason: 'never-clears',
            }),
            snowball: projectionOf('SNOWBALL', {
              debtFreeMonth: null,
              totalInterest: null,
              reason: 'never-clears',
            }),
          }),
        ],
      }),
    });

    const shown = text(fixture);
    expect(shown).toContain('Does not clear');
    expect(shown).toContain('the payment is less than the interest this balance builds up');
    // The plan still renders: a plan that refuses to draw because one debt is underwater is a
    // plan nobody can use to fix it.
    expect(shown).toContain('Visa');
    expect(shown).toContain('£645.00');
  });

  it('dates each debt from the plan, never from its own at-minimum projection', async () => {
    // `DebtResponse.payoffMonth` is what this debt does ALONE on its minimum; `rungs[].payoffMonth`
    // is what the plan does with it. On real figures those are years apart, and the at-minimum one
    // under a column headed "Clears" contradicts the page's own debt-free headline.
    const alone: Debt = { ...CARD, payoffMonth: '2034-09' };
    const fixture = await render({
      debts: [alone],
      list: listOf([alone]),
      plan: planOf({
        debtCount: 1,
        currencies: [
          groupOf({
            selected: projectionOf('AVALANCHE', {
              debtFreeMonth: '2029-04',
              rungs: [{ ...rungOf(alone, '260.0000', '445.0000'), payoffMonth: '2029-04' }],
            }),
          }),
        ],
      }),
    });

    const shown = text(fixture);
    expect(shown).toContain('Clears April 2029');
    expect(shown).not.toContain('2034');
  });

  it('says when the plan has no date for a debt, with the plan’s own reason', async () => {
    const fixture = await render({
      plan: planOf({
        currencies: [
          groupOf({
            selected: projectionOf('AVALANCHE', {
              debtFreeMonth: null,
              reason: 'never-clears',
              rungs: [{ ...rungOf(CARD, '260.0000', '445.0000'), payoffMonth: null }],
            }),
          }),
        ],
      }),
      debts: [CARD],
      list: listOf([CARD]),
    });

    expect(text(fixture)).toContain('Does not clear');
  });

  it('says the two lines are one rather than drawing a stroke nobody can see', async () => {
    // Avalanche and snowball over three real debts stay within a fraction of a pixel of each
    // other. The chart decides on the geometry; the page supplies the words, because the chart
    // holds no catalogue. Two keys over one visible stroke reads as a line that failed to render.
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelectorAll('polyline')).toHaveLength(1);
    // One key naming both, and the explanation as prose beside it.
    expect(root.querySelector('.chart-legend')?.textContent?.trim()).toBe('Avalanche · Snowball');
    expect(text(fixture)).toContain('are one line here');
    // And each strategy's own figures are still on the cards and in the text alternative.
    expect(text(fixture)).toContain('£3,120.00');
    expect(text(fixture)).toContain('£3,900.00');
  });

  it('draws both lines when the two orders really do diverge', async () => {
    const fixture = await render({
      plan: planOf({
        currencies: [
          groupOf({
            snowball: projectionOf('SNOWBALL', {
              debtFreeMonth: '2031-09',
              totalInterest: '9000.0000',
              series: [
                { month: '2026-11', balance: '26480.0000' },
                { month: '2028-05', balance: '22000.0000' },
                { month: '2029-06', balance: '0.0000' },
              ],
            }),
          }),
        ],
      }),
    });
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelectorAll('polyline')).toHaveLength(2);
    expect(text(fixture)).not.toContain('are one line here');
  });

  it('shows progress only when there is a starting balance to measure it against', async () => {
    const fixture = await render();

    const shown = text(fixture);
    expect(shown).toContain('£4,470.00 paid off');
    expect(shown).toContain('No starting balance recorded');
    expect(shown).not.toContain('0% of');
  });

  it('says plainly when the monthly amount cannot meet the minimums, and draws no line', async () => {
    const fixture = await render({
      plan: planOf({
        monthlyAmount: '400.0000',
        currencies: [
          groupOf({ monthlyAmount: '400.0000', belowMinimums: true, surplus: '-245.0000' }),
        ],
      }),
    });

    expect(text(fixture)).toContain('£400.00 a month is less than the £645.00');
    expect((fixture.nativeElement as HTMLElement).querySelector('svg')).toBeNull();
    // And it does not ask the what-if about an amount that cannot produce a projection.
    expect(plans.projection).not.toHaveBeenCalled();
  });

  it('asks the what-if about £50 more and reports the answer in months', async () => {
    const fixture = await render();

    expect(plans.projection).toHaveBeenCalledWith({ monthlyAmount: '955.0000' });
    expect(text(fixture)).toContain('Adding £50.00 a month would make you debt-free March 2029');
    expect(text(fixture)).toContain('3 months sooner');
  });

  it('still draws the plan when the what-if fails, because the plan is the page', async () => {
    const fixture = await render({ projectionFails: true });

    expect(text(fixture)).toContain('Balance to zero');
    expect(text(fixture)).not.toContain('Adding');
  });

  it('names every currency and promises no combined total', async () => {
    const euro = { ...STUDENT, currency: 'EUR', id: 'd4', rung: 1 };
    const fixture = await render({
      debts: [CARD, euro],
      list: listOf([CARD, euro], { debtCount: 2 }),
      plan: planOf({
        debtCount: 2,
        mixedCurrency: true,
        currencies: [
          groupOf({
            selected: projectionOf('AVALANCHE', {
              rungs: [rungOf(CARD, '260.0000', '445.0000')],
            }),
          }),
          groupOf({
            currency: 'EUR',
            planCurrency: false,
            owed: '9650.0000',
            selected: projectionOf('AVALANCHE', { rungs: [rungOf(euro, '0.0000', '118.0000')] }),
          }),
        ],
      }),
    });

    const shown = text(fixture);
    expect(shown).toContain('more than one currency');
    expect(shown).toContain('In EUR');
    expect(shown).toContain('€9,650.00');
  });

  it('opens the dialog on a debt and tells the household when the rate has been released', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    debtsService.update.mockReturnValue(of({}));

    buttonNamed(root, 'Edit terms').click();
    await settle(fixture);

    const release = root.querySelector<HTMLInputElement>('#debt-apr-solved');
    expect(release).toBeTruthy();
    release!.click();
    release!.dispatchEvent(new Event('change', { bubbles: true }));
    await settle(fixture);

    buttonNamed(root, 'Save').click();
    await settle(fixture);

    expect(debtsService.update).toHaveBeenCalledWith('d1', { version: 3, apr: null });
    expect(text(fixture)).toContain('now worked out from your other figures');
  });

  it('shows the same debts in both structures, so a phone and a desktop agree', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;

    const inTable = Array.from(root.querySelectorAll('.debt-table tbody tr th')).map(
      (cell) => cell.textContent?.trim().split('\n')[0],
    );
    const inLadder = Array.from(root.querySelectorAll('.ladder .rung-name')).map((cell) =>
      cell.textContent?.trim(),
    );
    expect(inLadder).toEqual(['Visa', 'Car loan', 'Student loan']);
    expect(inTable.length).toBe(inLadder.length);
  });
});
