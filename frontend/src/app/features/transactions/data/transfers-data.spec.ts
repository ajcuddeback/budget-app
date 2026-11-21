import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { I18nService } from '../../../core/i18n/i18n.service';
import { Transaction } from './transaction.models';
import { transferGroupIdOf } from './transaction.models';
import {
  TransferFormValue,
  buildTransferCreate,
  buildTransferPatch,
  isCrossCurrency,
  transferFormValue,
  transferPatchIsEmpty,
} from './transfer-patch';
import { Transfer } from './transfer.models';
import { TransfersService } from './transfers.service';

const CURRENT = { id: 'a1', currency: 'GBP' };
const SAVINGS = { id: 'a2', currency: 'GBP' };
const EUROS = { id: 'a3', currency: 'EUR' };

/** Same currency both sides: one figure, and the legs are equal and opposite. */
const SAME: Transfer = {
  id: 'g1',
  fromAccountId: 'a1',
  fromAccountName: 'Current',
  toAccountId: 'a2',
  toAccountName: 'Savings',
  date: '2026-11-11',
  sent: '200.0000',
  sentCurrency: 'GBP',
  received: '200.0000',
  receivedCurrency: 'GBP',
  crossCurrency: false,
  impliedRate: null,
  note: 'to the pot',
  createdAt: '2026-11-11T09:00:00Z',
  updatedAt: '2026-11-11T09:00:00Z',
  version: 2,
};

/** £200 left and €232.40 arrived. Neither figure is derivable from the other (ADR-0033). */
const CROSS: Transfer = {
  ...SAME,
  id: 'g2',
  toAccountId: 'a3',
  toAccountName: 'Holiday euros',
  received: '232.4000',
  receivedCurrency: 'EUR',
  crossCurrency: true,
  impliedRate: '1.162000',
  note: null,
};

const form = (overrides: Partial<TransferFormValue> = {}): TransferFormValue => ({
  fromAccountId: 'a1',
  toAccountId: 'a2',
  date: '2026-11-11',
  amount: '200',
  amountReceived: '',
  note: '',
  ...overrides,
});

const leg = (overrides: Partial<Transaction> = {}): Transaction => ({
  id: 't1',
  accountId: 'a1',
  accountName: 'Current',
  date: '2026-11-11',
  amount: '-200.0000',
  currency: 'GBP',
  payeeId: null,
  payeeName: null,
  categoryId: null,
  categoryName: null,
  categoryParentName: null,
  note: null,
  status: 'CLEARED',
  transfer: true,
  transferGroupId: 'g1',
  createdAt: '2026-11-11T09:00:00Z',
  updatedAt: '2026-11-11T09:00:00Z',
  version: 1,
  ...overrides,
});

describe('isCrossCurrency', () => {
  it('is true only when both accounts are known and their currencies differ', () => {
    const accounts = [CURRENT, SAVINGS, EUROS];

    expect(isCrossCurrency(accounts, 'a1', 'a3')).toBe(true);
    expect(isCrossCurrency(accounts, 'a1', 'a2')).toBe(false);
    // Nothing chosen yet, and an account this page has not been given — neither is a reason to
    // put a second required field on screen.
    expect(isCrossCurrency(accounts, 'a1', '')).toBe(false);
    expect(isCrossCurrency(accounts, 'a1', 'a9')).toBe(false);
  });
});

describe('buildTransferCreate', () => {
  it('sends both amounts as strings, unsigned, with no category or payee anywhere', () => {
    const request = buildTransferCreate(
      form({ toAccountId: 'a3', amount: '200', amountReceived: '232.40' }),
      true,
    );

    expect(request).toEqual({
      fromAccountId: 'a1',
      toAccountId: 'a3',
      date: '2026-11-11',
      amount: '200',
      amountReceived: '232.40',
    });
    // Strings, from the keystroke to the database (ADR-0006).
    expect(typeof request.amount).toBe('string');
    expect(typeof request.amountReceived).toBe('string');
    // Not "-200": the direction is which account it left.
    expect(request.amount.startsWith('-')).toBe(false);
    expect(Object.keys(request)).not.toContain('categoryId');
    expect(Object.keys(request)).not.toContain('payeeName');
  });

  it('omits the amount received when the two accounts share a currency', () => {
    // The server refuses a second figure there (400): within one currency what arrived is what
    // left, and a figure that silently did nothing would be a client believing a fiction.
    const request = buildTransferCreate(form({ amountReceived: '999' }), false);

    expect(Object.hasOwn(request, 'amountReceived')).toBe(false);
  });

  it('omits an empty note rather than sending a blank one', () => {
    expect(Object.hasOwn(buildTransferCreate(form(), false), 'note')).toBe(false);
    expect(buildTransferCreate(form({ note: '  to the pot  ' }), false).note).toBe('to the pot');
  });
});

describe('buildTransferPatch', () => {
  it('sends the pair version and only what changed', () => {
    const patch = buildTransferPatch(SAME, transferFormValue({ ...SAME, date: '2026-11-11' }));

    // Everything the form holds matches, so the patch is the version and nothing else.
    expect(patch).toEqual({ version: 2 });
  });

  it('never compares amounts as strings', () => {
    // The server echoes "200.0000" for a typed "200". A string comparison would patch every save.
    const patch = buildTransferPatch(SAME, form({ amount: '200', note: 'to the pot' }));

    expect(Object.hasOwn(patch, 'amount')).toBe(false);
  });

  it('carries a changed date, amount and note, and no account ids at all', () => {
    const patch = buildTransferPatch(
      SAME,
      form({ date: '2026-11-12', amount: '250', note: 'rent float' }),
    );

    expect(patch).toEqual({
      version: 2,
      date: '2026-11-12',
      amount: '250',
      note: 'rent float',
    });
    // A transfer's accounts are fixed at creation; the server accepts neither id.
    expect(Object.hasOwn(patch, 'fromAccountId')).toBe(false);
    expect(Object.hasOwn(patch, 'toAccountId')).toBe(false);
  });

  it('clears a note somebody emptied rather than leaving it alone', () => {
    expect(buildTransferPatch(SAME, form({ note: '' })).note).toBeNull();
  });

  it('leaves the receiving leg alone when only the note changed', () => {
    // An absent amountReceived means "unchanged", as an absent field does everywhere in this API.
    // Sending the figure back would be the client restating a fact it was not asked about.
    const patch = buildTransferPatch(
      CROSS,
      form({ toAccountId: 'a3', amountReceived: '232.4000', note: 'holiday money' }),
    );

    expect(patch).toEqual({ version: 2, note: 'holiday money' });
  });

  it('carries what arrived when that is what changed, across currencies only', () => {
    const corrected = buildTransferPatch(
      CROSS,
      form({ toAccountId: 'a3', amountReceived: '230.00' }),
    );
    expect(corrected).toEqual({ version: 2, amountReceived: '230.00' });

    // Within one currency the figure is what `amount` already says, and a second name for it is
    // refused with a 400 — so it is never sent, whatever is in the form.
    const sameCurrency = buildTransferPatch(SAME, form({ amount: '250', amountReceived: '250' }));
    expect(Object.hasOwn(sameCurrency, 'amountReceived')).toBe(false);
    expect(sameCurrency).toEqual({ version: 2, amount: '250', note: null });
  });
});

describe('transferPatchIsEmpty', () => {
  it('reads an untouched form as nothing to save, whatever the scale of the figures', () => {
    expect(transferPatchIsEmpty(buildTransferPatch(SAME, transferFormValue(SAME)))).toBe(true);
    // "200" against the server's "200.0000" is the same money, not an edit.
    expect(
      transferPatchIsEmpty(buildTransferPatch(SAME, form({ amount: '200', note: 'to the pot' }))),
    ).toBe(true);
    expect(transferPatchIsEmpty(buildTransferPatch(CROSS, transferFormValue(CROSS)))).toBe(true);
  });

  it('notices a changed second figure even when everything else is the same', () => {
    const patch = buildTransferPatch(CROSS, {
      ...transferFormValue(CROSS),
      amountReceived: '230.00',
    });

    expect(transferPatchIsEmpty(patch)).toBe(false);
  });
});

describe('transferFormValue', () => {
  it('leaves the second figure empty within one currency, so nothing can send it', () => {
    expect(transferFormValue(SAME).amountReceived).toBe('');
    expect(transferFormValue(CROSS).amountReceived).toBe('232.4000');
  });
});

describe('transferGroupIdOf', () => {
  it('names the pair for a leg, and nothing for anything else', () => {
    expect(transferGroupIdOf(leg())).toBe('g1');
    // An ordinary transaction, exactly as the server sends it.
    expect(transferGroupIdOf(leg({ transfer: false, transferGroupId: null }))).toBeNull();
    // Nonsense from a server this client should not act on the word of: not marked a transfer,
    // but carrying a group id. A guessed pair is one somebody else's money is in.
    expect(transferGroupIdOf(leg({ transfer: false }))).toBeNull();
    expect(transferGroupIdOf(leg({ transferGroupId: null }))).toBeNull();
  });
});

describe('TransfersService', () => {
  let service: TransfersService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TransfersService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates, reads, patches and deletes a transfer by its group id', () => {
    service
      .create({ fromAccountId: 'a1', toAccountId: 'a2', date: '2026-11-11', amount: '200' })
      .subscribe();
    const created = http.expectOne('/api/transfers');
    expect(created.request.method).toBe('POST');
    expect(created.request.body).toEqual({
      fromAccountId: 'a1',
      toAccountId: 'a2',
      date: '2026-11-11',
      amount: '200',
    });
    created.flush(SAME);

    service.get('g1').subscribe();
    const read = http.expectOne('/api/transfers/g1');
    expect(read.request.method).toBe('GET');
    read.flush(SAME);

    service.update('g1', { version: 2, amount: '250' }).subscribe();
    const patched = http.expectOne('/api/transfers/g1');
    expect(patched.request.method).toBe('PATCH');
    // The pair's version: a stale one is a 409 rather than a silently lost correction.
    expect(patched.request.body).toEqual({ version: 2, amount: '250' });
    patched.flush(SAME);

    service.remove('g1').subscribe();
    const removed = http.expectOne('/api/transfers/g1');
    expect(removed.request.method).toBe('DELETE');
    removed.flush(null);
  });

  it('encodes a group id so it cannot alter the path it is placed in', () => {
    service.get('../../accounts').subscribe();

    http.expectOne('/api/transfers/..%2F..%2Faccounts').flush(SAME);
  });
});

describe('I18nService.formatRate', () => {
  it('formats a derived rate for the locale, and nothing that is not one', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const i18n = TestBed.inject(I18nService);
    await i18n.use('en-GB');

    // Six places is what the server derives; the figure is handed to Intl as a string (ADR-0006).
    expect(i18n.formatRate('1.162000')).toBe('1.162');
    expect(i18n.formatRate('1234.5')).toBe('1,234.5');
    // Not a figure we can show honestly, so nothing is shown.
    expect(i18n.formatRate(null)).toBe('');
    expect(i18n.formatRate('one point two')).toBe('');
  });
});
