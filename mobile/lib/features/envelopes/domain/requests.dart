import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/envelopes/domain/envelope.dart';

/// What the envelope sheet holds. [categoryId] `''` is "No category", an ordinary choice.
final class EnvelopeForm {
  const EnvelopeForm({
    required this.name,
    required this.period,
    required this.amount,
    this.categoryId = '',
  });

  final String name;
  final String period;
  final String amount;
  final String categoryId;
}

/// `POST /api/envelopes`, as the web builds it: the category **absent**, not null, when none.
Map<String, Object?> createEnvelopeBody(EnvelopeForm form) => {
  'name': form.name.trim(),
  'period': form.period,
  'amount': form.amount.trim(),
  if (form.categoryId.isNotEmpty) 'categoryId': form.categoryId,
};

/// The patch for an edit: the version and only what changed. There is **no `period`** — moving an
/// envelope between months would carry its entries with it. `categoryId` is three-way: absent
/// keeps it, `null` clears it, an id sets it; sending the whole form would clear the category of
/// every envelope anybody renamed. Amounts compare as decimals (`600` is the server's `600.0000`).
Map<String, Object?> envelopePatch(Envelope original, EnvelopeForm form) {
  final patch = <String, Object?>{'version': original.version};
  final name = form.name.trim();
  if (name != original.name) patch['name'] = name;
  final amount = form.amount.trim();
  if (!sameAmount(amount, original.amountWire)) patch['amount'] = amount;
  final category = form.categoryId.isEmpty ? null : form.categoryId;
  if (category != original.categoryId) patch['categoryId'] = category;
  return patch;
}

/// What the quick-add sheet holds: an amount, and everything else optional.
final class EntryForm {
  const EntryForm({required this.amount, required this.date, this.payeeName = '', this.note = ''});

  final String amount;
  final CalendarDate date;
  final String payeeName;
  final String note;
}

/// `POST /api/envelopes/{id}/entries` with an amount, as the web's quick add sends it: the date
/// always (the sheet opens on today), payee and note **omitted** when empty — an empty payee name
/// is a validation failure, and cash out of a machine was not "with" anyone.
Map<String, Object?> createEntryBody(EntryForm form) => {
  'amount': form.amount.trim(),
  'date': form.date.iso,
  if (form.payeeName.trim().isNotEmpty) 'payeeName': form.payeeName.trim(),
  if (form.note.trim().isNotEmpty) 'note': form.note.trim(),
};

/// The patch for correcting an entry: the version and only what changed. `payeeName` and `note`
/// are three-way, as on a transaction: absent keeps, null clears, a value sets.
Map<String, Object?> entryPatch(EnvelopeEntry original, EntryForm form) {
  final patch = <String, Object?>{'version': original.version};
  if (form.date != original.date) patch['date'] = form.date.iso;
  final amount = form.amount.trim();
  if (!sameAmount(amount, original.amountWire)) patch['amount'] = amount;
  final payee = form.payeeName.trim();
  if (payee != (original.payeeName ?? '')) patch['payeeName'] = payee.isEmpty ? null : payee;
  final note = form.note.trim();
  if (note != (original.note ?? '')) patch['note'] = note.isEmpty ? null : note;
  return patch;
}

/// Whether a patch would change anything beyond the version it carries.
bool patchIsEmpty(Map<String, Object?> patch) => patch.length == 1;

final RegExp _unsigned = RegExp(r'^[0-9]{1,15}(\.[0-9]{1,4})?$');

/// An unsigned decimal that fits `NUMERIC(19,4)` — the server's `EnvelopeRequests.AMOUNT`, and the
/// web's. A minus is a mistake here (spending is the direction), so it is refused, not folded in.
/// Never parsed: what was typed is what is sent.
bool isEnvelopeAmount(String typed) => _unsigned.hasMatch(typed.trim());

/// An entry's amount reopened in the form: `86.4000` is `86.40`.
String entryAmountText(EnvelopeEntry entry) => magnitudeOf(entry.amountWire);

/// An envelope's amount reopened in the form.
String envelopeAmountText(Envelope envelope) => magnitudeOf(envelope.amountWire);
