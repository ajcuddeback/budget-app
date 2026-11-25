import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { HouseholdRole } from '../../../core/auth.models';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  OWNER,
  OWNER_MEMBERSHIP,
  buttonNamed,
  fakeAuth,
  settle,
  type,
} from '../../../testing/helpers';
import { AccountsService } from '../../accounts/data/accounts.service';
import { Contribution, Goal, PlanStep } from '../data/goal.models';
import { GoalsService } from '../data/goals.service';
import { GoalDetailPage } from './goal-detail.page';

/** The designs' emergency fund: no month, part-funded on day one, kept in a savings account. */
const FUND: Goal = {
  id: 'gol-0002',
  name: 'Emergency fund',
  currency: 'GBP',
  targetAmount: '6000.0000',
  targetMonth: null,
  startingAmount: '2000.0000',
  saved: '2340.0000',
  remaining: '3660.0000',
  reached: false,
  overdue: false,
  accountId: 'acc-2',
  thisPeriod: {
    period: '2026-08',
    planned: '200.0000',
    recorded: '50.0000',
    stillToCome: '150.0000',
  },
  monthlyContribution: '200.0000',
  projection: {
    projectedMonth: '2028-03',
    projectedReason: null,
    requiredMonthly: null,
    requiredReason: 'no-target-month',
  },
  archived: false,
  createdAt: '2026-01-05T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
  version: 7,
};

const IN: Contribution = {
  id: 'con-1',
  goalId: 'gol-0002',
  date: '2026-08-02',
  period: '2026-08',
  amount: '390.0000',
  currency: 'GBP',
  note: 'birthday money',
  transactionId: null,
  createdAt: '2026-08-02T09:00:00Z',
  updatedAt: '2026-08-02T09:00:00Z',
  version: 0,
};

const OUT: Contribution = {
  ...IN,
  id: 'con-2',
  date: '2026-07-14',
  period: '2026-07',
  amount: '-50.0000',
  note: 'boiler repair',
};

const STEPS: PlanStep[] = [
  { fromPeriod: '2026-01', monthlyAmount: '200.0000', currency: 'GBP' },
  { fromPeriod: '2026-09', monthlyAmount: '250.0000', currency: 'GBP' },
];

const SAVINGS = {
  id: 'acc-2',
  name: 'Rainy-day savings',
  lastFour: null,
  type: 'SAVINGS',
  currency: 'GBP',
  openingBalance: '0.0000',
  balance: '5100.0000',
  archived: false,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 0,
};

describe('GoalDetailPage', () => {
  const goalsService = {
    get: vi.fn(),
    plan: vi.fn(),
    contributions: vi.fn(),
    contribute: vi.fn(),
    removeContribution: vi.fn(),
    update: vi.fn(),
    setPlan: vi.fn(),
    archive: vi.fn(),
    unarchive: vi.fn(),
    remove: vi.fn(),
    preview: vi.fn(),
  };
  const accountsService = { list: vi.fn() };

  async function render(
    options: {
      goal?: Goal;
      role?: HouseholdRole;
      contributions?: Contribution[];
      steps?: PlanStep[];
    } = {},
  ): Promise<ComponentFixture<GoalDetailPage>> {
    for (const fn of [...Object.values(goalsService), accountsService.list]) {
      fn.mockReset();
    }
    const rows = options.contributions ?? [IN, OUT];
    const steps = options.steps ?? STEPS;
    goalsService.get.mockReturnValue(of(options.goal ?? FUND));
    goalsService.plan.mockReturnValue(
      of({ content: steps, page: 0, size: 50, totalElements: steps.length, totalPages: 1 }),
    );
    goalsService.contributions.mockReturnValue(
      of({ content: rows, page: 0, size: 50, totalElements: rows.length, totalPages: 1 }),
    );
    accountsService.list.mockReturnValue(
      of({ content: [SAVINGS], page: 0, size: 200, totalElements: 1, totalPages: 1 }),
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
            household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
          }),
        },
        { provide: GoalsService, useValue: goalsService },
        { provide: AccountsService, useValue: accountsService },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(new Map([['id', 'gol-0002']])) } as unknown as ActivatedRoute,
        },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(GoalDetailPage);
    await settle(fixture);
    return fixture;
  }

  const root = (fixture: ComponentFixture<GoalDetailPage>): HTMLElement =>
    fixture.nativeElement as HTMLElement;
  const text = (fixture: ComponentFixture<GoalDetailPage>): string =>
    root(fixture).textContent ?? '';

  it('labels the starting amount as already saved, and does not make it a history row', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain('Already saved when you started tracking it: £2,000.00');
    const rows = [...root(fixture).querySelectorAll('.history-table tbody tr')];
    expect(rows).toHaveLength(2);
    expect(rows.map((row) => row.textContent).join(' ')).not.toContain('£2,000.00');
  });

  it('says a withdrawal is one in words, and shows it negative', async () => {
    const fixture = await render();

    const withdrawal = [...root(fixture).querySelectorAll('.history-table tbody tr')][1];
    expect(withdrawal.textContent).toContain('-£50.00');
    expect(withdrawal.textContent).toContain('Withdrawal');
    expect(withdrawal.querySelector('.amount.is-negative')).not.toBeNull();
  });

  it('shows the linked account’s balance labelled as the account’s and not the goal’s', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain(
      "Rainy-day savings has £5,100.00. That is the account's balance, not this goal's.",
    );
    expect(text(fixture)).toContain('£2,340.00 of £6,000.00');
  });

  it('shows the plan in force and the step scheduled after it', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain('£200.00 a month in August 2026.');
    expect(text(fixture)).toContain('£250.00 a month from September 2026.');
    // No month means no required figure, said in words rather than left blank.
    expect(text(fixture)).toContain('There is no target month, so there is no monthly figure');
  });

  // The walk uses the later step, so this month's amount alone did not get the goal there.
  it('names the amount only when no later step changes it', async () => {
    const stepped = await render();
    expect(text(stepped)).toContain('Keeping to its plan, you reach it in March 2028.');
    expect(text(stepped)).not.toContain('At £200.00 a month you reach it');

    const steady = await render({ steps: [STEPS[0]] });
    expect(text(steady)).toContain('At £200.00 a month you reach it in March 2028.');
  });

  it('gives a VIEWER every figure and region and not one control', async () => {
    const fixture = await render({ role: 'VIEWER' });

    expect(text(fixture)).toContain('£2,340.00 of £6,000.00');
    expect(text(fixture)).toContain('History');
    expect(text(fixture)).toContain('The plan');
    expect(root(fixture).querySelectorAll('button')).toHaveLength(0);
  });

  it('opens Set money aside with what is still to come this month, and records it', async () => {
    const fixture = await render();
    goalsService.contribute.mockReturnValue(of(IN));

    buttonNamed(root(fixture), 'Set money aside').click();
    await settle(fixture);
    const amount = root(fixture).querySelector<HTMLInputElement>('#contribution-amount');
    expect(amount?.value).toBe('150');
    expect(text(fixture)).toContain('Filled in with what is still to come for August 2026');

    root(fixture).querySelector<HTMLFormElement>('dialog form')?.requestSubmit();
    await settle(fixture);

    expect(goalsService.contribute).toHaveBeenCalledWith(
      'gol-0002',
      expect.objectContaining({ amount: '150' }),
    );
  });

  it('takes money out through its own dialog and sends it negative', async () => {
    const fixture = await render();
    goalsService.contribute.mockReturnValue(of(OUT));

    buttonNamed(root(fixture), 'Take money out').click();
    await settle(fixture);
    type(root(fixture), '#contribution-amount', '500');
    root(fixture).querySelector<HTMLFormElement>('dialog form')?.requestSubmit();
    await settle(fixture);

    expect(goalsService.contribute).toHaveBeenCalledWith(
      'gol-0002',
      expect.objectContaining({ amount: '-500' }),
    );
  });

  it('changes the plan from next month with the goal’s version', async () => {
    const fixture = await render();
    goalsService.preview.mockReturnValue(of(FUND));
    goalsService.setPlan.mockReturnValue(of(FUND));

    buttonNamed(root(fixture), 'Change').click();
    await settle(fixture);
    type(root(fixture), '#plan-amount', '300');
    root(fixture).querySelector<HTMLInputElement>('input[value="next"]')?.click();
    await settle(fixture);
    root(fixture).querySelector<HTMLFormElement>('dialog form')?.requestSubmit();
    await settle(fixture);

    expect(goalsService.setPlan).toHaveBeenCalledWith('gol-0002', '2026-09', {
      monthlyAmount: '300',
      version: 7,
    });
  });

  it('drops a plan preview that arrives after a newer one', async () => {
    const fixture = await render();
    const slow = new Subject<unknown>();
    const quick = new Subject<unknown>();
    goalsService.preview.mockReturnValueOnce(slow).mockReturnValueOnce(quick);

    buttonNamed(root(fixture), 'Change').click();
    await settle(fixture);
    type(root(fixture), '#plan-amount', '400');
    await new Promise((resolve) => setTimeout(resolve, 400));

    quick.next({ ...FUND, projection: { ...FUND.projection, projectedMonth: '2027-05' } });
    quick.complete();
    slow.next({ ...FUND, projection: { ...FUND.projection, projectedMonth: '2031-01' } });
    slow.complete();
    await settle(fixture);

    const status = root(fixture).querySelector('dialog [role="status"]')?.textContent ?? '';
    expect(status).toContain('May 2027');
    expect(status).not.toContain('January 2031');
  });

  it('says reached for a reached goal and offers nothing to aim for', async () => {
    const fixture = await render({
      goal: {
        ...FUND,
        saved: '6250.0000',
        remaining: '-250.0000',
        reached: true,
        projection: {
          projectedMonth: null,
          projectedReason: 'reached',
          requiredMonthly: null,
          requiredReason: 'reached',
        },
      },
    });

    expect(text(fixture)).toContain('Reached.');
    expect(text(fixture)).toContain('Reached, with £250.00 more than the target.');
  });
});
