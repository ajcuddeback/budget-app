import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  TransactionFormValue,
  buildTransactionCreate,
  buildTransactionPatch,
  directionOf,
  magnitudeOf,
  patchIsEmpty,
  signedAmount,
} from './transaction-patch';
import {
  SETTABLE_TRANSACTION_STATUSES,
  TRANSACTION_STATUSES,
  Transaction,
  transactionStatusKey,
} from './transaction.models';
import { TransactionsService } from './transactions.service';

const SHOP: Transaction = {
  id: 't1',
  accountId: 'a1',
  accountName: 'Current',
  date: '2026-11-11',
  amount: '-42.5000',
  currency: 'GBP',
  payeeId: 'p1',
  payeeName: 'Tesco',
  categoryId: 'c3',
  categoryName: 'Groceries',
  categoryParentName: null,
  note: 'weekly shop',
  status: 'CLEARED',
  transfer: false,
  transferGroupId: null,
  createdAt: '2026-11-11T09:00:00Z',
  updatedAt: '2026-11-11T09:00:00Z',
  version: 2,
};

const form = (overrides: Partial<TransactionFormValue> = {}): TransactionFormValue => ({
  accountId: 'a1',
  date: '2026-11-11',
  direction: 'out',
  amount: '42.50',
  payeeName: 'Tesco',
  categoryId: 'c3',
  note: 'weekly shop',
  status: 'CLEARED',
  ...overrides,
});

describe('signedAmount', () => {
  it('makes money out negative, so nobody has to type a minus sign', () => {
    expect(signedAmount({ amount: '42.50', direction: 'out' })).toBe('-42.50');
  });

  it('leaves money in positive', () => {
    expect(signedAmount({ amount: '1200', direction: 'in' })).toBe('1200');
  });

  it('honours a typed minus sign instead of fighting it', () => {
    // Somebody who writes -42 with "Money out" selected means forty-two out. Normalising both
    // inputs to the same answer is kinder than an error about a character that said the right
    // thing.
    expect(signedAmount({ amount: '-42.50', direction: 'out' })).toBe('-42.50');
    expect(signedAmount({ amount: '-42.50', direction: 'in' })).toBe('42.50');
  });

  it('ignores surrounding whitespace', () => {
    expect(signedAmount({ amount: '  42.50  ', direction: 'out' })).toBe('-42.50');
  });
});

describe('directionOf and magnitudeOf', () => {
  it('reads the direction back off the sign', () => {
    expect(directionOf(SHOP)).toBe('out');
    expect(directionOf({ ...SHOP, amount: '1200.0000' })).toBe('in');
  });

  it('gives the form an unsigned magnitude to edit', () => {
    expect(magnitudeOf(SHOP)).toBe('42.5000');
    expect(magnitudeOf({ ...SHOP, amount: '1200.0000' })).toBe('1200.0000');
  });

  it('round-trips, so re-opening a transaction and saving it changes nothing', () => {
    const patch = buildTransactionPatch(
      SHOP,
      form({ direction: directionOf(SHOP), amount: magnitudeOf(SHOP) }),
    );

    expect(patchIsEmpty(patch)).toBe(true);
  });
});

describe('buildTransactionCreate', () => {
  it('applies the sign and sends the amount as a string', () => {
    expect(buildTransactionCreate(form())).toEqual({
      accountId: 'a1',
      date: '2026-11-11',
      amount: '-42.50',
      status: 'CLEARED',
      payeeName: 'Tesco',
      categoryId: 'c3',
      note: 'weekly shop',
    });
  });

  it('omits an empty payee, category and note rather than sending blanks', () => {
    // An empty payee name is a validation failure; no payee at all is an ordinary transaction.
    const request = buildTransactionCreate(form({ payeeName: '   ', categoryId: '', note: '' }));

    expect(request).toEqual({
      accountId: 'a1',
      date: '2026-11-11',
      amount: '-42.50',
      status: 'CLEARED',
    });
  });
});

describe('buildTransactionPatch', () => {
  it('sends only what changed', () => {
    const patch = buildTransactionPatch(SHOP, form({ amount: '45.00' }));

    expect(patch).toEqual({ version: 2, amount: '-45.00' });
  });

  it('does not clear the fields the user never touched', () => {
    // This is the data-loss bug the three-way shape exists to prevent: a client that sent the
    // whole form would clear whatever was left blank.
    const patch = buildTransactionPatch(SHOP, form({ amount: '45.00' }));

    expect('payeeName' in patch).toBe(false);
    expect('categoryId' in patch).toBe(false);
    expect('note' in patch).toBe(false);
  });

  it('sends an explicit null for each field the user cleared', () => {
    const patch = buildTransactionPatch(SHOP, form({ payeeName: '', categoryId: '', note: '' }));

    expect(patch).toEqual({
      version: 2,
      payeeName: null,
      categoryId: null,
      note: null,
    });
  });

  it('treats the same amount written with different precision as unchanged', () => {
    // The server echoes "-42.5000" and the form shows what it was given, so a string comparison
    // would send a pointless patch — and bump the version — after every edit.
    expect(patchIsEmpty(buildTransactionPatch(SHOP, form({ amount: '42.5' })))).toBe(true);
    expect(patchIsEmpty(buildTransactionPatch(SHOP, form({ amount: '42.500' })))).toBe(true);
    expect(patchIsEmpty(buildTransactionPatch(SHOP, form({ amount: '042.50' })))).toBe(true);
  });

  it('notices a real change in the smallest unit', () => {
    const patch = buildTransactionPatch(SHOP, form({ amount: '42.5001' }));

    expect(patch.amount).toBe('-42.5001');
  });

  it('notices a change of direction at the same magnitude', () => {
    const patch = buildTransactionPatch(SHOP, form({ direction: 'in' }));

    expect(patch.amount).toBe('42.50');
  });

  it('sends a status change and nothing else', () => {
    const patch = buildTransactionPatch(SHOP, form({ status: 'PENDING' }));

    expect(patch).toEqual({ version: 2, status: 'PENDING' });
  });

  it('always carries the version, which is what makes a concurrent edit a 409', () => {
    expect(buildTransactionPatch(SHOP, form()).version).toBe(2);
  });
});

describe('SETTABLE_TRANSACTION_STATUSES', () => {
  it('does not offer RECONCILED, which the server refuses from a caller', () => {
    // A transaction the server has marked reconciled must still be displayable, so the full list
    // keeps it — these two answer different questions and must not be collapsed into one.
    expect(SETTABLE_TRANSACTION_STATUSES).toEqual(['PENDING', 'CLEARED']);
    expect(TRANSACTION_STATUSES).toContain('RECONCILED');
  });
});

describe('transactionStatusKey', () => {
  it('gives a neutral word for a status this client has never heard of', () => {
    expect(transactionStatusKey('SETTLING')).toBe('transactions.status.unknown');
  });
});

describe('TransactionsService', () => {
  let service: TransactionsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TransactionsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const flushLedger = (request: { flush(body: unknown): void }) =>
    request.flush({
      content: [],
      pageTotal: null,
      pageTotalCurrency: null,
      page: 0,
      size: 50,
      totalElements: 0,
      totalPages: 0,
    });

  it('sends no filter parameters when nothing is filtered', () => {
    service.list().subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/transactions');
    expect(request.request.params.has('accountId')).toBe(false);
    expect(request.request.params.has('uncategorised')).toBe(false);
    expect(request.request.params.has('search')).toBe(false);
    expect(request.request.params.get('page')).toBe('0');
    flushLedger(request);
  });

  it('sends each filter it was given', () => {
    service
      .list({
        accountId: 'a1',
        categoryId: 'c3',
        from: '2026-01-01',
        to: '2026-12-31',
        search: 'lidl',
        page: 2,
      })
      .subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/transactions');
    expect(request.request.params.get('accountId')).toBe('a1');
    expect(request.request.params.get('categoryId')).toBe('c3');
    expect(request.request.params.get('from')).toBe('2026-01-01');
    expect(request.request.params.get('to')).toBe('2026-12-31');
    expect(request.request.params.get('search')).toBe('lidl');
    expect(request.request.params.get('page')).toBe('2');
    flushLedger(request);
  });

  it('leaves out a search that is only whitespace, which would match nothing', () => {
    service.list({ search: '   ' }).subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/transactions');
    expect(request.request.params.has('search')).toBe(false);
    flushLedger(request);
  });

  it('sends the uncategorised flag only when it is on', () => {
    service.list({ uncategorised: true }).subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/transactions');
    expect(request.request.params.get('uncategorised')).toBe('true');
    flushLedger(request);
  });

  it('encodes an id so it cannot alter the path it is placed in', () => {
    service.remove('../accounts/a1').subscribe();

    const request = http.expectOne('/api/transactions/..%2Faccounts%2Fa1');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
  });
});
