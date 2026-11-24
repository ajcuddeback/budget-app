import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { BehaviorSubject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { HouseholdRole } from '../../../core/auth.models';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  FakeAuth,
  OWNER,
  OWNER_MEMBERSHIP,
  buttonNamed,
  fakeAuth,
  settle,
  type,
} from '../../../testing/helpers';
import { AccountsService } from '../../accounts/data/accounts.service';
import { BillsService } from '../../bills/data/bills.service';
import { Debt, DebtDetail, DebtPayment, SeriesPoint } from '../data/debt.models';
import { DebtsService } from '../data/debts.service';
import { DebtDetailPage } from './debt-detail.page';

/** The designs' card: an entered rate, a solved horizon, and a payment the plan assigned. */
const VISA: Debt = {
  id: 'deb-0001',
  name: 'Visa',
  kind: 'CREDIT_CARD',
  servicer: 'Chase',
  currency: 'GBP',
  balance: '6180.0000',
  balanceSource: 'CONFIRMED',
  balanceAsOf: '2026-08-20',
  estimatedMonths: 0,
  balanceStale: false,
  anchorBalance: '6180.0000',
  anchorDate: '2026-08-20',
  apr: '22.900',
  aprSource: 'ENTERED',
  aprDerivable: true,
  minimumPayment: '185.0000',
  originalBalance: '10650.0000',
  paidOffSoFar: '4470.0000',
  progressPercent: '42.0',
  payment: '445.0000',
  paymentSource: 'FROM_PLAN',
  monthsLeft: 17,
  payoffMonth: '2027-12',
  horizonSource: 'SOLVED',
  totalInterestLeft: '1069.3700',
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
  version: 1,
};

const CAR: Debt = { ...VISA, id: 'deb-0002', name: 'Car loan', rung: 2, paymentCount: 0 };

/**
 * A point is what is still owed at the **start** of that month, so the first point at zero is the
 * month *after* the debt cleared — which is why the pace line's last point is 2028-01 for a debt
 * whose `payoffMonth` is 2027-12. The fixtures from the real backend have exactly this shape.
 */
const PACE: SeriesPoint[] = [
  { month: '2026-08', balance: '6180.0000' },
  { month: '2027-04', balance: '3000.0000' },
  { month: '2028-01', balance: '0.0000' },
];

const AT_MINIMUM: SeriesPoint[] = [
  { month: '2026-08', balance: '6180.0000' },
  { month: '2028-11', balance: '4000.0000' },
  { month: '2031-02', balance: '0.0000' },
];

function detailOf(debt: Debt, over: Partial<DebtDetail> = {}): DebtDetail {
  return {
    debt,
    pace: PACE,
    atMinimum: AT_MINIMUM,
    seriesMonthStride: 1,
    atMinimumIsTheSameLine: false,
    ...over,
  };
}

const LOGGED: DebtPayment = {
  id: 'pay-1',
  debtId: 'deb-0001',
  paidDate: '2026-08-20',
  currency: 'GBP',
  amount: '445.0000',
  interest: '123.7341',
  principal: '321.2659',
  balanceAfter: '6162.6195',
  provenance: 'USER_LOGGED',
  transactionId: null,
  billOccurrenceId: null,
  paidFromAccountId: 'acc-1',
  note: null,
  version: 0,
};

const CORRECTION: DebtPayment = {
  ...LOGGED,
  id: 'pay-2',
  amount: '23.9924',
  interest: '0.0000',
  principal: '23.9924',
  balanceAfter: '6180.0000',
  provenance: 'STATEMENT_CORRECTION',
  paidFromAccountId: null,
  note: 'Statement from Chase',
};

const ACCOUNT = {
  id: 'acc-1',
  name: 'Everyday Checking',
  lastFour: null,
  type: 'CHECKING',
  currency: 'GBP',
  openingBalance: '0.0000',
  balance: '0.0000',
  archived: false,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 0,
};

describe('DebtDetailPage', () => {
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
  const accountsService = { list: vi.fn() };
  const billsService = { list: vi.fn() };

  async function render(
    options: {
      role?: HouseholdRole;
      detail?: DebtDetail;
      payments?: readonly DebtPayment[];
      siblings?: readonly Debt[];
      route?: { paramMap: BehaviorSubject<Map<string, string>> };
    } = {},
  ): Promise<ComponentFixture<DebtDetailPage>> {
    auth = fakeAuth({
      ...OWNER,
      household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
    });
    for (const fn of [...Object.values(debtsService), accountsService.list, billsService.list]) {
      fn.mockReset();
    }

    const payments = options.payments ?? [LOGGED, CORRECTION];
    debtsService.get.mockReturnValue(of(options.detail ?? detailOf(VISA)));
    debtsService.payments.mockReturnValue(
      of({
        content: [...payments],
        page: 0,
        size: 50,
        totalElements: payments.length,
        totalPages: 1,
      }),
    );
    debtsService.list.mockReturnValue(
      of({
        debts: [...(options.siblings ?? [VISA, CAR])],
        strategy: 'AVALANCHE',
        currency: 'GBP',
        owed: '16830.0000',
        debtMinimums: '527.0000',
        debtCount: 2,
      }),
    );
    accountsService.list.mockReturnValue(
      of({ content: [ACCOUNT], page: 0, size: 200, totalElements: 1, totalPages: 1 }),
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
        { provide: AccountsService, useValue: accountsService },
        { provide: BillsService, useValue: billsService },
        {
          provide: ActivatedRoute,
          useValue: (options.route ?? {
            paramMap: of(new Map([['id', 'deb-0001']])),
          }) as unknown as ActivatedRoute,
        },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(DebtDetailPage);
    await settle(fixture);
    return fixture;
  }

  const text = (fixture: ComponentFixture<DebtDetailPage>): string =>
    (fixture.nativeElement as HTMLElement).textContent ?? '';

  const cellFor = (fixture: ComponentFixture<DebtDetailPage>, label: string): HTMLElement => {
    const found = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.term'),
    ).find((term) => term.querySelector('dt')?.textContent?.trim() === label);
    if (!found) {
      throw new Error(`no term cell labelled ${label}`);
    }
    return found;
  };

  it('tags what it worked out, names what the plan assigned, and leaves your own word unmarked', () => {
    // Three labels, not two. This is the whole point of the panel: a figure the app worked out
    // must never be mistaken for one the household asserted.
    return render().then((fixture) => {
      expect(cellFor(fixture, 'Annual rate').querySelector('.tag')).toBeNull();
      expect(cellFor(fixture, 'Balance now').querySelector('.tag')).toBeNull();
      expect(cellFor(fixture, 'Minimum payment').querySelector('.tag')).toBeNull();

      expect(cellFor(fixture, 'Months left').querySelector('.tag')?.textContent).toContain(
        'Solved',
      );
      expect(cellFor(fixture, 'Gone by').querySelector('.tag')?.textContent).toContain('Solved');
      expect(
        cellFor(fixture, 'Interest still to come').querySelector('.tag')?.textContent,
      ).toContain('Solved');

      expect(cellFor(fixture, 'What you are paying').querySelector('.tag')?.textContent).toMatch(
        /from your plan/i,
      );
    });
  });

  it('counts the panel from the panel, so the count cannot drift from the cells', async () => {
    const fixture = await render();

    // Entered: original balance, balance, rate, minimum. Solved: months left, payoff, interest.
    // The eighth cell comes from the plan and is neither — which is what makes the count add up.
    expect(text(fixture)).toContain('4 entered · 3 solved');
  });

  it('never marks the balance as solved, whatever else is', async () => {
    const fixture = await render();

    expect(cellFor(fixture, 'Balance now').querySelector('.tag')).toBeNull();
    expect(text(fixture)).toContain('The balance is never worked out from the other figures');
  });

  it('says a rate is not available rather than printing 0%, and tags it with nothing', async () => {
    const noRate: Debt = { ...VISA, apr: null, aprDerivable: false, aprSource: 'SOLVED' };
    const fixture = await render({ detail: detailOf(noRate) });

    const cell = cellFor(fixture, 'Annual rate');
    expect(cell.textContent).toContain('Rate not available');
    // `Solved` means "this was worked out", and there is nothing here to have been worked out.
    expect(cell.querySelector('.tag')).toBeNull();
    expect(text(fixture)).not.toContain('0%');
  });

  it('shows an absent starting balance as absent, not as zero', async () => {
    const fixture = await render({
      detail: detailOf({
        ...VISA,
        originalBalance: null,
        paidOffSoFar: null,
        progressPercent: null,
      }),
    });

    expect(cellFor(fixture, 'What it started at').textContent).toContain('Not recorded');
    expect(text(fixture)).toContain('No starting balance recorded');
    expect(text(fixture)).not.toContain('0% of');
  });

  it('labels the second line as the comparison, not as the plan', async () => {
    const fixture = await render();

    // The at-minimum line is a projection of a payment the household is NOT making. It is the
    // figure deliberately kept off the plan page's "Clears" column.
    expect(text(fixture)).toContain('At the minimum — the comparison');
    expect(text(fixture)).toContain('kinder to the minimum than reality is');
  });

  it('says in months what the extra is buying, and invents no money saving', async () => {
    const fixture = await render();

    // Both ends read the same way — the month BEFORE the first zero — so the pace side comes back
    // as 2027-12, which is this debt's own `payoffMonth`, and the comparison as 2031-01. Naming
    // the months the series' last points carry would put both a month late.
    expect(text(fixture)).toContain('£260.00 extra is buying you 37 months');
    expect(text(fixture)).toContain('December 2027 instead of January 2031');
    // The money difference would be a subtraction of two interest totals, and the API carries
    // only one of them.
    expect(text(fixture)).not.toContain('saving');
  });

  it('draws one line and says nothing about the extra when the minimum IS the plan', async () => {
    const fixture = await render({
      detail: detailOf({ ...VISA, planExtra: '0.0000' }, { atMinimumIsTheSameLine: true }),
    });
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelectorAll('polyline')).toHaveLength(1);
    expect(text(fixture)).not.toContain('is buying you');
  });

  it('says how every payment got here, and tells a correction from a payment', async () => {
    const fixture = await render();

    const shown = text(fixture);
    expect(shown).toContain('You logged it');
    expect(shown).toContain('Statement correction');
    expect(shown).toContain('1 you logged · 1 from a statement');
    // The split as it was recorded, not recomputed.
    expect(shown).toContain('£123.73');
    expect(shown).toContain('£321.27');
    expect(shown).toContain('£6,162.62');
    expect(shown).toContain('Paid from Everyday Checking');
  });

  it('shows the history in the order the server sent it, sorting nothing itself', async () => {
    // Two rows can share a date — a payment and the statement correction that followed it — and
    // only the server knows which came first. Re-sorting here by date would put them back in an
    // arbitrary order and "Balance after" would stop reconciling down from the headline.
    const older: DebtPayment = {
      ...LOGGED,
      id: 'pay-3',
      paidDate: '2026-07-20',
      amount: '440.0000',
    };
    const fixture = await render({ payments: [CORRECTION, LOGGED, older] });

    const paid = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.history-table tbody tr'),
    ).map((row) => row.querySelector('td')?.textContent?.trim());
    // Newest first, and the same-day pair in the order the server chose — not the order a date
    // sort would leave them in, which for two equal dates is whatever the input happened to be.
    expect(paid).toEqual(['£23.99', '£445.00', '£440.00']);
  });

  it('explains an empty history rather than showing an empty table', async () => {
    const fixture = await render({ payments: [] });

    expect(text(fixture)).toContain('Nothing recorded against this one yet');
    expect((fixture.nativeElement as HTMLElement).querySelector('.history-table')).toBeNull();
  });

  it('gives a VIEWER every figure and not one control', async () => {
    const fixture = await render({ role: 'VIEWER' });
    const root = fixture.nativeElement as HTMLElement;

    expect(text(fixture)).toContain('£6,180.00');
    expect(text(fixture)).toContain('22.9%');
    expect(text(fixture)).toContain('You logged it');

    // Absent, not disabled-looking.
    expect(Array.from(root.querySelectorAll('button')).map((b) => b.textContent?.trim())).toEqual(
      [],
    );
  });

  it('logs a payment with what was paid, the date and the account it left', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    debtsService.logPayment.mockReturnValue(of(LOGGED));

    buttonNamed(root, 'Log a payment').click();
    await settle(fixture);

    // Prefilled with what the plan puts against this debt, written the way somebody about to
    // edit it would write it: the same figure, without the wire's trailing zeros.
    expect(root.querySelector<HTMLInputElement>('#payment-amount')?.value).toBe('445');

    const account = root.querySelector<HTMLSelectElement>('#payment-account');
    account!.value = 'acc-1';
    account!.dispatchEvent(new Event('change', { bubbles: true }));
    await settle(fixture);

    buttonNamed(root, 'Record it').click();
    await settle(fixture);

    expect(debtsService.logPayment).toHaveBeenCalledWith('deb-0001', {
      paidDate: expect.any(String),
      amount: '445',
      paidFromAccountId: 'acc-1',
    });
  });

  it('reports the server’s split rather than predicting one of its own', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    debtsService.logPayment.mockReturnValue(of(LOGGED));

    buttonNamed(root, 'Log a payment').click();
    await settle(fixture);

    // Before: a promise, not a figure. A second implementation of `balance × apr ÷ 1200` here is
    // the one place the client and the server could disagree, so the sheet predicts nothing.
    const sheet = root.querySelector('dialog');
    expect(sheet?.textContent).toContain('works out how much of this went on interest');
    expect(sheet?.textContent).not.toContain('interest ·');

    buttonNamed(root, 'Record it').click();
    await settle(fixture);

    // After: the server's own figures.
    expect(text(fixture)).toContain('£123.73 interest · £321.27 principal');
    expect(text(fixture)).toContain('Balance now £6,162.62');
  });

  it('offers what is scheduled and fifty more, and submits neither on its own', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    debtsService.logPayment.mockReturnValue(of(LOGGED));

    buttonNamed(root, 'Log a payment').click();
    await settle(fixture);

    buttonNamed(root, '£495.00').click();
    await settle(fixture);

    expect(root.querySelector<HTMLInputElement>('#payment-amount')?.value).toBe('495.0000');
    // Filling a field is not recording a payment.
    expect(debtsService.logPayment).not.toHaveBeenCalled();
  });

  it('keeps the sheet open with an explanation when a write is refused', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    debtsService.logPayment.mockReturnValue(throwError(() => ({ status: 409 })));

    buttonNamed(root, 'Log a payment').click();
    await settle(fixture);
    buttonNamed(root, 'Record it').click();
    await settle(fixture);

    expect(root.querySelector('dialog')).not.toBeNull();
    expect(root.querySelector('dialog [role="alert"]')).not.toBeNull();
  });

  it('offers to confirm an estimate, saying what it assumed', async () => {
    const estimated: Debt = {
      ...VISA,
      balance: '9003.0000',
      balanceSource: 'ESTIMATED',
      estimatedMonths: 3,
      anchorBalance: '9254.0000',
      anchorDate: '2026-06-01',
    };
    const fixture = await render({ detail: detailOf(estimated) });
    const root = fixture.nativeElement as HTMLElement;
    debtsService.confirmBalance.mockReturnValue(of(detailOf(VISA)));

    expect(text(fixture)).toContain('estimated — not confirmed');
    buttonNamed(root, 'Confirm the balance').click();
    await settle(fixture);

    // It says the interest in between was added, which the designs' own figure forgot.
    expect(text(fixture)).toContain('3 months of £185.00 payments');
    expect(text(fixture)).toContain('interest in between was added');
    expect(text(fixture)).toContain('Last confirmed: £9,254.00');

    buttonNamed(root, 'Confirm £9,003.00').click();
    await settle(fixture);

    expect(debtsService.confirmBalance).toHaveBeenCalledWith('deb-0001', {
      balance: '9003',
      balanceDate: expect.any(String),
    });
  });

  it('re-anchors on the last confirmed figure when asked to stop estimating', async () => {
    const estimated: Debt = {
      ...VISA,
      balance: '9003.0000',
      balanceSource: 'ESTIMATED',
      estimatedMonths: 3,
      anchorBalance: '9254.0000',
      anchorDate: '2026-06-01',
    };
    const fixture = await render({ detail: detailOf(estimated) });
    const root = fixture.nativeElement as HTMLElement;
    debtsService.confirmBalance.mockReturnValue(of(detailOf(VISA)));

    buttonNamed(root, 'Confirm the balance').click();
    await settle(fixture);
    buttonNamed(root, 'Stop estimating').click();
    await settle(fixture);

    // The figure the household themselves last asserted, not the one the app extrapolated.
    expect(debtsService.confirmBalance).toHaveBeenCalledWith('deb-0001', {
      balance: '9254.0000',
      balanceDate: expect.any(String),
    });
  });

  it('lets somebody correct the estimate rather than confirm it', async () => {
    const estimated: Debt = {
      ...VISA,
      balance: '9003.0000',
      balanceSource: 'ESTIMATED',
      estimatedMonths: 3,
    };
    const fixture = await render({ detail: detailOf(estimated) });
    const root = fixture.nativeElement as HTMLElement;
    debtsService.confirmBalance.mockReturnValue(of(detailOf(VISA)));

    buttonNamed(root, 'Confirm the balance').click();
    await settle(fixture);
    buttonNamed(root, 'Correct it').click();
    await settle(fixture);

    type(root, '#estimate-balance', '8900.00');
    buttonNamed(root, 'Save the balance').click();
    await settle(fixture);

    expect(debtsService.confirmBalance).toHaveBeenCalledWith('deb-0001', {
      balance: '8900.00',
      balanceDate: expect.any(String),
    });
  });

  it('asks for a statement instead of a year of extrapolation', async () => {
    // Past twelve months the server stops extrapolating: the figure reverts to the anchor and
    // comes back CONFIRMED, with `balanceStale` as the flag. The staleness is the flag, not the
    // source — reading the source alone would show a fourteen-month-old figure as fresh.
    const stale: Debt = {
      ...VISA,
      balance: '860.0000',
      balanceSource: 'CONFIRMED',
      balanceStale: true,
      estimatedMonths: 14,
      anchorBalance: '860.0000',
      anchorDate: '2025-06-20',
    };
    const fixture = await render({ detail: detailOf(stale) });

    const shown = text(fixture);
    expect(shown).toContain('last confirmed over a year ago');
    expect(shown).toContain('rather than a year of guesses');
    expect(shown).not.toMatch(/Confirmed 20 Jun 2025\./);
  });

  it('offers to confirm a stale balance even though its source reads CONFIRMED', async () => {
    const stale: Debt = {
      ...VISA,
      balance: '860.0000',
      balanceSource: 'CONFIRMED',
      balanceStale: true,
      estimatedMonths: 14,
      anchorBalance: '860.0000',
      anchorDate: '2025-06-20',
    };
    const fixture = await render({ detail: detailOf(stale) });
    const root = fixture.nativeElement as HTMLElement;

    buttonNamed(root, 'Confirm the balance').click();
    await settle(fixture);

    expect(text(fixture)).toContain('Time for a statement');
    expect(text(fixture)).toContain('14 months with nothing confirmed');
  });

  it('describes an estimate as rolled forward, not as one that only grows', async () => {
    // The estimate takes the minimum off as well as adding the interest, so it can be LOWER than
    // the anchor — $1,196.63 against $1,240.00 — and wording that implied growth would be wrong.
    const estimated: Debt = {
      ...VISA,
      balance: '1196.6271',
      balanceSource: 'ESTIMATED',
      estimatedMonths: 3,
      anchorBalance: '1240.0000',
      anchorDate: '2026-05-20',
      minimumPayment: '45.0000',
    };
    const fixture = await render({ detail: detailOf(estimated) });

    const shown = text(fixture);
    expect(shown).toContain('£1,196.63');
    expect(shown).toContain('assuming the minimum was paid and the interest in between was added');
    expect(shown).not.toMatch(/grown|grows|added to/i);
  });

  it('says why there is no line rather than drawing an axis round an empty box', async () => {
    // A payment that never covers the interest has no projection and therefore no series.
    const underwater: Debt = {
      ...VISA,
      payoffMonth: null,
      monthsLeft: null,
      totalInterestLeft: null,
      projectionReason: 'never-clears',
    };
    const fixture = await render({
      detail: detailOf(underwater, { pace: [], atMinimum: [], atMinimumIsTheSameLine: true }),
    });
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('svg')).toBeNull();
    // And no legend keying lines that were not drawn.
    expect(root.querySelector('.chart-legend')).toBeNull();
    expect(text(fixture)).toContain('There is no line to draw');
    expect(text(fixture)).toContain('the payment is less than the interest');
    // The terms panel says the same thing in its own cells rather than leaving them blank — and
    // the interest cell answers "how much", not "when".
    expect(cellFor(fixture, 'Gone by').textContent).toContain('Never, at this payment');
    expect(cellFor(fixture, 'Months left').textContent).toContain('Never, at this payment');
    expect(cellFor(fixture, 'Interest still to come').textContent).toContain('never clears');
    expect(cellFor(fixture, 'Interest still to come').textContent).not.toContain('Never, at this');
  });

  it('offers no estimate controls on a confirmed balance', async () => {
    const fixture = await render();

    expect(text(fixture)).not.toContain('Confirm the balance');
  });

  it('re-reads when the switcher moves to another debt on the same route', async () => {
    // Same route, different parameter: the component is reused and only the id changes. Without
    // reacting to it the URL says one debt and the heading shows the one you just left.
    const route = { paramMap: new BehaviorSubject(new Map([['id', 'deb-0001']])) };
    const fixture = await render({ route });

    expect(debtsService.get).toHaveBeenCalledWith('deb-0001');

    debtsService.get.mockReturnValue(of(detailOf(CAR)));
    route.paramMap.next(new Map([['id', 'deb-0002']]));
    await settle(fixture);

    expect(debtsService.get).toHaveBeenLastCalledWith('deb-0002');
    expect(text(fixture)).toContain('Car loan');
  });

  it('steps between debts by id, and never by a name in the URL', async () => {
    const fixture = await render();
    const links = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('.switcher a'),
    );

    expect(links.map((link) => link.textContent?.trim())).toEqual(['1 Visa', '2 Car loan']);
    expect(links[1].getAttribute('href')).toBe('/debt-plan/deb-0002');
    expect(links[0].getAttribute('aria-current')).toBe('page');
  });
});
