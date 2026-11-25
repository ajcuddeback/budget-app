import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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
import { Goal, ProjectionReason } from '../data/goal.models';
import { GoalsService } from '../data/goals.service';
import { GoalsPage } from './goals.page';

/** The designs' Japan goal, looked at from August 2026. */
const JAPAN: Goal = {
  id: 'gol-0001',
  name: 'Japan',
  currency: 'GBP',
  targetAmount: '3500.0000',
  targetMonth: '2027-11',
  startingAmount: '0.0000',
  saved: '890.0000',
  remaining: '2610.0000',
  reached: false,
  overdue: false,
  accountId: null,
  thisPeriod: {
    period: '2026-08',
    planned: '110.0000',
    recorded: '0.0000',
    stillToCome: '110.0000',
  },
  monthlyContribution: '110.0000',
  projection: {
    projectedMonth: '2028-07',
    projectedReason: null,
    requiredMonthly: '163.13',
    requiredReason: null,
  },
  archived: false,
  createdAt: '2026-01-05T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
  version: 4,
};

const FUND: Goal = {
  ...JAPAN,
  id: 'gol-0002',
  name: 'Emergency fund',
  targetMonth: null,
  targetAmount: '6000.0000',
  saved: '2340.0000',
  remaining: '3660.0000',
  monthlyContribution: '200.0000',
  projection: {
    projectedMonth: '2028-03',
    projectedReason: null,
    requiredMonthly: null,
    requiredReason: 'no-target-month',
  },
};

const REASONS: readonly ProjectionReason[] = [
  'no-contribution',
  'beyond-horizon',
  'reached',
  'no-target-month',
  'target-month-passed',
  'not-reachable-by-target-month',
];

describe('GoalsPage', () => {
  const goalsService = {
    list: vi.fn(),
    create: vi.fn(),
    previewDraft: vi.fn(),
  };
  const accountsService = { list: vi.fn() };

  async function render(
    options: { goals?: readonly Goal[]; role?: HouseholdRole } = {},
  ): Promise<ComponentFixture<GoalsPage>> {
    for (const fn of [...Object.values(goalsService), accountsService.list]) {
      fn.mockReset();
    }
    const goals = options.goals ?? [JAPAN, FUND];
    goalsService.list.mockReturnValue(
      of({ content: [...goals], page: 0, size: 200, totalElements: goals.length, totalPages: 1 }),
    );
    accountsService.list.mockReturnValue(
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
            household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
          }),
        },
        { provide: GoalsService, useValue: goalsService },
        { provide: AccountsService, useValue: accountsService },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(GoalsPage);
    await settle(fixture);
    return fixture;
  }

  const root = (fixture: ComponentFixture<GoalsPage>): HTMLElement =>
    fixture.nativeElement as HTMLElement;
  const text = (fixture: ComponentFixture<GoalsPage>): string => root(fixture).textContent ?? '';
  const fills = (fixture: ComponentFixture<GoalsPage>): string[] =>
    [...root(fixture).querySelectorAll<HTMLElement>('.goal-bar-fill')].map(
      (bar) => bar.style.inlineSize,
    );

  it('shows saved of target as a sentence, with the month composed beside the name', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain('Japan, November 2027');
    expect(text(fixture)).toContain('£890.00 of £3,500.00');
    expect(text(fixture)).not.toContain('£890.00 / £3,500.00');
  });

  it('puts no month in the label of a goal that has none', async () => {
    const fixture = await render({ goals: [FUND] });

    expect(text(fixture)).toContain('Emergency fund');
    expect(text(fixture)).not.toContain('Emergency fund,');
  });

  it('says the projection in words, in the server’s figures', async () => {
    const fixture = await render({ goals: [JAPAN] });

    expect(text(fixture)).toContain(
      'At £110.00 a month you reach it in July 2028 — 8 months after November 2027.',
    );
    expect(text(fixture)).toContain('£163.13 a month gets you there by November 2027.');
  });

  it('renders a sentence for every reason code and never a blank', async () => {
    for (const reason of REASONS) {
      const fixture = await render({
        goals: [
          {
            ...JAPAN,
            overdue: reason === 'target-month-passed',
            projection: {
              projectedMonth: null,
              projectedReason: reason,
              requiredMonthly: null,
              requiredReason: reason,
            },
          },
        ],
      });
      const lines = [...root(fixture).querySelectorAll('.goal-said p')].map(
        (line) => line.textContent?.trim() ?? '',
      );
      expect(lines.length, reason).toBeGreaterThan(0);
      expect(
        lines.every((line) => line.length > 0 && !line.includes('goals.')),
        reason,
      ).toBe(true);
    }
  });

  it('fills the bar past 100% and still states the real figure', async () => {
    const fixture = await render({
      goals: [{ ...JAPAN, saved: '4000.0000', remaining: '-500.0000', reached: true }],
    });

    expect(fills(fixture)).toEqual(['100%']);
    expect(text(fixture)).toContain('£4,000.00 of £3,500.00');
    expect(text(fixture)).toContain('Reached.');
  });

  it('renders a negative figure as negative, with an empty bar', async () => {
    const fixture = await render({ goals: [{ ...JAPAN, saved: '-120.0000' }] });

    expect(fills(fixture)).toEqual(['0%']);
    expect(text(fixture)).toContain('-£120.00 of £3,500.00');
    expect(root(fixture).querySelector('.goal-figure.is-negative')).not.toBeNull();
  });

  it('keeps the bar out of the accessibility tree; the sentence is its equivalent', async () => {
    const fixture = await render({ goals: [JAPAN] });

    expect(root(fixture).querySelector('.goal-bar')?.getAttribute('aria-hidden')).toBe('true');
    expect(root(fixture).querySelector('meter')).toBeNull();
  });

  it('offers Add a goal on an empty page, and never a $0 of $0 bar', async () => {
    const fixture = await render({ goals: [] });

    expect(text(fixture)).toContain('Nothing you are saving for yet');
    expect(buttonNamed(root(fixture), 'Add a goal')).toBeTruthy();
    expect(root(fixture).querySelector('.goal-bar')).toBeNull();
    expect(text(fixture)).not.toContain('£0.00 of £0.00');
  });

  it('gives a VIEWER every figure and not one control', async () => {
    const fixture = await render({ role: 'VIEWER' });

    expect(text(fixture)).toContain('£890.00 of £3,500.00');
    expect(text(fixture)).toContain('£2,340.00 of £6,000.00');
    expect(root(fixture).querySelectorAll('button.btn-primary')).toHaveLength(0);
    expect(text(fixture)).not.toContain('Add a goal');
  });

  it('groups by currency only when there is more than one, and totals nothing', async () => {
    const single = await render();
    expect(text(single)).not.toContain('Goals in');

    const fixture = await render({ goals: [JAPAN, { ...FUND, currency: 'EUR' }] });
    expect(text(fixture)).toContain('Goals in GBP');
    expect(text(fixture)).toContain('Goals in EUR');
    expect(text(fixture)).not.toMatch(/total saved/i);
  });

  it('keeps archived goals behind a toggle, in their own section', async () => {
    const fixture = await render({ goals: [JAPAN, { ...FUND, archived: true }] });

    expect(text(fixture)).not.toContain('Emergency fund');
    buttonNamed(root(fixture), 'Show archived goals').click();
    await settle(fixture);
    expect(root(fixture).querySelector('#goals-archived')?.textContent).toContain('Emergency fund');
  });

  it('drops a preview answer that arrives after a newer question', async () => {
    const fixture = await render();
    const slow = new Subject<unknown>();
    const quick = new Subject<unknown>();
    goalsService.previewDraft.mockReturnValueOnce(slow).mockReturnValueOnce(quick);

    buttonNamed(root(fixture), 'Add a goal').click();
    await settle(fixture);
    type(root(fixture), '#goal-target', '3500');
    await new Promise((resolve) => setTimeout(resolve, 400));
    type(root(fixture), '#goal-monthly', '110');
    await new Promise((resolve) => setTimeout(resolve, 400));

    quick.next({
      ...JAPAN,
      projection: { ...JAPAN.projection, projectedMonth: '2029-01', requiredMonthly: null },
    });
    quick.complete();
    slow.next({ ...JAPAN, projection: { ...JAPAN.projection, projectedMonth: '2031-05' } });
    slow.complete();
    await settle(fixture);

    const status = root(fixture).querySelector('dialog [role="status"]')?.textContent ?? '';
    expect(status).toContain('January 2029');
    expect(status).not.toContain('May 2031');
  });

  it('never blocks saving when the preview fails', async () => {
    const fixture = await render();
    goalsService.previewDraft.mockReturnValue(of('not a projection'));
    goalsService.create.mockReturnValue(of(JAPAN));

    buttonNamed(root(fixture), 'Add a goal').click();
    await settle(fixture);
    type(root(fixture), '#goal-name', 'Japan');
    type(root(fixture), '#goal-target', '3500');
    await new Promise((resolve) => setTimeout(resolve, 400));
    await settle(fixture);
    expect(text(fixture)).toContain('The figures could not be worked out just now');

    root(fixture).querySelector<HTMLFormElement>('dialog form')?.requestSubmit();
    await settle(fixture);

    expect(goalsService.create).toHaveBeenCalledWith({ name: 'Japan', targetAmount: '3500' });
  });
});
