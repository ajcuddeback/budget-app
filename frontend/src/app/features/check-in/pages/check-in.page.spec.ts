import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { HouseholdRole } from '../../../core/auth.models';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  OWNER,
  OWNER_MEMBERSHIP,
  apiError,
  buttonNamed,
  fakeAuth,
  settle,
} from '../../../testing/helpers';
import { CategoriesService } from '../../categories/data/categories.service';
import { Item, Money, Step, Week } from '../data/check-in.models';
import { CheckInService } from '../data/check-in.service';
import { CheckInPage } from './check-in.page';

const usd = (amount: string): Money => ({ amount, currency: 'USD' });

const ITEM: Item = {
  itemKey: 'cat.t1',
  kind: 'CATEGORIZE',
  question: true,
  transactionId: 't1',
  transactionVersion: 0,
  date: '2026-08-19',
  amount: usd('-18.5000'),
  payeeName: 'SQ *BLUE BOTTLE',
  accountId: 'a1',
  accountName: 'Chase',
  accountLastFour: '4412',
  billId: null,
  billName: null,
  dueDate: null,
  expected: null,
  usual: null,
  multiple: null,
  skips: null,
  reason: null,
  suggestedBillId: null,
  suggestedDueDate: null,
  debtPrompt: null,
  ruleCategoryId: null,
  ruleCategoryName: null,
  preAnswer: null,
  answer: null,
};

const ALLOC: Item = { ...ITEM, itemKey: 'alloc', kind: 'ALLOCATION', payeeName: null };

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

const NEXT_WEEK = step('NEXT_WEEK', {
  questions: 1,
  items: [ALLOC],
  figures: {
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
  },
  choices: [
    {
      choice: 'GOAL',
      debtId: null,
      debtName: null,
      payoffMonthNow: null,
      payoffMonthWith: null,
      goalId: 'g1',
      goalName: 'Emergency fund',
      savedNow: usd('2340.0000'),
      savedWith: usd('2403.0000'),
      target: usd('6000.0000'),
    },
    {
      choice: 'ROLL_OVER',
      debtId: null,
      debtName: null,
      payoffMonthNow: null,
      payoffMonthWith: null,
      goalId: null,
      goalName: null,
      savedNow: null,
      savedWith: null,
      target: null,
    },
  ],
});

const DRAFT: Week = {
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
  steps: [step('CATEGORIZE', { questions: 1, items: [ITEM] }), NEXT_WEEK],
  snapshot: null,
  outcome: [],
};

describe('CheckInPage', () => {
  const service = {
    current: vi.fn(),
    get: vi.fn(),
    start: vi.fn(),
    answer: vi.fn(),
    clearAnswer: vi.fn(),
    complete: vi.fn(),
    discard: vi.fn(),
  };
  const categories = { list: vi.fn() };

  async function render(
    options: { week?: Week; role?: HouseholdRole } = {},
  ): Promise<ComponentFixture<CheckInPage>> {
    for (const fn of [...Object.values(service), categories.list]) {
      fn.mockReset();
    }
    service.current.mockReturnValue(of(options.week ?? DRAFT));
    categories.list.mockReturnValue(
      of({
        content: [
          {
            id: 'c8',
            name: 'Eating Out',
            kind: 'EXPENSE',
            parentId: null,
            parentName: null,
            archived: false,
            createdAt: '',
            updatedAt: '',
            version: 0,
          },
          {
            id: 'c9',
            name: 'Old',
            kind: 'EXPENSE',
            parentId: null,
            parentName: null,
            archived: true,
            createdAt: '',
            updatedAt: '',
            version: 0,
          },
        ],
        page: 0,
        size: 200,
        totalElements: 2,
        totalPages: 1,
      }),
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
            household: { ...OWNER_MEMBERSHIP, baseCurrency: 'USD', role: options.role ?? 'OWNER' },
          }),
        },
        { provide: CheckInService, useValue: service },
        { provide: CategoriesService, useValue: categories },
      ],
    });
    await TestBed.inject(I18nService).use('en-US');
    const fixture = TestBed.createComponent(CheckInPage);
    await settle(fixture);
    return fixture;
  }

  const root = (fixture: ComponentFixture<CheckInPage>): HTMLElement =>
    fixture.nativeElement as HTMLElement;
  const text = (fixture: ComponentFixture<CheckInPage>): string => root(fixture).textContent ?? '';

  it('opens the current week and shows the server’s figure in a live region', async () => {
    const fixture = await render();

    expect(service.current).toHaveBeenCalled();
    expect(text(fixture)).toContain('Check-in for week 34');
    const live = root(fixture).querySelector('[aria-live="polite"]');
    expect(live?.textContent).toContain('$480.00');
    expect(live?.textContent).toContain('≈ $68.57/day');
  });

  it('preselects nothing in step 4', async () => {
    const fixture = await render();

    const radios = [...root(fixture).querySelectorAll<HTMLInputElement>('input[type="radio"]')];
    expect(radios).toHaveLength(2);
    expect(radios.some((radio) => radio.checked)).toBe(false);
  });

  it('renders each payee in its own dir="auto" element', async () => {
    const fixture = await render();

    const payee = [...root(fixture).querySelectorAll('[dir="auto"]')].find((node) =>
      node.textContent?.includes('SQ *BLUE BOTTLE'),
    );
    expect(payee).toBeDefined();
  });

  it('offers only active categories', async () => {
    const fixture = await render();

    const options = [...root(fixture).querySelectorAll('select option')].map((o) =>
      o.textContent?.trim(),
    );
    expect(options).toContain('Eating Out');
    expect(options).not.toContain('Old');
  });

  it('sends the server’s own surplus as the allocation and shows the week it answers with', async () => {
    const fixture = await render();
    const answered: Week = {
      ...DRAFT,
      steps: [
        DRAFT.steps[0],
        {
          ...NEXT_WEEK,
          items: [
            {
              ...ALLOC,
              answer: {
                choice: 'ROLL_OVER',
                categoryId: null,
                categoryName: null,
                billId: null,
                billName: null,
                dueDate: null,
                goalId: null,
                debtId: null,
                amount: usd('63.0000'),
                remember: false,
                recordDebtPayment: false,
                byRule: false,
                answeredBy: null,
                version: 0,
              },
            },
          ],
        },
      ],
    };
    service.answer.mockReturnValue(of(answered));

    const roll = root(fixture).querySelectorAll<HTMLInputElement>('input[type="radio"]')[1];
    roll.click();
    roll.dispatchEvent(new Event('change'));
    await settle(fixture);

    expect(service.answer).toHaveBeenCalledWith('2026-W34', 'alloc', {
      choice: 'ROLL_OVER',
      amount: '63.0000',
    });
    expect(root(fixture).querySelector('[aria-live="polite"]')?.textContent).toContain('$543.00');
  });

  it('finishes with the version and basis it last read', async () => {
    const fixture = await render();
    service.complete.mockReturnValue(of({ ...DRAFT, status: 'COMPLETED', outcome: [] }));

    buttonNamed(root(fixture), 'Finish check-in').click();
    await settle(fixture);

    expect(service.complete).toHaveBeenCalledWith('2026-W34', 6, 'a'.repeat(64));
    expect(text(fixture)).toContain('Check-in done');
  });

  it('re-reads after a stale refusal and marks the item with its reason', async () => {
    const fixture = await render();
    service.complete.mockReturnValue(
      throwError(() =>
        apiError(409, 'check-in-stale', {
          items: [{ itemKey: 'cat.t1', reason: 'subject-changed' }],
        }),
      ),
    );
    service.current.mockReturnValue(of(DRAFT));

    buttonNamed(root(fixture), 'Finish check-in').click();
    await settle(fixture);

    expect(service.current).toHaveBeenCalledTimes(2);
    expect(text(fixture)).toContain('Something changed while you were answering');
    const item = root(fixture).querySelector('#item-cat-t1');
    expect(item?.classList).toContain('is-stale');
    expect(item?.textContent).toContain('This changed in the ledger after you answered.');
  });

  it('offers to discard the other week’s draft when one is open', async () => {
    const fixture = await render({ week: { ...DRAFT, status: 'NOT_STARTED', version: null } });
    service.start.mockReturnValue(
      throwError(() => apiError(409, 'check-in-draft-open', { week: '2026-W33' })),
    );

    buttonNamed(root(fixture), 'Start check-in').click();
    await settle(fixture);

    expect(text(fixture)).toContain(
      'There’s an unfinished check-in for 2026-W33'.replace('’', "'"),
    );
    expect(buttonNamed(root(fixture), 'Discard it')).toBeTruthy();
  });

  it('gives a viewer every figure and no control', async () => {
    const fixture = await render({ role: 'VIEWER' });

    expect(text(fixture)).toContain('You can read this check-in.');
    expect(root(fixture).querySelector('select')).toBeNull();
    expect(() => buttonNamed(root(fixture), 'Finish check-in')).toThrow();
    expect(() => buttonNamed(root(fixture), 'Discard this draft')).toThrow();
    for (const radio of root(fixture).querySelectorAll<HTMLInputElement>('input[type="radio"]')) {
      expect(radio.disabled).toBe(true);
    }
    expect(root(fixture).querySelector('[aria-live="polite"]')?.textContent).toContain('$480.00');
  });
});
