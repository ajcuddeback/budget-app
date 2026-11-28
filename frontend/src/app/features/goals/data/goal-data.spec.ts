import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Goal, ProjectionReason, projectedReasonKey, requiredReasonKey } from './goal.models';
import {
  GoalFormValue,
  buildContribution,
  buildCreateGoal,
  buildDraftPreview,
  buildEditPreview,
  buildGoalPatch,
  monthsBetween,
  nextMonth,
  patchIsEmpty,
  projectionSaid,
} from './goal-patch';
import { GoalsService } from './goals.service';

/** The designs' Japan goal, looked at from August 2026. */
const JAPAN: Goal = {
  id: 'gol-0001',
  name: 'Japan',
  currency: 'USD',
  targetAmount: '3500.0000',
  targetMonth: '2027-11',
  startingAmount: '0.0000',
  saved: '890.0000',
  remaining: '2610.0000',
  reached: false,
  overdue: false,
  accountId: 'acc-0002',
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

const FORM: GoalFormValue = {
  name: 'Japan',
  targetAmount: '3500',
  targetMonth: '2027-11',
  startingAmount: '0',
  monthlyContribution: '',
  currency: '',
  accountId: 'acc-0002',
};

/** Formatters that show exactly what they were given, so a test reads the server's own figures. */
const VERBATIM = {
  money: (amount: string, currency: string) => `${currency} ${amount}`,
  month: (period: string) => `[${period}]`,
};

const REASONS: readonly ProjectionReason[] = [
  'no-contribution',
  'beyond-horizon',
  'reached',
  'no-target-month',
  'target-month-passed',
  'not-reachable-by-target-month',
];

describe('goal requests', () => {
  it('sends amounts as the strings they were typed as, never as numbers', () => {
    const request = buildCreateGoal({
      ...FORM,
      targetAmount: ' 3500.10 ',
      startingAmount: '890',
      monthlyContribution: '110.00',
      accountId: '',
      currency: 'JPY',
    });

    expect(request).toEqual({
      name: 'Japan',
      targetAmount: '3500.10',
      targetMonth: '2027-11',
      startingAmount: '890',
      monthlyContribution: '110.00',
      currency: 'JPY',
    });
    expect(typeof request.targetAmount).toBe('string');
  });

  it('never sends a currency beside an account, which owns that fact', () => {
    const request = buildCreateGoal({ ...FORM, currency: 'EUR' });

    expect(request.accountId).toBe('acc-0002');
    expect('currency' in request).toBe(false);
  });

  it('leaves an untouched month and account out of a PATCH entirely', () => {
    const patch = buildGoalPatch(JAPAN, { ...FORM, name: 'Japan trip' });

    expect(patch).toEqual({ name: 'Japan trip', version: 4 });
    expect('targetMonth' in patch).toBe(false);
    expect('accountId' in patch).toBe(false);
  });

  it('sends null to clear a month or an account, which is a different request from leaving it', () => {
    const patch = buildGoalPatch(JAPAN, { ...FORM, targetMonth: '', accountId: '' });

    expect(patch).toEqual({ targetMonth: null, accountId: null, version: 4 });
  });

  it('treats the same figure at another scale as no change', () => {
    const patch = buildGoalPatch(JAPAN, { ...FORM, targetAmount: '3500.00' });

    expect(patchIsEmpty(patch)).toBe(true);
  });

  it('signs a withdrawal by the dialog, not by a minus typed into the field', () => {
    expect(buildContribution({ amount: '500', date: '2026-08-20', note: '' }, 'out')).toEqual({
      amount: '-500',
      date: '2026-08-20',
    });
    expect(
      buildContribution({ amount: '110', date: '2026-08-20', note: ' birthday ' }, 'in'),
    ).toEqual({ amount: '110', date: '2026-08-20', note: 'birthday' });
  });

  it('asks nothing while an amount is half-typed', () => {
    expect(buildDraftPreview({ ...FORM, targetAmount: '35.' })).toBeNull();
    expect(buildEditPreview(JAPAN, { ...FORM, startingAmount: 'abc' })).toBeNull();
    expect(
      buildDraftPreview({ ...FORM, targetAmount: '3500', monthlyContribution: '1.' }),
    ).toBeNull();
  });

  it('previews an edit with only what was changed substituted, and null to clear the month', () => {
    expect(buildEditPreview(JAPAN, { ...FORM, targetAmount: '4000', targetMonth: '' })).toEqual({
      targetAmount: '4000',
      targetMonth: null,
    });
    expect(buildEditPreview(JAPAN, FORM)).toEqual({});
  });

  it('counts months by the calendar', () => {
    expect(monthsBetween('2027-11', '2028-07')).toBe(8);
    expect(monthsBetween('2027-11', 'not a month')).toBeNull();
    expect(nextMonth('2026-12')).toBe('2027-01');
  });
});

describe('the projection in words', () => {
  it('states the server’s figures and works none of them out', () => {
    // Figures that do not reconcile with each other: the sentence repeats them anyway, because
    // the browser does not compute a projection and must not "correct" one.
    const said = projectionSaid(
      {
        ...JAPAN,
        monthlyContribution: '1.0000',
        projection: { ...JAPAN.projection, requiredMonthly: '999.99' },
      },
      VERBATIM,
      'one-amount',
    );

    expect(said).toEqual([
      {
        key: 'goals.projection.late',
        params: { monthly: 'USD 1.0000', month: '[2028-07]', count: 8, target: '[2027-11]' },
      },
      { key: 'goals.projection.required', params: { amount: 'USD 999.99', target: '[2027-11]' } },
    ]);
  });

  it('gives every reason code its own sentence and never a blank', () => {
    for (const reason of REASONS) {
      expect(projectedReasonKey(reason)).toMatch(/^goals\./);
      expect(requiredReasonKey(reason)).toMatch(/^goals\./);
      const said = projectionSaid(
        {
          ...JAPAN,
          reached: false,
          overdue: reason === 'target-month-passed',
          projection: {
            projectedMonth: null,
            projectedReason: reason,
            requiredMonthly: null,
            requiredReason: reason,
          },
        },
        VERBATIM,
        'one-amount',
      );
      expect(said.length).toBeGreaterThan(0);
    }
  });

  it('says reached and nothing else for a reached goal', () => {
    expect(projectionSaid({ ...JAPAN, reached: true }, VERBATIM, 'one-amount')).toEqual([
      { key: 'goals.reason.reached' },
    ]);
  });

  it('says how long it has been overdue and still when it will get there', () => {
    const said = projectionSaid(
      { ...JAPAN, overdue: true, targetMonth: '2026-06' },
      VERBATIM,
      'one-amount',
    );

    expect(said[0]).toEqual({
      key: 'goals.projection.overdueReaches',
      params: { target: '[2026-06]', monthly: 'USD 110.0000', month: '[2028-07]' },
    });
  });

  // The walk uses every step. With $165 from September, Japan's month is December 2027 at $110 and
  // then $165, so "At $110 a month you reach it in December 2027" would be false. The capture for
  // the user guide showed exactly that sentence before this.
  it('does not name this month’s amount when a later step may change it', () => {
    const late = projectionSaid({ ...JAPAN }, VERBATIM, 'may-change');
    expect(late[0]).toEqual({
      key: 'goals.projection.planLate',
      params: { monthly: 'USD 110.0000', month: '[2028-07]', count: 8, target: '[2027-11]' },
    });

    const onTime = projectionSaid(
      { ...JAPAN, projection: { ...JAPAN.projection, projectedMonth: '2027-10' } },
      VERBATIM,
      'may-change',
    );
    expect(onTime[0].key).toBe('goals.projection.planOnTime');

    const noMonth = projectionSaid({ ...JAPAN, targetMonth: null }, VERBATIM, 'may-change');
    expect(noMonth[0].key).toBe('goals.projection.planReaches');

    const overdue = projectionSaid(
      { ...JAPAN, overdue: true, targetMonth: '2026-06' },
      VERBATIM,
      'may-change',
    );
    expect(overdue[0].key).toBe('goals.projection.planOverdueReaches');
  });
});

describe('GoalsService', () => {
  let http: HttpTestingController;
  let service: GoalsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(GoalsService);
  });

  afterEach(() => {
    http.verify();
  });

  it('sends both previews as POSTs, with the amount in the body and never in the URL', () => {
    service.preview('gol-0001', { monthlyAmount: '165.00' }).subscribe();
    service.previewDraft({ targetAmount: '3500', monthlyAmount: '110' }).subscribe();

    const bound = http.expectOne(
      (request) => request.url.endsWith('/projection-preview') && request.url.includes('gol-0001'),
    );
    const draft = http.expectOne('/api/goals/projection-preview');

    for (const request of [bound, draft]) {
      expect(request.request.method).toBe('POST');
      expect(request.request.urlWithParams).not.toMatch(/165|3500|110/);
      expect(request.request.params.keys()).toEqual([]);
    }
    expect(bound.request.body).toEqual({ monthlyAmount: '165.00' });
    expect(draft.request.body).toEqual({ targetAmount: '3500', monthlyAmount: '110' });
    bound.flush({});
    draft.flush({});
  });

  it('puts the plan’s month in the path and its amount in the body', () => {
    service.setPlan('gol-0001', '2026-09', { monthlyAmount: '165', version: 4 }).subscribe();

    const request = http.expectOne('/api/goals/gol-0001/contribution-plan/2026-09');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ monthlyAmount: '165', version: 4 });
    request.flush(JAPAN);
  });
});
