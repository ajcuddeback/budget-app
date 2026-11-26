import { describe, expect, it } from 'vitest';
import { ApiError } from '../../../core/api-error';
import { MessageKey, MessageParams } from '../../../core/i18n/messages';
import { Choice, Figures, Item, Money, Step, Week } from './check-in.models';
import {
  Formatters,
  choiceEffect,
  dueNextWeekSaid,
  incomeSaid,
  isNegative,
  isPositive,
  isZero,
  magnitude,
  nextWeekFigure,
  progress,
  sameAmount,
  shownSteps,
  staleItems,
  staleSaid,
  stepStatus,
  streakSaid,
  summary,
  weekNumber,
  weekResult,
  withVersion,
} from './check-in-text';

const usd = (amount: string): Money => ({ amount, currency: 'USD' });

/** Formatters that show their inputs, so a test reads which figure went where. */
const f: Formatters = {
  money: (money) => `$${money.amount}`,
  date: (iso) => `<${iso}>`,
  month: (period) => `[${period}]`,
  list: (parts) => parts.join(' & '),
  t: (key: MessageKey, params?: MessageParams) => `${key}${JSON.stringify(params ?? {})}`,
};

/** The canvas's step 4 figures, as the server computed them (CheckInIT reconciles them). */
const CANVAS: Figures = {
  weekTarget: usd('550.0000'),
  weekSpending: usd('487.0000'),
  surplus: usd('63.0000'),
  free: usd('611.5700'),
  base: usd('480.0000'),
  basePerDay: usd('68.5700'),
  rolledOver: usd('543.0000'),
  rolledPerDay: usd('77.5700'),
  surplusAllocatable: true,
  soFar: true,
};

function step(name: Step['step'], overrides: Partial<Step> = {}): Step {
  return {
    step: name,
    answered: false,
    questions: 0,
    answeredCount: 0,
    items: [],
    income: null,
    more: null,
    weekSpending: null,
    bills: null,
    stillToPay: null,
    billChoices: null,
    figures: null,
    choices: null,
    dueNextWeek: null,
    streak: null,
    ...overrides,
  };
}

function week(overrides: Partial<Week> = {}): Week {
  return {
    week: '2026-W34',
    weekStart: '2026-08-17',
    weekEnd: '2026-08-23',
    windowStart: '2026-08-17',
    status: 'DRAFT',
    version: 6,
    basis: 'a'.repeat(64),
    startedBy: null,
    completedAt: null,
    completedBy: null,
    currency: 'USD',
    otherCurrencyRows: 0,
    byRule: { categorized: 0, charges: 0 },
    steps: [],
    snapshot: null,
    outcome: [],
    ...overrides,
  };
}

describe('amounts are inspected, never computed', () => {
  it('reads the sign from the string, and a zero is neither sign', () => {
    expect(isNegative(usd('-31.1000'))).toBe(true);
    expect(isPositive(usd('63.0000'))).toBe(true);
    expect(isZero(usd('-0.0000'))).toBe(true);
    expect(isNegative(usd('-0.0000'))).toBe(false);
    expect(isPositive(usd('0'))).toBe(false);
  });

  it('drops the sign by string surgery and compares exactly at any scale', () => {
    expect(magnitude(usd('-126.0000')).amount).toBe('126.0000');
    expect(sameAmount(usd('2620'), usd('2620.0000'))).toBe(true);
    expect(sameAmount(usd('2620.0001'), usd('2620.0000'))).toBe(false);
    expect(sameAmount(usd('1'), { amount: '1', currency: 'EUR' })).toBe(false);
  });
});

describe('the week', () => {
  it('reads the week number, and nothing from a malformed one', () => {
    expect(weekNumber('2026-W34')).toBe(34);
    expect(weekNumber('2026-W3')).toBe(0);
  });

  it('orders the shown steps as the canvas does, and counts only those', () => {
    const shown = week({
      steps: [step('NEXT_WEEK'), step('WHATS_NEW', { answered: true }), step('CATEGORIZE')],
    });
    expect(shownSteps(shown).map((s) => s.step)).toEqual(['WHATS_NEW', 'CATEGORIZE', 'NEXT_WEEK']);
    expect(progress(shown)).toEqual({ answered: 1, shown: 3 });
  });

  it('says how much of a step is waiting', () => {
    expect(stepStatus(step('BILLS')).key).toBe('checkIn.status.nothingToAnswer');
    expect(stepStatus(step('WHATS_NEW', { questions: 2, answeredCount: 2 })).key).toBe(
      'checkIn.status.bothHandled',
    );
    expect(stepStatus(step('CATEGORIZE', { questions: 3, answeredCount: 3 })).key).toBe(
      'checkIn.status.allHandled',
    );
    expect(stepStatus(step('CATEGORIZE', { questions: 3, answeredCount: 2 })).key).toBe(
      'checkIn.status.waitingOne',
    );
    expect(stepStatus(step('CATEGORIZE', { questions: 3, answeredCount: 0 }))).toEqual({
      key: 'checkIn.status.waiting',
      params: { count: 3 },
    });
  });
});

describe('step 4, in the server’s figures', () => {
  it('names the week’s result from the surplus’s sign, and an overspend by its size', () => {
    expect(weekResult(CANVAS, f)).toEqual({
      key: 'checkIn.next.headingUnder',
      params: { amount: '$63.0000' },
    });
    expect(weekResult({ ...CANVAS, surplus: usd('-40.0000') }, f)).toEqual({
      key: 'checkIn.next.headingOver',
      params: { amount: '$40.0000' },
    });
    expect(weekResult({ ...CANVAS, surplus: usd('0.0000') }, f).key).toBe(
      'checkIn.next.headingOnTarget',
    );
    expect(weekResult({ ...CANVAS, weekTarget: null, surplus: null }, f).key).toBe(
      'checkIn.next.headingFirst',
    );
  });

  it('shows the rolled-over figure only when rolling over is the answer', () => {
    expect(nextWeekFigure(CANVAS, false)).toEqual({
      amount: usd('480.0000'),
      perDay: usd('68.5700'),
    });
    expect(nextWeekFigure(CANVAS, true)).toEqual({
      amount: usd('543.0000'),
      perDay: usd('77.5700'),
    });
  });

  it('describes each choice from its preview, and says nothing moves', () => {
    const goal: Choice = {
      choice: 'GOAL',
      debtId: null,
      debtName: null,
      payoffMonthNow: null,
      payoffMonthWith: null,
      goalId: 'g',
      goalName: 'Emergency fund',
      savedNow: usd('2340.0000'),
      savedWith: usd('2403.0000'),
      target: usd('6000.0000'),
    };
    const [moves, never] = choiceEffect(goal, CANVAS, f);
    expect(moves.params).toEqual({
      goal: 'Emergency fund',
      from: '$2340.0000',
      to: '$2403.0000',
      target: '$6000.0000',
    });
    expect(never.key).toBe('checkIn.choice.goalNeverMoves');

    const debt: Choice = {
      ...goal,
      choice: 'DEBT',
      debtId: 'd',
      debtName: 'Visa •8823',
      goalId: null,
      goalName: null,
      payoffMonthNow: '2027-04',
      payoffMonthWith: '2027-03',
    };
    expect(choiceEffect(debt, CANVAS, f)[0]).toEqual({
      key: 'checkIn.choice.debtMoves',
      params: { debt: 'Visa •8823', from: '[2027-04]', to: '[2027-03]' },
    });
    expect(choiceEffect({ ...debt, payoffMonthWith: '2027-04' }, CANVAS, f)[0].key).toBe(
      'checkIn.choice.debtSameMonth',
    );
    expect(
      choiceEffect({ ...goal, choice: 'ROLL_OVER', goalId: null }, CANVAS, f)[0].params,
    ).toEqual({ amount: '$543.0000' });
  });

  it('shows a streak only from two weeks, and its months only when they differ', () => {
    expect(streakSaid({ weeks: 1, debtFreeFrom: null, debtFreeTo: null }, f)).toBeNull();
    expect(streakSaid({ weeks: 4, debtFreeFrom: null, debtFreeTo: null }, f)?.key).toBe(
      'checkIn.next.streak',
    );
    expect(streakSaid({ weeks: 4, debtFreeFrom: '2027-07', debtFreeTo: '2027-03' }, f)).toEqual({
      key: 'checkIn.next.streakMoved',
      params: { count: 4, from: '[2027-07]', to: '[2027-03]' },
    });
  });

  it('lists the bills due next week through the locale’s list', () => {
    expect(dueNextWeekSaid([], f)).toBeNull();
    const said = dueNextWeekSaid(
      [{ billId: 'b', billName: 'Car payment', dueDate: '2026-08-24' }],
      f,
    );
    expect(String(said?.params?.['bills'])).toContain('Car payment');
  });
});

describe('income is information, compared exactly', () => {
  const line = {
    incomeSourceId: 'i',
    name: 'Northwind Systems',
    expectedDate: '2026-08-20',
    expected: usd('2620.0000'),
    transactionId: 't',
    date: '2026-08-20',
    actual: usd('2620'),
    accountId: null,
    accountName: null,
    accountLastFour: null,
  };

  it('says as expected only for the same amount, and otherwise states the plan', () => {
    expect(incomeSaid(line, f)[1].key).toBe('checkIn.income.asExpected');
    expect(incomeSaid({ ...line, actual: usd('2580.0000') }, f)[1]).toEqual({
      key: 'checkIn.income.expected',
      params: { amount: '$2620.0000' },
    });
    expect(incomeSaid({ ...line, transactionId: null, actual: null, date: null }, f)[0].key).toBe(
      'checkIn.income.notYet',
    );
  });
});

describe('answers', () => {
  it('sends the answer’s version only when it replaces one', () => {
    const item = { answer: null } as unknown as Item;
    expect(withVersion(item, { choice: 'LEAVE' })).toEqual({ choice: 'LEAVE' });
    const answered = { answer: { version: 3 } } as unknown as Item;
    expect(withVersion(answered, { choice: 'LEAVE' })).toEqual({ choice: 'LEAVE', version: 3 });
  });
});

describe('a stale refusal', () => {
  it('reads every item and its reason, and nothing from another error', () => {
    const error = new ApiError(409, 'check-in-stale', {
      items: [
        { itemKey: 'cat.1', reason: 'subject-changed' },
        { reason: 'basis-changed' },
        'nonsense',
      ],
    });
    expect(staleItems(error)).toEqual([
      { itemKey: 'cat.1', reason: 'subject-changed' },
      { itemKey: null, reason: 'basis-changed' },
    ]);
    expect(staleItems(new ApiError(409, 'stale-version', { items: [] }))).toEqual([]);
  });

  it('gives every reason a sentence, and an unknown one the generic sentence', () => {
    for (const reason of [
      'subject-changed',
      'subject-gone',
      'target-archived',
      'already-recorded',
      'bill-inactive',
      'transaction-attached-elsewhere',
      'goal-archived',
      'debt-paid-off',
      'limit-reached',
      'surplus-changed',
      'basis-changed',
    ]) {
      expect(staleSaid(reason).key).toBe(`checkIn.stale.${reason}`);
    }
    expect(staleSaid('from-a-newer-server').key).toBe('checkIn.stale.unknown');
  });
});

describe('the completed summary', () => {
  it('says what was written, linking each record to where it lives', () => {
    const base = {
      byRule: false,
      transactionId: null,
      payeeName: null,
      categoryId: null,
      categoryName: null,
      billId: null,
      billName: null,
      dueDate: null,
      goalId: null,
      goalName: null,
      debtId: null,
      debtName: null,
      amount: null,
      remember: false,
      recordDebtPayment: false,
    };
    const done = week({
      status: 'COMPLETED',
      snapshot: {
        weekTarget: usd('550.0000'),
        weekSpending: usd('487.0000'),
        rolledOver: null,
        nextWeekTarget: usd('480.0000'),
        debtFreeMonth: null,
      },
      outcome: [
        { ...base, kind: 'CATEGORIZE', choice: 'CATEGORY', remember: true },
        { ...base, kind: 'CATEGORIZE', choice: 'CATEGORY', byRule: true },
        { ...base, kind: 'BILL_CHARGE', choice: 'ATTACH', billName: 'Internet', remember: true },
        {
          ...base,
          kind: 'ALLOCATION',
          choice: 'GOAL',
          goalId: 'g',
          goalName: 'Emergency fund',
          amount: usd('63.0000'),
        },
      ],
    });

    const lines = summary(done, f);

    expect(lines.map((line) => line.said.key)).toEqual([
      'checkIn.summary.filed',
      'checkIn.summary.byRule',
      'checkIn.summary.rememberedOne',
      'checkIn.summary.attachedRemembered',
      'checkIn.summary.goal',
      'checkIn.summary.nextWeek',
    ]);
    expect(lines[4].link).toEqual(['/goals', 'g']);
    expect(lines[5].said.params).toEqual({ amount: '$480.0000' });
  });
});
