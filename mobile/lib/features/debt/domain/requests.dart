import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/debt/domain/debt.dart';
import 'package:budget_owl/shared/figures.dart';
import 'package:budget_owl/shared/year_month.dart';

/// The debt request bodies, built exactly as the web's `debt-patch.ts` builds them — and in
/// particular **the three-way `PATCH`** (debt-plan.md, API):
///
/// | the body carries | it means |
/// |---|---|
/// | the key is **absent** | leave the field exactly as it is |
/// | the key is **`null`** | **release** it: the server solves it, and it reads `Solved` |
/// | the key carries a **value** | enter it, and the solver works around it |
///
/// So a key is added only when a change says so; nothing is inferred from emptiness. A client that
/// sent the whole form would release every field the household had entered, silently.

/// What a form says about one solver field.
sealed class TermChange<T> {
  const TermChange();
}

/// Not mentioned: the stored value stands, and no key is sent.
final class Unchanged<T> extends TermChange<T> {
  const Unchanged();
}

/// Handed back to the solver: sent as an explicit `null`.
final class Released<T> extends TermChange<T> {
  const Released();
}

/// The household typed it.
final class Entered<T> extends TermChange<T> {
  const Entered(this.value);
  final T value;
}

/// A `PATCH` body carrying the version and exactly the solver instructions it was given.
Map<String, Object?> termsPatch(
  int version, {
  TermChange<String> apr = const Unchanged(),
  TermChange<String> payment = const Unchanged(),
  TermChange<int> monthsLeft = const Unchanged(),
  TermChange<String> payoffMonth = const Unchanged(),
}) {
  final patch = <String, Object?>{'version': version};
  void put<T>(String key, TermChange<T> change) {
    switch (change) {
      case Unchanged():
        break;
      case Released():
        patch[key] = null;
      case Entered(:final value):
        patch[key] = value;
    }
  }

  put('apr', apr);
  put('payment', payment);
  put('monthsLeft', monthsLeft);
  put('payoffMonth', payoffMonth);
  return patch;
}

/// What the add / edit sheet holds. Every amount is the text typed.
final class DebtForm {
  const DebtForm({
    required this.name,
    required this.kind,
    required this.balance,
    required this.balanceDate,
    required this.minimumPayment,
    this.servicer = '',
    this.currency = '',
    this.apr = '',
    this.aprSolved = false,
    this.originalBalance = '',
    this.accountId,
    this.billId,
  });

  /// The server's debt, for an edit. A solved rate opens empty with the box ticked, so the field
  /// never presents a figure the household did not assert as though they had.
  factory DebtForm.of(Debt debt) => DebtForm(
    name: debt.name,
    kind: DebtKind.parse(debt.kind) ?? DebtKind.creditCard,
    servicer: debt.servicer ?? '',
    currency: debt.currency,
    balance: trimAmount(debt.balance),
    balanceDate: debt.balanceAsOf,
    apr: debt.aprSource == TermSource.entered ? trimAmount(debt.apr ?? '') : '',
    aprSolved: debt.aprSource != TermSource.entered,
    minimumPayment: trimAmount(debt.minimumPayment),
    originalBalance: debt.originalBalance == null ? '' : trimAmount(debt.originalBalance!),
    accountId: debt.accountId,
    billId: debt.billId,
  );

  final String name;
  final DebtKind kind;

  /// Empty means none — on an edit, a **clear**.
  final String servicer;

  /// Empty means the household's own; offered only when creating, with no account named.
  final String currency;
  final String balance;

  /// The date the balance was true: with the balance, the anchor.
  final CalendarDate balanceDate;
  final String apr;

  /// "Work the rate out for me" — the release, as an interaction. Edit only.
  final bool aprSolved;
  final String minimumPayment;

  /// Empty means absent, and progress is then absent rather than 0%.
  final String originalBalance;
  final String? accountId;

  /// The bill that pays its minimum, so the minimum is counted once.
  final String? billId;
}

/// `POST /api/debts`. **The rate is required here**: solving for one needs a payment and a
/// horizon, which this form does not collect.
Map<String, Object?> createDebtBody(DebtForm form) => {
  'name': form.name.trim(),
  'kind': form.kind.wire,
  'balance': form.balance.trim(),
  'balanceDate': form.balanceDate.iso,
  'minimumPayment': form.minimumPayment.trim(),
  if (form.apr.trim().isNotEmpty) 'apr': form.apr.trim(),
  if (form.servicer.trim().isNotEmpty) 'servicer': form.servicer.trim(),
  if (form.currency.trim().isNotEmpty && form.accountId == null)
    'currency': form.currency.trim().toUpperCase(),
  if (form.originalBalance.trim().isNotEmpty) 'originalBalance': form.originalBalance.trim(),
  if (form.accountId != null) 'accountId': form.accountId,
  if (form.billId != null) 'billId': form.billId,
};

/// `PATCH /api/debts/{id}`: only what changed, with the rate's three-way answer made explicit.
///
/// **The balance is not here.** It is never solved and never patched — it moves through
/// `POST /api/debts/{id}/balance`, which keeps its date and its provenance.
Map<String, Object?> debtPatch(Debt original, DebtForm form) {
  final patch = termsPatch(original.version, apr: _aprChange(original, form));
  final name = form.name.trim();
  if (name != original.name) patch['name'] = name;
  if (form.kind.wire != original.kind) patch['kind'] = form.kind.wire;
  final minimum = form.minimumPayment.trim();
  if (!sameAmount(minimum, original.minimumPayment)) patch['minimumPayment'] = minimum;
  // Nullable text and the links: absent keeps them, null clears them.
  final servicer = form.servicer.trim().isEmpty ? null : form.servicer.trim();
  if (servicer != original.servicer) patch['servicer'] = servicer;
  final originalBalance = form.originalBalance.trim();
  if (originalBalance.isEmpty) {
    if (original.originalBalance != null) patch['originalBalance'] = null;
  } else if (original.originalBalance == null ||
      !sameAmount(originalBalance, original.originalBalance!)) {
    patch['originalBalance'] = originalBalance;
  }
  if (form.accountId != original.accountId) patch['accountId'] = form.accountId;
  if (form.billId != original.billId) patch['billId'] = form.billId;
  return patch;
}

/// What the form says about the rate. The two middle cases are the ones a careless client gets
/// wrong: ticked on an entered rate **releases** it (explicit null); ticked on an already-solved
/// rate says **nothing** (no key); the same figure says nothing either.
TermChange<String> _aprChange(Debt original, DebtForm form) {
  final typed = form.apr.trim();
  if (form.aprSolved) {
    return original.aprSource == TermSource.entered ? const Released() : const Unchanged();
  }
  if (typed.isEmpty) return const Unchanged();
  final stored = original.aprSource == TermSource.entered ? original.apr : null;
  return stored != null && sameAmount(typed, stored) ? const Unchanged() : Entered(typed);
}

/// What the log-a-payment sheet holds.
final class PaymentForm {
  const PaymentForm({
    required this.paidDate,
    required this.amount,
    this.paidFromAccountId,
    this.note = '',
  });

  final CalendarDate paidDate;
  final String amount;

  /// Where the money left — **not** a transaction, and not where the debt is held.
  final String? paidFromAccountId;
  final String note;
}

/// `POST /api/debts/{id}/payments`. **The split is not sent and never computed here**: the server
/// works out the interest and the principal under the rate in force and reports them back.
Map<String, Object?> logPaymentBody(PaymentForm form) => {
  'paidDate': form.paidDate.iso,
  'amount': form.amount.trim(),
  if (form.paidFromAccountId != null) 'paidFromAccountId': form.paidFromAccountId,
  if (form.note.trim().isNotEmpty) 'note': form.note.trim(),
};

/// `POST /api/debts/{id}/payments/preview`: the amount in a body, never a URL, and the date the
/// payment will carry — which month's interest it carries depends on it (ADR-0043).
Map<String, Object?> previewBody(String amount, CalendarDate paidDate) => {
  'amount': amount.trim(),
  'paidDate': paidDate.iso,
};

/// `POST /api/debts/{id}/balance`: what a statement says, and when that was true. A statement wins.
Map<String, Object?> confirmBalanceBody({
  required String balance,
  required CalendarDate balanceDate,
  String note = '',
}) => {
  'balance': balance.trim(),
  'balanceDate': balanceDate.iso,
  if (note.trim().isNotEmpty) 'note': note.trim(),
};

/// `PUT /api/debt-plan`, choosing a named strategy.
Map<String, Object?> strategyBody(int version, Strategy strategy) => {
  'version': version,
  'strategy': strategy.wire,
};

/// `PUT /api/debt-plan` with **the whole order**: a debt the list leaves out has its position
/// cleared, so one currency's ladder alone would unplace the other's.
Map<String, Object?> customOrderBody(int version, List<String> order) => {
  'version': version,
  'strategy': Strategy.custom.wire,
  'customOrder': order,
};

/// `PUT /api/debt-plan`, the monthly amount every date on the page derives from.
Map<String, Object?> monthlyAmountBody(int version, String amount) => {
  'version': version,
  'monthlyAmount': amount.trim(),
};

/// Whether a patch would change anything beyond the version it carries.
bool patchIsEmpty(Map<String, Object?> patch) => patch.length == 1;

/// How many months from one `YYYY-MM` to another — the only delta the what-if states. Date
/// arithmetic, never money. Null when either is absent: an unknown delta is left out, not a zero.
int? monthsBetween(String? from, String? to) {
  final start = YearMonth.tryParse(from);
  final end = YearMonth.tryParse(to);
  return start == null || end == null ? null : start.monthsUntil(end);
}

/// The month a line reaches zero in. A point is what is owed at the **start** of its month, so the
/// first point at zero is the month after it cleared — the month before that is the answer, which
/// for a debt's own pace reproduces `payoffMonth` exactly.
String? clearedMonth(List<SeriesPoint> points) {
  for (final point in points) {
    if (signOf(point.balance) == 0) return YearMonth.tryParse(point.month)?.shift(-1).wire;
  }
  return null;
}
