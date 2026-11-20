import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  EntryFormValue,
  EnvelopeFormValue,
  buildEntryCreate,
  buildEntryPatch,
  buildEnvelopeCreate,
  buildEnvelopePatch,
  patchIsEmpty,
} from './envelope-patch';
import { currentPeriod, isPeriod, periodAsDate, shiftPeriod } from './envelope-period';
import { Envelope, EnvelopeEntry, entrySourceKey } from './envelope.models';
import { EnvelopesService } from './envelopes.service';

const GROCERIES: Envelope = {
  id: 'e1',
  name: 'Groceries',
  period: '2026-08',
  categoryId: 'c3',
  categoryName: 'Groceries',
  categoryParentName: null,
  amount: '400.0000',
  spent: '191.1500',
  remaining: '208.8500',
  currency: 'GBP',
  entryCount: 2,
  empty: false,
  archived: false,
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
  version: 1,
};

const ENTRY: EnvelopeEntry = {
  id: 'n1',
  envelopeId: 'e1',
  date: '2026-08-03',
  amount: '86.4000',
  currency: 'GBP',
  payeeId: 'p1',
  payeeName: 'Greenfield Market',
  note: 'big shop',
  source: 'MANUAL',
  transactionId: null,
  createdAt: '2026-08-03T16:20:00Z',
  updatedAt: '2026-08-03T16:20:00Z',
  version: 0,
};

const envelopeForm = (overrides: Partial<EnvelopeFormValue> = {}): EnvelopeFormValue => ({
  name: 'Groceries',
  period: '2026-08',
  amount: '400',
  categoryId: 'c3',
  ...overrides,
});

const entryForm = (overrides: Partial<EntryFormValue> = {}): EntryFormValue => ({
  amount: '86.40',
  date: '2026-08-03',
  payeeName: 'Greenfield Market',
  note: 'big shop',
  ...overrides,
});

describe('period helpers', () => {
  it('reads the current month from the viewer’s own calendar', () => {
    expect(currentPeriod(new Date(2026, 7, 20))).toBe('2026-08');
    // Zero-padded, because the server matches on the string.
    expect(currentPeriod(new Date(2026, 0, 1))).toBe('2026-01');
  });

  it('steps forward and back across a year boundary', () => {
    expect(shiftPeriod('2026-08', 1)).toBe('2026-09');
    expect(shiftPeriod('2026-12', 1)).toBe('2027-01');
    expect(shiftPeriod('2026-01', -1)).toBe('2025-12');
    expect(shiftPeriod('2026-08', -14)).toBe('2025-06');
  });

  it('does the arithmetic on strings, so no timezone can change the answer', () => {
    // "Which month am I budgeting" is a calendar question. Going through Date would let a
    // household near a date line get a different answer from the same click.
    expect(shiftPeriod('2026-03', 1)).toBe('2026-04');
    expect(shiftPeriod('2026-10', 1)).toBe('2026-11');
  });

  it('turns a period into the first of that month for formatting', () => {
    const date = periodAsDate('2026-08');

    expect(date.getFullYear()).toBe(2026);
    expect(date.getMonth()).toBe(7);
    expect(date.getDate()).toBe(1);
  });

  it('recognises the shape the server accepts and rejects the rest', () => {
    expect(isPeriod('2026-08')).toBe(true);
    expect(isPeriod('2026-13')).toBe(false);
    expect(isPeriod('2026-00')).toBe(false);
    expect(isPeriod('2026-8')).toBe(false);
    expect(isPeriod('2026-08-01')).toBe(false);
  });
});

describe('buildEnvelopeCreate', () => {
  it('sends the month as a label and trims the name', () => {
    expect(buildEnvelopeCreate(envelopeForm({ name: '  Groceries  ' }))).toEqual({
      name: 'Groceries',
      period: '2026-08',
      amount: '400',
      categoryId: 'c3',
    });
  });

  it('omits an absent category rather than sending an empty string', () => {
    expect(buildEnvelopeCreate(envelopeForm({ categoryId: '' }))).toEqual({
      name: 'Groceries',
      period: '2026-08',
      amount: '400',
    });
  });
});

describe('buildEnvelopePatch', () => {
  it('sends only what changed', () => {
    expect(buildEnvelopePatch(GROCERIES, envelopeForm({ amount: '450' }))).toEqual({
      version: 1,
      amount: '450',
    });
  });

  it('treats 400 and 400.0000 as the same amount', () => {
    // The server echoes the stored scale, so a string comparison would send a pointless patch —
    // and bump the version — after every edit.
    expect(patchIsEmpty(buildEnvelopePatch(GROCERIES, envelopeForm()))).toBe(true);
    expect(patchIsEmpty(buildEnvelopePatch(GROCERIES, envelopeForm({ amount: '400.00' })))).toBe(
      true,
    );
  });

  it('sends an explicit null to clear the category and omits it when untouched', () => {
    expect(buildEnvelopePatch(GROCERIES, envelopeForm({ categoryId: '' })).categoryId).toBeNull();
    expect('categoryId' in buildEnvelopePatch(GROCERIES, envelopeForm())).toBe(false);
  });
});

describe('buildEntryCreate', () => {
  it('sends the amount and whatever else was filled in', () => {
    expect(buildEntryCreate(entryForm())).toEqual({
      amount: '86.40',
      date: '2026-08-03',
      payeeName: 'Greenfield Market',
      note: 'big shop',
    });
  });

  it('needs nothing but an amount', () => {
    // The 81-times-a-month action: anything else required would have broken the feature.
    expect(buildEntryCreate(entryForm({ date: '', payeeName: '  ', note: '' }))).toEqual({
      amount: '86.40',
    });
  });
});

describe('buildEntryPatch', () => {
  it('does not clear the fields the user never touched', () => {
    const patch = buildEntryPatch(ENTRY, entryForm({ amount: '96.40' }));

    expect(patch).toEqual({ version: 0, amount: '96.40' });
    expect('payeeName' in patch).toBe(false);
    expect('note' in patch).toBe(false);
  });

  it('sends an explicit null for each field the user cleared', () => {
    const patch = buildEntryPatch(ENTRY, entryForm({ payeeName: '', note: '' }));

    expect(patch).toEqual({ version: 0, payeeName: null, note: null });
  });

  it('notices a change in the smallest unit', () => {
    expect(buildEntryPatch(ENTRY, entryForm({ amount: '86.4001' })).amount).toBe('86.4001');
  });
});

describe('entrySourceKey', () => {
  it('gives a neutral word for a source this client has never heard of', () => {
    expect(entrySourceKey('IMPORTED')).toBe('envelopes.source.unknown');
  });
});

describe('EnvelopesService', () => {
  let service: EnvelopesService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(EnvelopesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('asks for one month at a time, archived included', () => {
    service.list('2026-08').subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/envelopes');
    expect(request.request.params.get('period')).toBe('2026-08');
    expect(request.request.params.get('includeArchived')).toBe('true');
    request.flush({
      content: [],
      totalAllocated: '0',
      totalSpent: '0',
      totalRemaining: '0',
      currency: 'GBP',
      page: 0,
      size: 200,
      totalElements: 0,
      totalPages: 0,
    });
  });

  it('names the envelope as well as the entry when correcting one', () => {
    // An entry of the right household but the wrong envelope is still the wrong entry for the
    // request that asked for it, and the server answers 404.
    service.updateEntry('e1', 'n1', { version: 0, amount: '1.00' }).subscribe();

    const request = http.expectOne('/api/envelopes/e1/entries/n1');
    expect(request.request.method).toBe('PATCH');
    request.flush(ENTRY);
  });

  it('encodes ids so they cannot alter the path they are placed in', () => {
    service.removeEntry('../../accounts', 'n1').subscribe();

    const request = http.expectOne('/api/envelopes/..%2F..%2Faccounts/entries/n1');
    request.flush(null);
  });
});
