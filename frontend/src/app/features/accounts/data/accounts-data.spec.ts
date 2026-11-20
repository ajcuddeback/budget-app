import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { groupByCurrency, orderedByName } from './account-grouping';
import {
  AccountFormValue,
  buildAccountCreate,
  buildAccountPatch,
  differingFields,
} from './account-patch';
import { Account, accountTypeKey, isKnownToHaveHistory } from './account.models';
import { AccountsService } from './accounts.service';

const CHASE: Account = {
  id: 'a1',
  name: 'Chase',
  lastFour: '4412',
  type: 'CREDIT_CARD',
  currency: 'GBP',
  openingBalance: '-400.0000',
  balance: '-425.5000',
  archived: false,
  createdAt: '2026-10-01T09:00:00Z',
  updatedAt: '2026-10-11T09:00:00Z',
  version: 3,
};

const SAVINGS: Account = {
  ...CHASE,
  id: 'a2',
  name: 'Savings',
  lastFour: null,
  type: 'SAVINGS',
  currency: 'GBP',
  openingBalance: '1000.0000',
  balance: '1000.0000',
  version: 0,
};

const EURO: Account = {
  ...SAVINGS,
  id: 'a3',
  name: 'Berlin account',
  currency: 'EUR',
  openingBalance: '250.5000',
  balance: '250.5000',
};

function form(overrides: Partial<AccountFormValue> = {}): AccountFormValue {
  return {
    name: 'Chase',
    lastFour: '4412',
    type: 'CREDIT_CARD',
    currency: 'GBP',
    openingBalance: '-400.0000',
    ...overrides,
  };
}

describe('AccountsService', () => {
  let http: HttpTestingController;
  let service: AccountsService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(AccountsService);
  });

  afterEach(() => http.verify());

  it('asks for archived rows too, and for one page the server will not clamp', () => {
    service.list().subscribe();
    const request = http.expectOne((r) => r.url === '/api/accounts');

    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('includeArchived')).toBe('true');
    expect(request.request.params.get('size')).toBe('200');
    request.flush({ content: [CHASE], page: 0, size: 200, totalElements: 1, totalPages: 1 });
  });

  it('sends amounts as strings, never as JSON numbers', () => {
    service
      .create({
        name: 'Chase',
        lastFour: '4412',
        type: 'CREDIT_CARD',
        currency: 'GBP',
        openingBalance: '-400.0000',
      })
      .subscribe();
    const posted = http.expectOne('/api/accounts');
    const body = posted.request.body as Record<string, unknown>;

    expect(typeof body['openingBalance']).toBe('string');
    expect(body['openingBalance']).toBe('-400.0000');
    // The whole body, as JSON, with not a single numeric amount in it.
    expect(JSON.stringify(body)).toBe(
      '{"name":"Chase","lastFour":"4412","type":"CREDIT_CARD","currency":"GBP","openingBalance":"-400.0000"}',
    );
    posted.flush(CHASE);
  });

  it('patches, archives, un-archives and deletes by escaped id', () => {
    service.update('a/1', { version: 3, name: 'Chase' }).subscribe();
    expect(http.expectOne('/api/accounts/a%2F1').request.method).toBe('PATCH');

    service.archive('a1').subscribe();
    expect(http.expectOne('/api/accounts/a1/archive').request.method).toBe('POST');

    service.unarchive('a1').subscribe();
    expect(http.expectOne('/api/accounts/a1/unarchive').request.method).toBe('POST');

    service.remove('a1').subscribe();
    expect(http.expectOne('/api/accounts/a1').request.method).toBe('DELETE');

    service.get('a1').subscribe();
    expect(http.expectOne('/api/accounts/a1').request.method).toBe('GET');
    http.match(() => true).forEach((r) => r.flush(CHASE));
  });
});

describe('buildAccountCreate', () => {
  it('omits the digits rather than sending an empty string the server would reject', () => {
    expect(buildAccountCreate(form({ lastFour: '' }))).not.toHaveProperty('lastFour');
    expect(buildAccountCreate(form({ lastFour: ' 4412 ' })).lastFour).toBe('4412');
  });

  it('trims the name and normalises the currency, and keeps every digit of the amount', () => {
    const request = buildAccountCreate(
      form({ name: '  Chase  ', currency: 'gbp', openingBalance: ' -400.0000 ' }),
    );

    expect(request.name).toBe('Chase');
    expect(request.currency).toBe('GBP');
    expect(request.openingBalance).toBe('-400.0000');
  });
});

/**
 * The three-way `lastFour` is the whole reason this function exists (ADR-0028). Collapsing absent
 * and cleared means a client that patches only the name silently erases the digits that tell two
 * cards at one bank apart — so each of the three states gets its own assertion.
 */
describe('buildAccountPatch', () => {
  it('leaves the digits alone when they were not touched', () => {
    const patch = buildAccountPatch(CHASE, form({ name: 'Chase Credit' }));

    expect(patch).toEqual({ version: 3, name: 'Chase Credit' });
    expect('lastFour' in patch).toBe(false);
    // And the absent property really is absent once it is JSON.
    expect(JSON.stringify(patch)).not.toContain('lastFour');
  });

  it('clears the digits with an explicit null when the box was emptied', () => {
    const patch = buildAccountPatch(CHASE, form({ lastFour: '' }));

    expect(patch).toEqual({ version: 3, lastFour: null });
    expect(JSON.stringify(patch)).toBe('{"version":3,"lastFour":null}');
  });

  it('sets the digits when they were typed', () => {
    expect(buildAccountPatch(CHASE, form({ lastFour: '9876' }))).toEqual({
      version: 3,
      lastFour: '9876',
    });
  });

  it('says nothing at all about digits an account never had', () => {
    const patch = buildAccountPatch(SAVINGS, {
      ...form({ lastFour: '' }),
      name: 'Savings',
      type: 'SAVINGS',
      openingBalance: '1000.0000',
    });

    expect(patch).toEqual({ version: 0 });
  });

  it('does not report a re-scaled amount as a change, and sends the typed digits when it is', () => {
    expect(buildAccountPatch(CHASE, form({ openingBalance: '-400' }))).toEqual({ version: 3 });

    const patch = buildAccountPatch(CHASE, form({ openingBalance: '12345678901234.5678' }));
    expect(patch.openingBalance).toBe('12345678901234.5678');
    expect(typeof patch.openingBalance).toBe('string');
  });

  it('only mentions the currency when it changed, so an immutable one is not refused for nothing', () => {
    expect(buildAccountPatch(CHASE, form({ currency: 'gbp' }))).toEqual({ version: 3 });
    expect(buildAccountPatch(CHASE, form({ currency: 'eur' })).currency).toBe('EUR');
  });

  it('carries the version it read, always', () => {
    expect(buildAccountPatch(CHASE, form()).version).toBe(3);
    expect(buildAccountPatch({ ...CHASE, version: 0 }, form()).version).toBe(0);
  });
});

describe('differingFields', () => {
  it('names what somebody else changed, and ignores a re-scaled amount', () => {
    const theirs: Account = {
      ...CHASE,
      name: 'Chase Visa',
      lastFour: null,
      openingBalance: '-400',
      archived: true,
      version: 4,
    };

    expect(differingFields(CHASE, theirs)).toEqual(['name', 'lastFour', 'archived']);
    expect(differingFields(CHASE, CHASE)).toEqual([]);
  });
});

describe('groupByCurrency', () => {
  it('groups by currency, subtotals each in its own currency, and totals nothing across them', () => {
    const groups = groupByCurrency([CHASE, SAVINGS, EURO]);

    expect(groups.map((g) => g.currency)).toEqual(['EUR', 'GBP']);
    expect(groups[0].subtotal).toBe('250.5000');
    // -425.50 + 1000.00, exactly, and never added to the EUR group.
    expect(groups[1].subtotal).toBe('574.5000');
    expect(groups[1].accounts.map((a) => a.id)).toEqual(['a1', 'a2']);
  });

  it('reports a subtotal it cannot compute as missing rather than as zero', () => {
    const broken = { ...SAVINGS, balance: 'not an amount' };

    expect(groupByCurrency([CHASE, broken])[0].subtotal).toBeNull();
  });

  it('keeps one currency in one group', () => {
    expect(groupByCurrency([CHASE, SAVINGS])).toHaveLength(1);
    expect(groupByCurrency([])).toEqual([]);
  });
});

describe('orderedByName', () => {
  it('orders the way the server does: lower(name), then id', () => {
    const b = { ...SAVINGS, id: 'b', name: 'savings' };
    const a = { ...SAVINGS, id: 'a', name: 'Savings' };

    expect(orderedByName([CHASE, b, a]).map((one) => one.id)).toEqual(['a1', 'a', 'b']);
  });
});

describe('account types', () => {
  it('labels every type the server has, and does not invent one for a type it does not know', () => {
    expect(accountTypeKey('CHECKING')).toBe('accounts.type.CHECKING');
    expect(accountTypeKey('INVESTMENT')).toBe('accounts.type.INVESTMENT');
    expect(accountTypeKey('BROKERAGE_ISA')).toBe('accounts.type.unknown');
  });
});

describe('isKnownToHaveHistory', () => {
  it('is only true when the server said so', () => {
    expect(isKnownToHaveHistory(CHASE)).toBe(false);
    expect(isKnownToHaveHistory({ ...CHASE, empty: true })).toBe(false);
    expect(isKnownToHaveHistory({ ...CHASE, empty: false })).toBe(true);
  });
});
