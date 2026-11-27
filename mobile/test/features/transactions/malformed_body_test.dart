import 'dart:convert';
import 'dart:typed_data';

import 'package:budget_owl/core/cache/cache_holder.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/features/transactions/data/transactions_repository.dart';
import 'package:budget_owl/features/transactions/domain/transaction.dart';
import 'package:budget_owl/l10n/gen/app_localizations_en.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fixture_server.dart';
import '../../support/harness.dart';

/// A ledger or transfer body this client cannot read is the screen's generic error state — never
/// an uncaught error, and never a cached copy.
///
/// `Money.fromWire` and `CalendarDate.parse` throw `FormatException`, and the readers map only
/// `UnexpectedJson`: a bare call in a `fromJson` let one malformed figure escape `refresh` as an
/// uncaught async error (mobile-app.md, Phase 1 ledger follow-up). The bodies are the recorded
/// demo answers with **one field** changed, so everything else about them is what the server sends.
void main() {
  final l = AppLocalizationsEn();
  const firstPage = LedgerQuery();

  for (final (field, value, change)
      in <(String, String, void Function(Map<String, Object?>, String))>[
        ('amount', '1e9', (body, v) => _firstRow(body)['amount'] = v),
        ('amount', '12.34567', (body, v) => _firstRow(body)['amount'] = v),
        ('amount', '1234567890123456', (body, v) => _firstRow(body)['amount'] = v),
        ('pageTotal', '1e9', (body, v) => body['pageTotal'] = v),
        ('date', '2026-13-45', (body, v) => _firstRow(body)['date'] = v),
      ]) {
    testWidgets('a ledger "$field": "$value" is the generic error, uncached, not an exception', (
      tester,
    ) async {
      final server = _Tampering(FixtureServer.load('demo', state: 'demo'), {
        '/api/transactions?page=0&size=50': (body) => change(body, value),
      });
      final container = await pumpApp(tester, TestPhone(server: server)..signedIn('owner'));

      expect(server.tampered, contains('/api/transactions?page=0&size=50'));
      final snapshot = container.read(ledgerProvider(firstPage));
      expect(snapshot.data, isNull);
      expect(snapshot.loading, isFalse);
      expect(snapshot.error?.kind, AppErrorKind.unexpected);
      expect(find.text(l.error_generic), findsOneWidget);
      expect(tester.takeException(), isNull);

      // Nothing unreadable is kept: the next launch would decode it again and fail again.
      final key = container.read(ledgerProvider(firstPage).notifier).cacheKey;
      final cache = await tester.runAsync(() => container.read(cacheHolderProvider).obtain());
      expect(await tester.runAsync(() => cache!.read(key)), isNull);
    });
  }

  for (final (field, value) in [('sent', '1e9'), ('received', '1e9'), ('fromDate', 'yesterday')]) {
    testWidgets('a transfer "$field": "$value" is AppError.unexpected, which the screen catches', (
      tester,
    ) async {
      final server = _Tampering(FixtureServer.load('demo', state: 'demo'), {
        '/api/transfers/tgr-0001': (body) => body[field] = value,
      });
      final container = await pumpApp(tester, TestPhone(server: server)..signedIn('owner'));

      final failure = await tester.runAsync<Object?>(
        () => container
            .read(transactionsWriterProvider)
            .transfer('tgr-0001')
            .then<Object?>((_) => null, onError: (Object error) => error),
      );
      expect(server.tampered, contains('/api/transfers/tgr-0001'));
      expect(failure, isA<AppError>().having((e) => e.kind, 'kind', AppErrorKind.unexpected));
    });
  }

  testWidgets('a transfer answered 200 with a page that is not JSON is AppError.unexpected', (
    tester,
  ) async {
    // A reverse proxy's sign-in or error page, served with a 200. Neither getJson nor the fixture
    // maps it: the repository must.
    final server = _Tampering(
      FixtureServer.load('demo', state: 'demo'),
      const {},
      raw: {'/api/transfers/tgr-0001': '<html><body>Sign in</body></html>'},
    );
    final container = await pumpApp(tester, TestPhone(server: server)..signedIn('owner'));

    final failure = await tester.runAsync<Object?>(
      () => container
          .read(transactionsWriterProvider)
          .transfer('tgr-0001')
          .then<Object?>((_) => null, onError: (Object error) => error),
    );
    expect(server.tampered, contains('/api/transfers/tgr-0001'));
    expect(failure, isA<AppError>().having((e) => e.kind, 'kind', AppErrorKind.unexpected));
  });

  testWidgets('the recorded bodies, untampered, still read', (tester) async {
    final server = _Tampering(FixtureServer.load('demo', state: 'demo'), const {});
    final container = await pumpApp(tester, TestPhone(server: server)..signedIn('owner'));

    expect(container.read(ledgerProvider(firstPage)).data?.content, isNotEmpty);
    final transfer = await tester.runAsync(
      () => container.read(transactionsWriterProvider).transfer('tgr-0001'),
    );
    expect(transfer?.sentWire, '500.0000');
  });
}

Map<String, Object?> _firstRow(Map<String, Object?> body) =>
    (body['content']! as List<Object?>).first! as Map<String, Object?>;

/// The fixture server, with the answer to each listed `path?query` changed by a test.
final class _Tampering implements HttpClientAdapter {
  _Tampering(this._fixture, this._changes, {this.raw = const {}});

  final FixtureServer _fixture;
  final Map<String, void Function(Map<String, Object?> body)> _changes;

  /// Bodies replaced outright, for an answer that is not JSON at all.
  final Map<String, String> raw;

  /// Which listed requests were answered, so a test cannot pass by never asking.
  final List<String> tampered = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final answer = await _fixture.fetch(options, requestStream, cancelFuture);
    final uri = options.uri;
    final line = '${uri.path}${uri.hasQuery ? '?${uri.query}' : ''}';
    if (options.method != 'GET') return answer;
    final replacement = raw[line];
    if (replacement != null) {
      tampered.add(line);
      return ResponseBody.fromString(replacement, answer.statusCode, headers: answer.headers);
    }
    final change = _changes[line];
    if (change == null) return answer;
    final bytes = <int>[];
    await answer.stream.forEach(bytes.addAll);
    final body = jsonDecode(utf8.decode(bytes)) as Map<String, Object?>;
    change(body);
    tampered.add(line);
    return ResponseBody.fromString(jsonEncode(body), answer.statusCode, headers: answer.headers);
  }

  @override
  void close({bool force = false}) => _fixture.close(force: force);
}
