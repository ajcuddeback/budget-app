import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  csvFileName,
  directionOf,
  isUnderOnePercent,
  lastCompleteMonth,
  lastMonths,
  segmentTone,
} from './reports-data';
import { IncomeAndSpending } from './reports.models';
import { ReportsService } from './reports.service';

describe('report ranges', () => {
  it('builds the last twelve months from the device date, ending this month', () => {
    expect(lastMonths(12, new Date(2026, 7, 20))).toEqual({ from: '2025-09', to: '2026-08' });
  });

  it('builds the shorter ranges the chips offer', () => {
    expect(lastMonths(6, new Date(2026, 7, 20))).toEqual({ from: '2026-03', to: '2026-08' });
    expect(lastMonths(3, new Date(2026, 7, 20))).toEqual({ from: '2026-06', to: '2026-08' });
  });

  it('wraps across a year end', () => {
    expect(lastMonths(3, new Date(2027, 0, 2))).toEqual({ from: '2026-11', to: '2027-01' });
  });

  it('uses the device calendar, not UTC: the first of the month is already that month', () => {
    // Local midnight on 1 September. In a zone east of UTC that instant is still 31 August in UTC,
    // and the member's "this month" is the device's, which is the reason there is no server
    // default (decision 10).
    expect(lastMonths(3, new Date(2026, 8, 1, 0, 5)).to).toBe('2026-09');
  });

  it('opens "where it went" on the last complete month, not this one', () => {
    expect(lastCompleteMonth(new Date(2026, 7, 20))).toBe('2026-07');
    expect(lastCompleteMonth(new Date(2027, 0, 3))).toBe('2026-12');
  });
});

describe('the direction of a change', () => {
  it('is flat when the change is exactly zero, compared as a string', () => {
    expect(directionOf('0.0000', '40.0000')).toBe('flat');
    expect(directionOf('0', '40.0000')).toBe('flat');
  });

  it('is up or down by the sign of the server’s change', () => {
    expect(directionOf('0.1600', '612.0000')).toBe('up');
    expect(directionOf('-0.2200', '30.0000')).toBe('down');
    expect(directionOf('-1.0000', '0.0000')).toBe('down');
  });

  it('is new against nothing when there is spending now', () => {
    expect(directionOf(null, '40.0000')).toBe('new');
  });

  it('says nothing against nothing when there is no spending now either', () => {
    expect(directionOf(null, '0.0000')).toBe('none');
    expect(directionOf(null, '-20.0000')).toBe('none');
  });

  it('knows when a real change would round to "0%"', () => {
    expect(isUnderOnePercent('0.0002')).toBe(true);
    expect(isUnderOnePercent('-0.0049')).toBe(true);
    expect(isUnderOnePercent('0.0050')).toBe(false);
    expect(isUnderOnePercent('0.0000')).toBe(false);
  });
});

describe('the rest', () => {
  it('names a CSV from the report and the two periods only', () => {
    expect(csvFileName('income-and-spending', '2025-09', '2026-08')).toBe(
      'budget-owl-income-and-spending-2025-09-2026-08.csv',
    );
  });

  it('gives rows past the sixth the last segment tone', () => {
    expect(segmentTone(0)).toBe('s1');
    expect(segmentTone(5)).toBe('s6');
    expect(segmentTone(6)).toBe('rest');
    expect(segmentTone(40)).toBe('rest');
  });
});

describe('ReportsService', () => {
  let http: HttpTestingController;
  let service: ReportsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(ReportsService);
  });

  afterEach(() => http.verify());

  it('asks for income and spending with both periods, and keeps every amount a string', async () => {
    const report = firstValueFrom(service.incomeAndSpending('2025-09', '2026-08'));

    const request = http.expectOne(
      (r) =>
        r.url === '/api/reports/income-and-spending' &&
        r.params.get('from') === '2025-09' &&
        r.params.get('to') === '2026-08',
    );
    expect(request.request.method).toBe('GET');
    request.flush({
      from: '2025-09',
      to: '2026-08',
      baseCurrency: 'GBP',
      currencies: [
        {
          currency: 'GBP',
          months: [
            {
              period: '2026-08',
              income: '123456789012345.6789',
              spending: '-50.0000',
              uncategorisedIn: '0.0000',
              uncategorisedOut: '0.0000',
              kept: '123456789012395.6789',
              complete: false,
            },
          ],
          totals: {
            income: '123456789012345.6789',
            spending: '-50.0000',
            uncategorisedIn: '0.0000',
            uncategorisedOut: '0.0000',
            kept: '123456789012395.6789',
          },
          averageKept: null,
          bestMonth: null,
          completeMonths: 0,
          uncategorisedCount: 0,
          transactionCount: 2,
        },
      ],
    } satisfies IncomeAndSpending);

    const month = (await report).currencies[0].months[0];
    expect(typeof month.income).toBe('string');
    expect(month.income).toBe('123456789012345.6789');
    expect(month.spending).toBe('-50.0000');
  });

  it('asks for spending by category for one month', async () => {
    const report = firstValueFrom(service.spendingByCategory('2026-07', '2026-07'));

    const request = http.expectOne((r) => r.url === '/api/reports/spending-by-category');
    expect(request.request.params.get('from')).toBe('2026-07');
    expect(request.request.params.get('to')).toBe('2026-07');
    request.flush({
      from: '2026-07',
      to: '2026-07',
      comparedFrom: '2026-06',
      comparedTo: '2026-06',
      complete: true,
      baseCurrency: 'GBP',
      currencies: [],
    });
    expect((await report).comparedFrom).toBe('2026-06');
  });

  it('asks for the same resource as CSV, with Accept and nothing else', async () => {
    const file = firstValueFrom(service.csv('spending-by-category', '2026-07', '2026-07'));

    const request = http.expectOne((r) => r.url === '/api/reports/spending-by-category');
    expect(request.request.headers.get('Accept')).toBe('text/csv');
    expect(request.request.responseType).toBe('blob');
    expect(request.request.method).toBe('GET');
    request.flush(new Blob(['"currency"\r\n'], { type: 'text/csv' }));
    expect(await file).toBeInstanceOf(Blob);
  });
});

describe('formatting a change', () => {
  it('formats the server’s ratio with Intl, unsigned, and computes nothing', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const i18n = TestBed.inject(I18nService);
    await i18n.use('en-GB');

    expect(i18n.formatPercent('0.1600')).toBe('16%');
    expect(i18n.formatPercent('-0.2200')).toBe('22%');
    // The server's figure wins even where it looks odd: the page never recomputes it.
    expect(i18n.formatPercent('0.1555')).toBe('16%');
    expect(i18n.formatPercent('not a ratio')).toBe('');
  });

  it('formats a range of months as one range, not two dates', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const i18n = TestBed.inject(I18nService);
    await i18n.use('en-GB');

    // Intl puts thin spaces around the dash; the shape is what matters here.
    expect(i18n.formatMonthRange('2025-09', '2026-08').replace(/\s/g, ' ')).toBe(
      'Sept 2025 – Aug 2026',
    );
    expect(i18n.formatMonthShort('2026-08')).toBe('Aug 2026');
    expect(i18n.formatMonthRange('2026-13', '2026-08')).toBe('');
  });
});
