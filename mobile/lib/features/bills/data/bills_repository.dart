import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/bills/domain/bill.dart';
import 'package:budget_owl/features/bills/domain/requests.dart';
import 'package:budget_owl/shared/year_month.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:meta/meta.dart';

/// Which bills to read: one month's, and whether retired ones come too.
///
/// The Bills tab reads retired bills as well, so the screen lists them apart without a second
/// request that could disagree with the first; the debt dialog offers active ones only.
@immutable
final class BillsQuery {
  const BillsQuery(this.period, {this.includeInactive = true});

  final YearMonth period;
  final bool includeInactive;

  @override
  bool operator ==(Object other) =>
      other is BillsQuery && other.period == period && other.includeInactive == includeInactive;

  @override
  int get hashCode => Object.hash(period, includeInactive);
}

/// `GET /api/bills?period=&includeInactive=` — the month's bills, each with the occurrences its
/// schedule implies. No household id anywhere: the server resolves it (ADR-0017).
final class BillsResource extends CachedResource<BillsPage> {
  BillsResource(this.billsQuery);

  final BillsQuery billsQuery;

  @override
  String get path => '/api/bills';

  @override
  Map<String, String> get query => {
    'period': billsQuery.period.wire,
    'includeInactive': '${billsQuery.includeInactive}',
  };

  @override
  BillsPage decode(JsonMap json) => BillsPage.fromJson(json);
}

final billsProvider = NotifierProvider.autoDispose
    .family<BillsResource, Snapshot<BillsPage>, BillsQuery>(BillsResource.new);

/// `GET /api/income?period=&includeInactive=true` — the month's sources and what they come to.
final class IncomeResource extends CachedResource<IncomeList> {
  IncomeResource(this.period);

  final YearMonth period;

  @override
  String get path => '/api/income';

  @override
  Map<String, String> get query => {'period': period.wire, 'includeInactive': 'true'};

  @override
  IncomeList decode(JsonMap json) => IncomeList.fromJson(json);
}

final incomeProvider = NotifierProvider.autoDispose
    .family<IncomeResource, Snapshot<IncomeList>, YearMonth>(IncomeResource.new);

/// `GET /api/periods/{period}/summary` — **one** request for the headline, so it cannot disagree
/// with the blocks beneath it. The subtraction is the server's; this client only shows it.
final class PeriodSummaryResource extends CachedResource<PeriodSummary> {
  PeriodSummaryResource(this.period);

  final YearMonth period;

  @override
  String get path => '/api/periods/${period.wire}/summary';

  @override
  PeriodSummary decode(JsonMap json) => PeriodSummary.fromJson(json);
}

final periodSummaryProvider = NotifierProvider.autoDispose
    .family<PeriodSummaryResource, Snapshot<PeriodSummary>, YearMonth>(PeriodSummaryResource.new);

/// The month the phone's own calendar is in — a calendar question (the web's `currentPeriod`).
YearMonth currentPeriod(DateTime now) => YearMonth.of(CalendarDate.of(now));

/// Every write Bills & income makes, each sent only when the last request reached the server.
///
/// **Nothing is queued** (mobile-app.md decision 3): offline, a write throws [AppError.offline]
/// without being sent — the screen disables the control first, and this is the second refusal
/// behind it. After a success the data revision is bumped, so every screen re-reads rather than
/// this client patching a figure. Each body is the web's.
final class BillsWriter {
  BillsWriter(this._ref);

  final Ref _ref;

  Future<JsonMap?> _write(String method, String path, [Object? body]) async {
    if (!_ref.read(reachabilityProvider).canWrite) throw const AppError.offline();
    final api = _ref.read(apiClientProvider);
    if (api == null) throw const AppError.unexpected();
    final result = await api.write(method, path, body: body);
    _ref.read(dataRevisionProvider.notifier).bump();
    return result;
  }

  static String _bill(String id) => '/api/bills/${Uri.encodeComponent(id)}';

  static String _occurrence(String billId, CalendarDate dueDate) =>
      '${_bill(billId)}/occurrences/${Uri.encodeComponent(dueDate.iso)}';

  static String _income(String id) => '/api/income/${Uri.encodeComponent(id)}';

  /// Records a status against one occurrence, addressed by **its own due date** — keying on the
  /// month would cap a weekly bill at one settled payment out of four or five.
  Future<void> record(String billId, CalendarDate dueDate, Map<String, Object?> body) =>
      _write('PUT', _occurrence(billId, dueDate), body);

  /// Forgets what was recorded, so the date reads as its projection again. **Not** marking it due.
  Future<void> forget(String billId, CalendarDate dueDate) =>
      _write('DELETE', _occurrence(billId, dueDate));

  Future<void> createBill(BillForm form) => _write('POST', '/api/bills', createBillBody(form));

  /// Sends the patch, or nothing when nothing changed. Returns whether anything was sent.
  Future<bool> updateBill(Bill original, BillForm form) async {
    final patch = billPatch(original, form);
    if (patchIsEmpty(patch)) return false;
    await _write('PATCH', _bill(original.id), patch);
    return true;
  }

  Future<void> setBillActive(Bill bill, {required bool active}) =>
      _write('PATCH', _bill(bill.id), activePatch(bill.version, active: active));

  /// Deletes the bill and what was recorded about it. No transaction is deleted (ADR-0025).
  Future<void> deleteBill(Bill bill) => _write('DELETE', _bill(bill.id));

  Future<void> createIncome(IncomeForm form) =>
      _write('POST', '/api/income', createIncomeBody(form));

  Future<bool> updateIncome(IncomeSource original, IncomeForm form) async {
    final patch = incomePatch(original, form);
    if (patchIsEmpty(patch)) return false;
    await _write('PATCH', _income(original.id), patch);
    return true;
  }

  Future<void> setIncomeActive(IncomeSource source, {required bool active}) =>
      _write('PATCH', _income(source.id), activePatch(source.version, active: active));

  Future<void> deleteIncome(IncomeSource source) => _write('DELETE', _income(source.id));

  /// Removes a mapping a weekly check-in remembered. What was recorded stays recorded.
  Future<void> unmap(BillMapping mapping) =>
      _write('DELETE', '/api/charge-rules/${Uri.encodeComponent(mapping.ruleId)}');
}

final billsWriterProvider = Provider<BillsWriter>(BillsWriter.new);
