import {
  CreateEntryRequest,
  CreateEnvelopeRequest,
  Envelope,
  EnvelopeEntry,
  UpdateEntryRequest,
  UpdateEnvelopeRequest,
} from './envelope.models';

/** What the envelope dialog holds. `categoryId` of `''` means no category. */
export interface EnvelopeFormValue {
  name: string;
  period: string;
  amount: string;
  categoryId: string;
}

export function buildEnvelopeCreate(form: EnvelopeFormValue): CreateEnvelopeRequest {
  const request: CreateEnvelopeRequest = {
    name: form.name.trim(),
    period: form.period,
    amount: form.amount.trim(),
  };
  if (form.categoryId !== '') {
    request.categoryId = form.categoryId;
  }
  return request;
}

/**
 * The patch for an edit: only what changed.
 *
 * `categoryId` is three-way, so the absent case has to be built by *not adding the key*. A client
 * that sent the whole form would clear the category of every envelope it edited.
 */
export function buildEnvelopePatch(
  original: Envelope,
  form: EnvelopeFormValue,
): UpdateEnvelopeRequest {
  const patch: UpdateEnvelopeRequest = { version: original.version };

  const name = form.name.trim();
  if (name !== original.name) {
    patch.name = name;
  }

  const amount = form.amount.trim();
  if (!sameAmount(amount, original.amount)) {
    patch.amount = amount;
  }

  const category = form.categoryId === '' ? null : form.categoryId;
  if (category !== original.categoryId) {
    patch.categoryId = category;
  }

  return patch;
}

/** What the quick-add sheet holds. */
export interface EntryFormValue {
  amount: string;
  date: string;
  payeeName: string;
  note: string;
}

export function buildEntryCreate(form: EntryFormValue): CreateEntryRequest {
  const request: CreateEntryRequest = { amount: form.amount.trim() };
  // Omitted, not sent empty: an empty payee name is a validation failure, while no payee at all
  // is an ordinary entry — cash out of a machine was not "with" anyone.
  if (form.date !== '') {
    request.date = form.date;
  }
  if (form.payeeName.trim() !== '') {
    request.payeeName = form.payeeName.trim();
  }
  if (form.note.trim() !== '') {
    request.note = form.note.trim();
  }
  return request;
}

export function buildEntryPatch(
  original: EnvelopeEntry,
  form: EntryFormValue,
): UpdateEntryRequest {
  const patch: UpdateEntryRequest = { version: original.version };

  if (form.date !== original.date) {
    patch.date = form.date;
  }

  const amount = form.amount.trim();
  if (!sameAmount(amount, original.amount)) {
    patch.amount = amount;
  }

  const payee = form.payeeName.trim();
  if (payee !== (original.payeeName ?? '')) {
    patch.payeeName = payee === '' ? null : payee;
  }

  const note = form.note.trim();
  if (note !== (original.note ?? '')) {
    patch.note = note === '' ? null : note;
  }

  return patch;
}

/** Whether a patch would change anything beyond the version it has to carry. */
export function patchIsEmpty(patch: { version: number }): boolean {
  return Object.keys(patch).length === 1;
}

/**
 * Whether two decimal strings are the same amount.
 *
 * Compared digit by digit after normalising, never through `Number()`: the server echoes
 * `400.0000` for an amount typed as `400`, so a string comparison would send a pointless patch
 * after every edit — and a double cannot hold every `NUMERIC(19,4)` anyway (ADR-0006).
 */
function sameAmount(left: string, right: string): boolean {
  return normalise(left) === normalise(right);
}

function normalise(amount: string): string {
  const [whole, fraction = ''] = amount.trim().replace(/^\+/, '').split('.');
  const wholeDigits = whole.replace(/^0+(?=\d)/, '') || '0';
  const fractionDigits = fraction.replace(/0+$/, '');
  return fractionDigits === '' ? wholeDigits : `${wholeDigits}.${fractionDigits}`;
}
