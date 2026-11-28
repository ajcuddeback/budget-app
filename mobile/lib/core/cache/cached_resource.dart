import 'dart:async';

import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// What a screen has to show for one API read: the data (cached or fresh), when it was fetched,
/// and whether the latest attempt to refresh it failed.
final class Snapshot<T> {
  const Snapshot({this.data, this.fetchedAt, this.loading = false, this.error});

  const Snapshot.loading() : this(loading: true);

  final T? data;
  final DateTime? fetchedAt;

  /// A refresh is in flight.
  final bool loading;

  /// Why the latest refresh failed, when it did. With [data] present, the copy shown is stale and
  /// **must** be labelled (mobile-app.md: "A screen never shows a cached figure without that label
  /// once a refresh has failed").
  final AppError? error;

  bool get hasData => data != null;

  bool get isStale => data != null && error != null;

  Snapshot<T> refreshing() =>
      Snapshot(data: data, fetchedAt: fetchedAt, loading: true, error: error);
}

/// Bumped after every successful write, so every live read refreshes from the server.
///
/// The app never patches a cached figure itself (mobile-app.md, Writes): a write's effect on a
/// balance is the server's to compute, so the screens that show one ask again.
final class DataRevision extends Notifier<int> {
  @override
  int build() => 0;

  void bump() => state++;
}

final dataRevisionProvider = NotifierProvider<DataRevision, int>(DataRevision.new);

/// A read through the encrypted cache: **the last cached copy first, then the API**.
///
/// The cached copy is replaced only by a successful response. A failed refresh keeps the copy and
/// records why, which is what the stale label reads. Only first pages are cached ([cacheable]),
/// so a member scrolling a long ledger cannot grow the cache past what one screen needs; the cache
/// itself is capped at 50 MB besides.
///
/// A feature's repository is one subclass: a [path], a [query], and [decode]. See
/// mobile/README.md, "Adding a feature".
abstract class CachedResource<T> extends Notifier<Snapshot<T>> {
  /// The API path, e.g. `/api/transactions`.
  String get path;

  /// Query parameters, sent as given. Never a household id: the server resolves it (ADR-0017).
  Map<String, String> get query => const {};

  /// Whether this response may be cached. Override to false for anything past a first page, or
  /// a free-text search whose every variant would be another entry.
  bool get cacheable => true;

  /// The body as a typed model. Throw [UnexpectedJson] for a shape this client cannot read.
  T decode(JsonMap json);

  ({int epoch, String? household})? _scope;

  @override
  Snapshot<T> build() {
    _scope = ref.watch(
      signedInProvider.select(
        (s) => s == null ? null : (epoch: s.epoch, household: s.membership?.householdId),
      ),
    );
    ref.listen(dataRevisionProvider, (_, _) => unawaited(refresh()));
    if (_scope == null) return const Snapshot();
    scheduleMicrotask(_load);
    return const Snapshot.loading();
  }

  /// The cache key: household, path and the query in a fixed order.
  String get cacheKey {
    final sorted = query.keys.toList()..sort();
    final q = sorted.map((k) => '$k=${Uri.encodeQueryComponent(query[k]!)}').join('&');
    return '${_scope?.household ?? '-'}|$path${q.isEmpty ? '' : '?$q'}';
  }

  Future<void> _load() async {
    final scope = _scope;
    if (scope == null) return;
    if (cacheable && scope.household != null) {
      try {
        final cache = await ref.read(cacheHolderProvider).obtain();
        final entry = await cache.read(cacheKey);
        if (entry != null && ref.mounted && _scope == scope) {
          state = Snapshot(
            data: decode(decodeObject(entry.body)),
            fetchedAt: entry.fetchedAt,
            loading: true,
          );
        }
      } on Object {
        // An unreadable cache entry is a cache miss; the API is asked next either way.
      }
    }
    await refresh();
  }

  /// Asks the API again. Keeps whatever is shown while it does, and keeps it if it fails.
  Future<void> refresh() async {
    final scope = _scope;
    final api = ref.read(apiClientProvider);
    if (scope == null || api == null || !ref.mounted) return;
    state = state.refreshing();
    try {
      final body = await api.getText(path, query: query);
      final T value;
      try {
        value = decode(decodeObject(body));
      } on UnexpectedJson {
        throw const AppError.unexpected();
      }
      final fetchedAt = ref.read(clockProvider)().toUtc();
      if (cacheable && scope.household != null) {
        final cache = await ref.read(cacheHolderProvider).obtain();
        await cache.write(cacheKey, scope.household!, body, fetchedAt);
      }
      if (ref.mounted && _scope == scope) {
        state = Snapshot(data: value, fetchedAt: fetchedAt);
      }
    } on AppError catch (failure) {
      if (ref.mounted && _scope == scope) {
        state = Snapshot(data: state.data, fetchedAt: state.fetchedAt, error: failure);
      }
    }
  }
}
