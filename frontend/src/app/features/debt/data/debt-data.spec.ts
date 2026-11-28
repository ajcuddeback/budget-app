import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  DebtFormValue,
  buildConfirmBalance,
  buildDebtCreate,
  buildDebtPatch,
  buildLogPayment,
  buildTermsPatch,
  debtFormFrom,
  entered,
  monthsBetween,
  newDebtForm,
  patchIsEmpty,
  released,
  unchanged,
} from './debt-patch';
import {
  Debt,
  balanceSourceKey,
  debtKindKey,
  isPayoffStrategy,
  projectionReasonKey,
  provenanceKey,
  strategyKey,
  termSourceKey,
} from './debt.models';
import { DebtPlanService } from './debt-plan.service';
import { DebtTermsService } from './debt-terms.service';
import { DebtsService } from './debts.service';

/** A card whose rate the household entered, as the server reports it. */
const VISA: Debt = {
  id: 'd1',
  name: 'Visa',
  kind: 'CREDIT_CARD',
  servicer: 'Chase',
  currency: 'GBP',
  balance: '6180.0000',
  balanceSource: 'CONFIRMED',
  balanceAsOf: '2026-09-01',
  estimatedMonths: 0,
  balanceStale: false,
  anchorBalance: '6180.0000',
  anchorDate: '2026-09-01',
  apr: '22.900',
  aprSource: 'ENTERED',
  aprDerivable: true,
  minimumPayment: '118.0000',
  originalBalance: '10650.0000',
  paidOffSoFar: '4470.0000',
  progressPercent: '41.972',
  payment: '445.0000',
  paymentSource: 'FROM_PLAN',
  monthsLeft: 16,
  payoffMonth: '2027-04',
  horizonSource: 'SOLVED',
  totalInterestLeft: '890.0000',
  projectionReason: null,
  rung: 1,
  planMinimum: '118.0000',
  planExtra: '327.0000',
  accountId: null,
  billId: null,
  customOrder: null,
  archived: false,
  paidOffAt: null,
  paymentCount: 7,
  version: 3,
};

/** The same debt after the household handed the rate back to the solver. */
const SOLVED_RATE: Debt = { ...VISA, apr: '19.422', aprSource: 'SOLVED' };

function formFor(debt: Debt, changes: Partial<DebtFormValue> = {}): DebtFormValue {
  return { ...debtFormFrom(debt), ...changes };
}

describe('the three-way PATCH', () => {
  it('sends a released rate as an explicit null, which survives serialization', () => {
    const patch = buildDebtPatch(VISA, formFor(VISA, { apr: '', aprSolved: true }));

    expect('apr' in patch).toBe(true);
    expect(patch.apr).toBeNull();
    // The whole mechanism: an absent property is dropped by JSON.stringify and an explicit null
    // is not. A test on the object alone would pass for a body that never carried the release.
    expect(JSON.parse(JSON.stringify(patch))).toHaveProperty('apr', null);
  });

  it('omits the rate entirely when it did not change, rather than re-asserting it', () => {
    const patch = buildDebtPatch(VISA, formFor(VISA, { name: 'Visa card' }));

    expect('apr' in patch).toBe(false);
    expect(JSON.stringify(patch)).not.toContain('apr');
    expect(patch.name).toBe('Visa card');
  });

  it('says nothing about a rate that is already solved, because there is nothing to release', () => {
    const patch = buildDebtPatch(SOLVED_RATE, formFor(SOLVED_RATE, { name: 'Visa card' }));

    expect('apr' in patch).toBe(false);
  });

  it('enters a rate the household typed over a solved one', () => {
    const patch = buildDebtPatch(
      SOLVED_RATE,
      formFor(SOLVED_RATE, { apr: '22.9', aprSolved: false }),
    );

    expect(patch.apr).toBe('22.9');
  });

  it('treats 22.9 and 22.900 as the same rate, so an unrelated edit does not touch it', () => {
    const patch = buildDebtPatch(VISA, formFor(VISA, { apr: '22.9', name: 'Visa card' }));

    expect('apr' in patch).toBe(false);
  });

  it('keeps absent, released and entered apart on every solver field', () => {
    const patch = buildTermsPatch(7, {
      apr: released(),
      payment: entered('445.00'),
      monthsLeft: unchanged(),
      payoffMonth: released(),
    });

    expect(patch).toEqual({ version: 7, apr: null, payment: '445.00', payoffMonth: null });
    expect('monthsLeft' in patch).toBe(false);
    const body = JSON.parse(JSON.stringify(patch)) as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(['apr', 'payment', 'payoffMonth', 'version']);
  });

  it('mentions nothing at all when nothing was said', () => {
    const patch = buildTermsPatch(7, {});

    expect(patch).toEqual({ version: 7 });
    expect(patchIsEmpty(patch)).toBe(true);
  });

  it('entering one face of the horizon does not volunteer a release of the other', () => {
    const patch = buildTermsPatch(2, { monthsLeft: entered(24) });

    // The server clears the other face on the stored row. A client that sent `payoffMonth: null`
    // here would be releasing a field nobody asked it to.
    expect(patch).toEqual({ version: 2, monthsLeft: 24 });
  });

  it('clears the servicer with a null and leaves it alone when untouched', () => {
    expect(buildDebtPatch(VISA, formFor(VISA, { servicer: '   ' })).servicer).toBeNull();
    expect('servicer' in buildDebtPatch(VISA, formFor(VISA))).toBe(false);
    expect(buildDebtPatch(VISA, formFor(VISA, { servicer: 'Chase Bank' })).servicer).toBe(
      'Chase Bank',
    );
  });

  it('clears the starting balance with a null, so progress becomes absent rather than 0%', () => {
    expect(buildDebtPatch(VISA, formFor(VISA, { originalBalance: '' })).originalBalance).toBeNull();
    expect('originalBalance' in buildDebtPatch(VISA, formFor(VISA))).toBe(false);
  });

  it('clears the bill link with a null, which starts counting that minimum into what is left', () => {
    const linked: Debt = { ...VISA, billId: 'b9' };

    expect(buildDebtPatch(linked, formFor(linked, { billId: '' })).billId).toBeNull();
    expect(buildDebtPatch(VISA, formFor(VISA, { billId: 'b9' })).billId).toBe('b9');
  });

  it('carries the version so a concurrent edit is a 409 and not a silent loss', () => {
    expect(buildDebtPatch(VISA, formFor(VISA, { name: 'Visa card' })).version).toBe(3);
  });

  it('never patches the balance, which moves through its own endpoint with its date', () => {
    const patch = buildDebtPatch(
      VISA,
      formFor(VISA, { balance: '1.00', balanceDate: '2026-01-01' }),
    );

    expect('balance' in patch).toBe(false);
    expect(patchIsEmpty(patch)).toBe(true);
  });

  it('sends amounts as the strings they were typed as, trimmed and never parsed', () => {
    const patch = buildDebtPatch(VISA, formFor(VISA, { minimumPayment: ' 118.4400 ' }));

    expect(patch.minimumPayment).toBe('118.4400');
    expect(typeof patch.minimumPayment).toBe('string');
  });

  it('treats 118 and 118.0000 as the same minimum', () => {
    expect('minimumPayment' in buildDebtPatch(VISA, formFor(VISA, { minimumPayment: '118' }))).toBe(
      false,
    );
  });
});

describe('buildDebtCreate', () => {
  const filled: DebtFormValue = {
    ...newDebtForm(),
    name: '  Car loan  ',
    kind: 'INSTALLMENT',
    servicer: 'Alpine Auto Finance',
    balance: '10650.00',
    balanceDate: '2026-09-01',
    apr: '6.4',
    minimumPayment: '342.00',
  };

  it('sends the figures as strings, with the date the balance was true', () => {
    expect(buildDebtCreate(filled)).toEqual({
      name: 'Car loan',
      kind: 'INSTALLMENT',
      servicer: 'Alpine Auto Finance',
      balance: '10650.00',
      balanceDate: '2026-09-01',
      apr: '6.4',
      minimumPayment: '342.00',
    });
  });

  it('omits what nobody filled in rather than sending blanks the server would refuse', () => {
    const request = buildDebtCreate({ ...filled, servicer: '', originalBalance: '' });

    expect('servicer' in request).toBe(false);
    expect('originalBalance' in request).toBe(false);
    expect('accountId' in request).toBe(false);
    expect('billId' in request).toBe(false);
    expect('currency' in request).toBe(false);
  });

  it('names a currency only when one was chosen, so the household default applies otherwise', () => {
    expect(buildDebtCreate({ ...filled, currency: 'EUR' }).currency).toBe('EUR');
  });
});

describe('debtFormFrom', () => {
  it('shows an entered rate in the field with the box unticked', () => {
    const form = debtFormFrom(VISA);

    expect(form.apr).toBe('22.9');
    expect(form.aprSolved).toBe(false);
  });

  it('shows a solved rate as an empty field with the box ticked, never as the household’s own', () => {
    const form = debtFormFrom(SOLVED_RATE);

    expect(form.apr).toBe('');
    expect(form.aprSolved).toBe(true);
  });
});

describe('buildLogPayment', () => {
  it('sends the date and the amount, and never a split it worked out itself', () => {
    const request = buildLogPayment({
      paidDate: '2026-09-15',
      amount: ' 118.00 ',
      paidFromAccountId: '',
      transactionId: '',
      note: '  ',
    });

    expect(request).toEqual({ paidDate: '2026-09-15', amount: '118.00' });
    // The server computes the interest and the principal under the rate in force and reports them
    // back. A client that sent its own would be storing its arithmetic as somebody's history.
    expect('interest' in request).toBe(false);
    expect('principal' in request).toBe(false);
  });

  it('carries the ledger row when one was named, as evidence rather than authority', () => {
    expect(
      buildLogPayment({
        paidDate: '2026-09-15',
        amount: '118.00',
        paidFromAccountId: '',
        transactionId: 't7',
        note: 'x',
      }),
    ).toEqual({ paidDate: '2026-09-15', amount: '118.00', transactionId: 't7', note: 'x' });
  });

  it('names the account the money left, which is not the account the debt is held at', () => {
    const request = buildLogPayment({
      paidDate: '2026-09-15',
      amount: '118.00',
      paidFromAccountId: 'acc-9',
      transactionId: '',
      note: '',
    });

    // `paidFromAccountId` is where the charge will be looked for in slice 16. `Debt.accountId` is
    // where the card is held. Conflating them would send the matching to the wrong ledger.
    expect(request.paidFromAccountId).toBe('acc-9');
    expect('accountId' in request).toBe(false);
  });

  it('omits the account when the money came from somewhere Budget Owl does not track', () => {
    const request = buildLogPayment({
      paidDate: '2026-09-15',
      amount: '118.00',
      paidFromAccountId: '',
      transactionId: '',
      note: '',
    });

    expect('paidFromAccountId' in request).toBe(false);
  });

  it('confirms a balance with the date it was true, and drops an empty note', () => {
    expect(
      buildConfirmBalance({ balance: ' 8900.00 ', balanceDate: '2026-09-01', note: '  ' }),
    ).toEqual({ balance: '8900.00', balanceDate: '2026-09-01' });
    expect(
      buildConfirmBalance({ balance: '8900.00', balanceDate: '2026-09-01', note: 'Chase' }),
    ).toEqual({ balance: '8900.00', balanceDate: '2026-09-01', note: 'Chase' });
  });
});

describe('monthsBetween', () => {
  it('counts whole months, which is the only delta monthly compounding can support', () => {
    expect(monthsBetween('2027-01', '2027-04')).toBe(3);
    expect(monthsBetween('2026-11', '2027-02')).toBe(3);
    expect(monthsBetween('2027-04', '2027-04')).toBe(0);
    expect(monthsBetween('2027-04', '2027-01')).toBe(-3);
  });

  it('answers null for an absent or malformed month rather than a misleading zero', () => {
    expect(monthsBetween(null, '2027-04')).toBeNull();
    expect(monthsBetween('2027-04', null)).toBeNull();
    expect(monthsBetween('2027-13', '2027-04')).toBeNull();
    expect(monthsBetween('April 2027', '2027-04')).toBeNull();
  });
});

describe('the display helpers', () => {
  it('gives a neutral word for anything this client has never heard of', () => {
    expect(termSourceKey('WHATEVER')).toBe('debt.source.unknown');
    expect(debtKindKey('MORTGAGE')).toBe('debt.kind.unknown');
    expect(balanceSourceKey('GUESSED')).toBe('debt.balanceSource.unknown');
    expect(provenanceKey('IMPORTED')).toBe('debt.provenance.unknown');
    expect(strategyKey('CHAOS')).toBe('debt.strategy.unknown');
    expect(projectionReasonKey('who-knows')).toBe('debt.reason.unknown');
  });

  it('names the three term sources it does know', () => {
    expect(termSourceKey('SOLVED')).toBe('debt.source.SOLVED');
    expect(termSourceKey('FROM_PLAN')).toBe('debt.source.FROM_PLAN');
    expect(projectionReasonKey('never-clears')).toBe('debt.reason.neverClears');
  });

  it('recognises the strategies the plan can be set to', () => {
    expect(isPayoffStrategy('SNOWBALL')).toBe(true);
    expect(isPayoffStrategy('snowball')).toBe(false);
  });
});

describe('DebtsService', () => {
  let service: DebtsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(DebtsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the ladder without archived debts unless asked', () => {
    service.list().subscribe();
    const request = http.expectOne((candidate) => candidate.url === '/api/debts');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('includeArchived')).toBe('false');
    request.flush({
      debts: [],
      strategy: 'AVALANCHE',
      currency: 'GBP',
      owed: '0.0000',
      debtMinimums: '0.0000',
      debtCount: 0,
    });
  });

  it('creates, patches and deletes a debt with the method each one means', () => {
    service
      .create(
        buildDebtCreate({
          ...newDebtForm(),
          name: 'Visa',
          balance: '1',
          balanceDate: '2026-09-01',
          apr: '1',
          minimumPayment: '1',
        }),
      )
      .subscribe();
    expect(http.expectOne('/api/debts').request.method).toBe('POST');

    service.update('d1', { version: 1, apr: null }).subscribe();
    const patched = http.expectOne('/api/debts/d1');
    expect(patched.request.method).toBe('PATCH');
    // The release reaches the wire as a null, not as an omission.
    expect(patched.request.body).toEqual({ version: 1, apr: null });

    service.remove('d1').subscribe();
    expect(http.expectOne('/api/debts/d1').request.method).toBe('DELETE');

    http.expectNone('/api/debts');
  });

  it('retires and restores through sub-resources rather than a field on the patch', () => {
    service.archive('d1').subscribe();
    expect(http.expectOne('/api/debts/d1/archive').request.method).toBe('POST');

    service.unarchive('d1').subscribe();
    expect(http.expectOne('/api/debts/d1/unarchive').request.method).toBe('POST');
  });

  it('confirms a balance through its own endpoint, with the date it was true', () => {
    service.confirmBalance('d1', { balance: '6180.00', balanceDate: '2026-09-01' }).subscribe();
    const request = http.expectOne('/api/debts/d1/balance');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ balance: '6180.00', balanceDate: '2026-09-01' });
  });

  it('asks for the payment history one page at a time', () => {
    service.payments('d1', 2, 25).subscribe();
    const request = http.expectOne((candidate) => candidate.url === '/api/debts/d1/payments');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('25');
  });

  it('logs, corrects and removes a payment', () => {
    service.logPayment('d1', { paidDate: '2026-09-15', amount: '118.00' }).subscribe();
    expect(http.expectOne('/api/debts/d1/payments').request.method).toBe('POST');

    service.correctPayment('d1', 'p9', { version: 1, transactionId: null }).subscribe();
    const corrected = http.expectOne('/api/debts/d1/payments/p9');
    expect(corrected.request.method).toBe('PATCH');
    expect(corrected.request.body).toEqual({ version: 1, transactionId: null });

    service.removePayment('d1', 'p9').subscribe();
    expect(http.expectOne('/api/debts/d1/payments/p9').request.method).toBe('DELETE');
  });

  it('escapes an id rather than pasting it into a path', () => {
    service.get('a/b').subscribe();
    http.expectOne('/api/debts/a%2Fb').flush(null);
  });
});

describe('DebtPlanService', () => {
  let service: DebtPlanService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(DebtPlanService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads and writes one plan, with no id anywhere', () => {
    service.get().subscribe();
    expect(http.expectOne('/api/debt-plan').request.method).toBe('GET');

    service.update({ version: 4, strategy: 'SNOWBALL' }).subscribe();
    const saved = http.expectOne('/api/debt-plan');
    expect(saved.request.method).toBe('PUT');
    expect(saved.request.body).toEqual({ version: 4, strategy: 'SNOWBALL' });
  });

  it('asks the what-if as a GET that writes nothing, with the amount as a string', () => {
    service.projection({ monthlyAmount: '610.0000' }).subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/debt-plan/projection');
    expect(request.request.method).toBe('GET');
    expect(request.request.body).toBeNull();
    expect(request.request.params.get('monthlyAmount')).toBe('610.0000');
    expect(request.request.params.has('strategy')).toBe(false);
  });

  it('omits a parameter nobody chose rather than sending an empty one', () => {
    service.projection().subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/debt-plan/projection');
    expect(request.request.params.keys()).toEqual([]);
  });
});

describe('DebtTermsService', () => {
  it('posts the term set, because amounts do not belong in a URL', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const service = TestBed.inject(DebtTermsService);
    const http = TestBed.inject(HttpTestingController);

    service.solve({ balance: '6180.0000', apr: '22.900', payment: '445.0000' }).subscribe();

    const request = http.expectOne('/api/debt-terms/solve');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      balance: '6180.0000',
      apr: '22.900',
      payment: '445.0000',
    });
    http.verify();
  });
});
