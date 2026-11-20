import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NonNullableFormBuilder } from '@angular/forms';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  BillFormValue,
  IncomeFormValue,
  ScheduleFormValue,
  buildBillCreate,
  buildBillPatch,
  buildIncomeCreate,
  buildIncomePatch,
  buildPaidRecord,
  buildScheduleRequest,
  clearUnusedParameters,
  patchIsEmpty,
  scheduleFormFrom,
} from './bill-patch';
import {
  Bill,
  BillOccurrence,
  IncomeSource,
  isNegative,
  occurrenceStatusKey,
  sourceOfTruthKey,
  statusSourceKey,
} from './bills.models';
import { BillsService } from './bills.service';
import { IncomeService } from './income.service';
import { applyScheduleKind, buildScheduleGroup } from './schedule-form';
import { scheduleSaid } from './schedule-text';
import { Schedule, isoWeekday, scheduleKindKey } from './schedule.models';
import { PeriodSummaryService } from './summary.service';

const MONTHLY: Schedule = {
  kind: 'MONTHLY',
  dayOfMonth: 15,
  month: null,
  dayOfWeek: null,
  anchorDate: null,
};

const RENT: Bill = {
  id: 'b1',
  name: 'Rent',
  categoryId: 'c3',
  categoryName: 'Housing',
  categoryParentName: null,
  expectedAmount: '1200.0000',
  currency: 'GBP',
  schedule: MONTHLY,
  amountVaries: false,
  tolerance: null,
  active: true,
  occurrences: [],
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
  version: 1,
};

const OCCURRENCE: BillOccurrence = {
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
};

const SALARY: IncomeSource = {
  id: 'i1',
  name: 'Salary',
  amount: '2400.0000',
  currency: 'GBP',
  schedule: MONTHLY,
  accountId: 'a1',
  accountName: 'Everyday',
  sourceOfTruth: 'MANUAL_ESTIMATE',
  active: true,
  dates: ['2026-08-15'],
  expectedForPeriod: '2400.0000',
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
  version: 2,
};

const schedule = (overrides: Partial<ScheduleFormValue> = {}): ScheduleFormValue => ({
  kind: 'MONTHLY',
  dayOfMonth: '15',
  month: '',
  dayOfWeek: '',
  anchorDate: '',
  ...overrides,
});

const billForm = (overrides: Partial<BillFormValue> = {}): BillFormValue => ({
  name: 'Rent',
  expectedAmount: '1200',
  categoryId: 'c3',
  amountVaries: false,
  tolerance: '',
  schedule: schedule(),
  ...overrides,
});

const incomeForm = (overrides: Partial<IncomeFormValue> = {}): IncomeFormValue => ({
  name: 'Salary',
  amount: '2400',
  accountId: 'a1',
  schedule: schedule(),
  ...overrides,
});

describe('buildScheduleRequest', () => {
  it('sends a monthly schedule as a day of the month and nothing else', () => {
    expect(buildScheduleRequest(schedule())).toEqual({ kind: 'MONTHLY', dayOfMonth: 15 });
  });

  it('sends a yearly schedule as a month and a day', () => {
    expect(buildScheduleRequest(schedule({ kind: 'YEARLY', month: '4', dayOfMonth: '6' }))).toEqual(
      { kind: 'YEARLY', month: 4, dayOfMonth: 6 },
    );
  });

  it('sends a fortnightly schedule as a weekday and the date to count from', () => {
    expect(
      buildScheduleRequest(
        schedule({ kind: 'FORTNIGHTLY', dayOfWeek: 'WEDNESDAY', anchorDate: '2026-08-05' }),
      ),
    ).toEqual({ kind: 'FORTNIGHTLY', dayOfWeek: 'WEDNESDAY', anchorDate: '2026-08-05' });
  });

  it('never sends a parameter the kind has no use for', () => {
    // The server refuses one rather than ignoring it, because ignoring it loses an edit. A form
    // that left a day of the month behind would be a 400 with nothing on screen to explain it.
    const stale = schedule({ kind: 'WEEKLY', dayOfMonth: '15', dayOfWeek: 'FRIDAY' });

    expect(buildScheduleRequest(stale)).toEqual({ kind: 'WEEKLY', dayOfWeek: 'FRIDAY' });
  });

  it('sends nothing but the kind for an irregular bill', () => {
    expect(buildScheduleRequest(schedule({ kind: 'IRREGULAR' }))).toEqual({ kind: 'IRREGULAR' });
  });

  it('omits a parameter nobody has filled in rather than sending a blank one', () => {
    expect(buildScheduleRequest(schedule({ dayOfMonth: '' }))).toEqual({ kind: 'MONTHLY' });
  });
});

describe('clearUnusedParameters', () => {
  it('empties the fields that stopped applying when the kind changes', () => {
    const now = clearUnusedParameters(schedule({ dayOfMonth: '15' }), 'WEEKLY');

    expect(now).toEqual({
      kind: 'WEEKLY',
      dayOfMonth: '',
      month: '',
      dayOfWeek: '',
      anchorDate: '',
    });
  });

  it('keeps the ones that still apply', () => {
    const yearly = schedule({ kind: 'YEARLY', month: '4', dayOfMonth: '6' });

    expect(clearUnusedParameters(yearly, 'MONTHLY')).toMatchObject({
      dayOfMonth: '6',
      month: '',
    });
  });
});

describe('the schedule form group', () => {
  function group() {
    TestBed.configureTestingModule({});
    return buildScheduleGroup(TestBed.inject(NonNullableFormBuilder));
  }

  it('asks for the parameters the kind needs and nothing else', () => {
    const form = group();

    expect(form.controls.dayOfMonth.valid).toBe(false);
    form.controls.dayOfMonth.setValue('15');
    expect(form.valid).toBe(true);
  });

  it('clears and stops requiring a parameter the new kind does not use', () => {
    const form = group();
    form.controls.dayOfMonth.setValue('15');

    applyScheduleKind(form, 'WEEKLY');

    expect(form.controls.dayOfMonth.value).toBe('');
    expect(form.controls.dayOfMonth.valid).toBe(true);
    // And the weekday it now needs is required, so the person finds out here and not from a 400.
    expect(form.controls.dayOfWeek.valid).toBe(false);
  });

  it('requires nothing at all for an irregular bill', () => {
    const form = group();

    applyScheduleKind(form, 'IRREGULAR');

    expect(form.valid).toBe(true);
  });
});

describe('scheduleSaid', () => {
  let words: I18nService;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    words = TestBed.inject(I18nService);
    await words.use('en-GB');
  });

  it('names the weekday rather than printing a code', () => {
    const weekly: Schedule = {
      kind: 'WEEKLY',
      dayOfMonth: null,
      month: null,
      dayOfWeek: 'WEDNESDAY',
      anchorDate: null,
    };

    expect(scheduleSaid(weekly, words)).toBe('Every Wednesday');
  });

  it('says which Wednesdays a fortnightly schedule means', () => {
    const fortnightly: Schedule = {
      kind: 'FORTNIGHTLY',
      dayOfMonth: null,
      month: null,
      dayOfWeek: 'WEDNESDAY',
      anchorDate: '2026-08-05',
    };

    expect(scheduleSaid(fortnightly, words)).toContain('Every other Wednesday');
    expect(scheduleSaid(fortnightly, words)).toContain('2026');
  });

  it('gives a yearly schedule a month and a day in the locale’s own order', () => {
    const yearly: Schedule = {
      kind: 'YEARLY',
      dayOfMonth: 6,
      month: 4,
      dayOfWeek: null,
      anchorDate: null,
    };

    expect(scheduleSaid(yearly, words)).toBe('Every year on 6 April');
  });

  it('says an irregular bill has no date, which is the point of it', () => {
    const irregular: Schedule = {
      kind: 'IRREGULAR',
      dayOfMonth: null,
      month: null,
      dayOfWeek: null,
      anchorDate: null,
    };

    expect(scheduleSaid(irregular, words)).toContain('no set date');
  });

  it('describes a monthly schedule by its day', () => {
    expect(scheduleSaid(MONTHLY, words)).toBe('Every month on day 15');
  });
});

describe('buildBillCreate', () => {
  it('sends the amount as a string and the schedule the form built', () => {
    expect(buildBillCreate(billForm({ name: '  Rent  ' }))).toEqual({
      name: 'Rent',
      // A string, never a number: an amount that has been through a double has already lost
      // whatever it lost (ADR-0006).
      expectedAmount: '1200',
      schedule: { kind: 'MONTHLY', dayOfMonth: 15 },
      categoryId: 'c3',
    });
  });

  it('omits an absent category rather than sending an empty string', () => {
    expect('categoryId' in buildBillCreate(billForm({ categoryId: '' }))).toBe(false);
  });

  it('sends a tolerance only alongside the flag that gives it meaning', () => {
    const varies = buildBillCreate(billForm({ amountVaries: true, tolerance: '22' }));

    expect(varies).toMatchObject({ amountVaries: true, tolerance: '22' });
    expect('tolerance' in buildBillCreate(billForm())).toBe(false);
    expect('amountVaries' in buildBillCreate(billForm())).toBe(false);
  });
});

describe('buildBillPatch', () => {
  it('sends only what changed', () => {
    expect(buildBillPatch(RENT, billForm({ expectedAmount: '1250' }))).toEqual({
      version: 1,
      expectedAmount: '1250',
    });
  });

  it('treats 1200 and 1200.0000 as the same amount', () => {
    // The server echoes the stored scale, so a string comparison would send a pointless patch —
    // and bump the version — after every edit.
    expect(patchIsEmpty(buildBillPatch(RENT, billForm()))).toBe(true);
    expect(patchIsEmpty(buildBillPatch(RENT, billForm({ expectedAmount: '1200.00' })))).toBe(true);
  });

  it('sends the whole schedule when any part of it moved', () => {
    const patch = buildBillPatch(RENT, billForm({ schedule: schedule({ dayOfMonth: '20' }) }));

    expect(patch.schedule).toEqual({ kind: 'MONTHLY', dayOfMonth: 20 });
  });

  it('sends no schedule when it did not move', () => {
    expect('schedule' in buildBillPatch(RENT, billForm())).toBe(false);
  });

  it('sends an explicit null to clear the category and omits it when untouched', () => {
    expect(buildBillPatch(RENT, billForm({ categoryId: '' })).categoryId).toBeNull();
    expect('categoryId' in buildBillPatch(RENT, billForm())).toBe(false);
  });

  it('sends amountVaries and tolerance together, or neither', () => {
    const varies = buildBillPatch(RENT, billForm({ amountVaries: true, tolerance: '22' }));
    expect(varies).toMatchObject({ amountVaries: true, tolerance: '22' });

    const fixed = buildBillPatch(
      { ...RENT, amountVaries: true, tolerance: '22.0000' },
      billForm({ amountVaries: false }),
    );
    // Going back to a fixed amount sends the flag alone: a fixed bill with a tolerance is a 400.
    expect(fixed).toEqual({ version: 1, amountVaries: false });

    const unchanged = buildBillPatch(
      { ...RENT, amountVaries: true, tolerance: '22.0000' },
      billForm({ amountVaries: true, tolerance: '22' }),
    );
    expect(patchIsEmpty(unchanged)).toBe(true);
  });
});

describe('buildIncomeCreate and buildIncomePatch', () => {
  it('sends what arrives each time, as a string', () => {
    expect(buildIncomeCreate(incomeForm())).toEqual({
      name: 'Salary',
      amount: '2400',
      schedule: { kind: 'MONTHLY', dayOfMonth: 15 },
      accountId: 'a1',
    });
  });

  it('omits the account when none was chosen', () => {
    expect('accountId' in buildIncomeCreate(incomeForm({ accountId: '' }))).toBe(false);
  });

  it('clears the account with an explicit null and leaves it alone otherwise', () => {
    expect(buildIncomePatch(SALARY, incomeForm({ accountId: '' })).accountId).toBeNull();
    expect(patchIsEmpty(buildIncomePatch(SALARY, incomeForm()))).toBe(true);
  });

  it('carries the version so a concurrent edit is a 409 and not a silent loss', () => {
    expect(buildIncomePatch(SALARY, incomeForm({ amount: '2500' }))).toEqual({
      version: 2,
      amount: '2500',
    });
  });
});

describe('buildPaidRecord', () => {
  it('records what really left and the date it went', () => {
    expect(
      buildPaidRecord({ dueDate: '2026-08-15', actualAmount: '1300.50', paidDate: '2026-08-14' }),
    ).toEqual({ status: 'PAID', actualAmount: '1300.50', paidDate: '2026-08-14' });
  });

  it('omits what was left blank rather than sending an empty figure', () => {
    expect(buildPaidRecord({ dueDate: '2026-08-15', actualAmount: '', paidDate: '' })).toEqual({
      status: 'PAID',
    });
  });
});

describe('the display helpers', () => {
  it('reads a negative figure from its sign, never from a parse', () => {
    expect(isNegative('-180.0000')).toBe(true);
    expect(isNegative('0.0000')).toBe(false);
    expect(isNegative('180.0000')).toBe(false);
  });

  it('gives a neutral word for anything this client has never heard of', () => {
    expect(occurrenceStatusKey('CANCELLED')).toBe('bills.status.unknown');
    expect(statusSourceKey('IMPORTED')).toBe('bills.source.unknown');
    expect(sourceOfTruthKey('GUESSED')).toBe('bills.truth.unknown');
    expect(scheduleKindKey('QUARTERLY')).toBe('bills.kind.unknown');
  });

  it('numbers the weekdays the way Intl does, Monday first', () => {
    expect(isoWeekday('MONDAY')).toBe(1);
    expect(isoWeekday('SUNDAY')).toBe(7);
  });

  it('fills a form from the schedule the server reported', () => {
    expect(scheduleFormFrom(MONTHLY)).toEqual({
      kind: 'MONTHLY',
      dayOfMonth: '15',
      month: '',
      dayOfWeek: '',
      anchorDate: '',
    });
  });
});

describe('BillsService', () => {
  let service: BillsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BillsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('asks for one month at a time, retired bills included', () => {
    service.list('2026-08').subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/bills');
    expect(request.request.params.get('period')).toBe('2026-08');
    expect(request.request.params.get('includeInactive')).toBe('true');
    request.flush({ content: [], total: '0', outstanding: '0', overdue: '0', currency: 'GBP' });
  });

  it('records a status against a date, with PUT, and carries no version', () => {
    // The URL is valid before anything exists at it, and recording the same status twice is the
    // same request twice (ADR-0032).
    service.record('b1', '2026-08-15', { status: 'PAID' }).subscribe();

    const request = http.expectOne('/api/bills/b1/occurrences/2026-08-15');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ status: 'PAID' });
    request.flush(OCCURRENCE);
  });

  it('forgets a record by deleting the date, not by recording DUE', () => {
    service.forget('b1', '2026-08-15').subscribe();

    const request = http.expectOne('/api/bills/b1/occurrences/2026-08-15');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
  });

  it('asks for one bill with the month it wants the occurrences of', () => {
    // A bill on its own has no occurrences: which month the client is looking at is the client's
    // question, so the server requires it here too.
    service.get('b1', '2026-08').subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/bills/b1');
    expect(request.request.params.get('period')).toBe('2026-08');
    request.flush(RENT);
  });

  it('creates, patches and deletes a bill with the method each one means', () => {
    service
      .create({ name: 'Rent', expectedAmount: '1200', schedule: { kind: 'IRREGULAR' } })
      .subscribe();
    const created = http.expectOne('/api/bills');
    expect(created.request.method).toBe('POST');
    created.flush(RENT);

    service.update('b1', { version: 1, expectedAmount: '1250' }).subscribe();
    const patched = http.expectOne('/api/bills/b1');
    expect(patched.request.method).toBe('PATCH');
    // The version is what makes a concurrent edit a 409 instead of a silent loss.
    expect(patched.request.body).toEqual({ version: 1, expectedAmount: '1250' });
    patched.flush(RENT);

    service.remove('b1').subscribe();
    const removed = http.expectOne('/api/bills/b1');
    expect(removed.request.method).toBe('DELETE');
    removed.flush(null);
  });

  it('encodes ids and dates so they cannot alter the path they are placed in', () => {
    service.forget('../../accounts', '2026-08-15').subscribe();

    const request = http.expectOne('/api/bills/..%2F..%2Faccounts/occurrences/2026-08-15');
    request.flush(null);
  });
});

describe('IncomeService and PeriodSummaryService', () => {
  let income: IncomeService;
  let summaries: PeriodSummaryService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    income = TestBed.inject(IncomeService);
    summaries = TestBed.inject(PeriodSummaryService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('asks income for one month at a time', () => {
    income.list('2026-08').subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/income');
    expect(request.request.params.get('period')).toBe('2026-08');
    request.flush({ content: [SALARY], total: '2400.0000', currency: 'GBP' });
  });

  it('creates, patches and deletes an income source', () => {
    income
      .create({ name: 'Salary', amount: '2400', schedule: { kind: 'MONTHLY', dayOfMonth: 15 } })
      .subscribe();
    const created = http.expectOne('/api/income');
    expect(created.request.method).toBe('POST');
    created.flush(SALARY);

    income.update('i1', { version: 2, active: false }).subscribe();
    const patched = http.expectOne('/api/income/i1');
    expect(patched.request.method).toBe('PATCH');
    patched.flush(SALARY);

    income.remove('i1').subscribe();
    const removed = http.expectOne('/api/income/i1');
    expect(removed.request.method).toBe('DELETE');
    removed.flush(null);
  });

  it('asks for one income source with the month it wants the dates of', () => {
    income.get('i1', '2026-08').subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/income/i1');
    expect(request.request.params.get('period')).toBe('2026-08');
    request.flush(SALARY);
  });

  it('asks the server for the subtraction rather than doing it here', () => {
    summaries.get('2026-08').subscribe();

    const request = http.expectOne('/api/periods/2026-08/summary');
    expect(request.request.method).toBe('GET');
    request.flush({
      period: '2026-08',
      income: '2400.0000',
      bills: '1200.0000',
      billsOutstanding: '0',
      billsOverdue: '0',
      envelopesAllocated: '400.0000',
      left: '800.0000',
      currency: 'GBP',
    });
  });
});
