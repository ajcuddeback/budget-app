import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/transactions/domain/transaction.dart';

/// Which way the money went. Chosen, never typed: nobody writing down a £42 shop types a minus
/// sign, and asking them to is how a month of income ends up recorded as spending.
enum Direction { out, into }

/// What the entry sheet holds. [amount] is the magnitude as typed.
final class TransactionForm {
  const TransactionForm({
    required this.accountId,
    required this.date,
    required this.direction,
    required this.amount,
    this.payeeName = '',
    this.categoryId,
    this.note = '',
    this.status = TransactionStatus.cleared,
  });

  /// An existing row, for editing it.
  factory TransactionForm.of(Transaction t) => TransactionForm(
    accountId: t.accountId,
    date: t.date,
    direction: t.outgoing ? Direction.out : Direction.into,
    amount: ValidAmount(magnitudeOf(t.wireAmount)),
    payeeName: t.payeeName ?? '',
    categoryId: t.categoryId,
    note: t.note ?? '',
    status: t.status == TransactionStatus.pending
        ? TransactionStatus.pending
        : TransactionStatus.cleared,
  );

  final String accountId;
  final CalendarDate date;
  final Direction direction;
  final ValidAmount amount;
  final String payeeName;

  /// Null is "Needs a category", which is a choice and not a failure to choose.
  final String? categoryId;
  final String note;
  final TransactionStatus status;

  String get signedAmount => direction == Direction.out ? '-${amount.text}' : amount.text;
}

/// The body for `POST /api/transactions`, exactly as the web builds it: optional fields omitted
/// rather than sent empty, since an empty payee name is a validation failure and no payee at all
/// is an ordinary transaction.
Map<String, Object?> createTransactionBody(TransactionForm form) => {
  'accountId': form.accountId,
  'date': form.date.iso,
  'amount': form.signedAmount,
  'status': form.status.wire,
  if (form.payeeName.trim().isNotEmpty) 'payeeName': form.payeeName.trim(),
  if (form.categoryId != null) 'categoryId': form.categoryId,
  if (form.note.trim().isNotEmpty) 'note': form.note.trim(),
};

/// The patch for an edit: the version and **only what changed**.
///
/// `payeeName`, `categoryId` and `note` are three-way: absent keeps, null clears, a value sets.
/// Sending the form wholesale would clear whatever the person had not touched. There is no
/// `accountId` — the server accepts none (transactions.md rule 7). Amounts are compared as
/// decimals, so `-42.5` and the server's `-42.5000` are no change.
Map<String, Object?> transactionPatch(Transaction original, TransactionForm form) {
  final patch = <String, Object?>{'version': original.version};
  if (form.date != original.date) patch['date'] = form.date.iso;
  if (!sameAmount(form.signedAmount, original.wireAmount)) patch['amount'] = form.signedAmount;
  final payee = form.payeeName.trim();
  if (payee != (original.payeeName ?? '')) patch['payeeName'] = payee.isEmpty ? null : payee;
  if (form.categoryId != original.categoryId) patch['categoryId'] = form.categoryId;
  final note = form.note.trim();
  if (note != (original.note ?? '')) patch['note'] = note.isEmpty ? null : note;
  final originalStatus = original.status == TransactionStatus.pending
      ? TransactionStatus.pending
      : original.status == TransactionStatus.cleared
      ? TransactionStatus.cleared
      : null;
  if (originalStatus != null && form.status != originalStatus) patch['status'] = form.status.wire;
  return patch;
}

/// Whether a patch would change anything beyond the version it carries. An untouched sheet sends
/// nothing: a `PATCH` would bump the version and make somebody else's open edit stale.
bool patchIsEmpty(Map<String, Object?> patch) => patch.length == 1;

/// What the transfer sheet holds. Both amounts are unsigned: the direction is the two accounts.
final class TransferForm {
  const TransferForm({
    required this.fromAccountId,
    required this.toAccountId,
    required this.date,
    required this.amount,
    this.amountReceived,
    this.note = '',
  });

  final String fromAccountId;
  final String toAccountId;
  final CalendarDate date;
  final ValidAmount amount;

  /// Only across currencies, where it is required; never sent within one (ADR-0033).
  final ValidAmount? amountReceived;
  final String note;
}

/// `POST /api/transfers`. `amountReceived` is included **when and only when** [crossCurrency]:
/// absent across currencies is a `400`, and so is present within one.
Map<String, Object?> createTransferBody(TransferForm form, {required bool crossCurrency}) => {
  'fromAccountId': form.fromAccountId,
  'toAccountId': form.toAccountId,
  'date': form.date.iso,
  'amount': form.amount.text,
  if (crossCurrency && form.amountReceived != null) 'amountReceived': form.amountReceived!.text,
  if (form.note.trim().isNotEmpty) 'note': form.note.trim(),
};

/// The patch for a transfer edit: the pair's version and only what changed. The date compares
/// with the outgoing leg's, so an untouched date leaves each leg its own (file-import.md rule 12);
/// `amountReceived` is sent only across currencies and only when changed. No account ids.
Map<String, Object?> transferPatch(Transfer original, TransferForm form) {
  final patch = <String, Object?>{'version': original.version};
  if (form.date != original.fromDate) patch['date'] = form.date.iso;
  if (!sameAmount(form.amount.text, original.sentWire)) patch['amount'] = form.amount.text;
  final received = form.amountReceived;
  if (original.crossCurrency &&
      received != null &&
      !sameAmount(received.text, original.receivedWire)) {
    patch['amountReceived'] = received.text;
  }
  final note = form.note.trim();
  if (note != (original.fromNote ?? '')) patch['note'] = note.isEmpty ? null : note;
  return patch;
}
