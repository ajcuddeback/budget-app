import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/features/envelopes/domain/envelope.dart';
import 'package:budget_owl/features/envelopes/domain/requests.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../support/fixture_server.dart';

/// The bodies, the months and the meter the envelopes screen works with — the web's
/// (`envelope-patch.ts`, `envelopes.page.ts`).
void main() {
  final page = EnvelopePage.fromJson(
    fixtureBody(
          'envelopes',
          state: 'envelopes',
          path: '/api/envelopes?period=2026-08&includeArchived=true&page=0&size=200',
        )!
        as JsonMap,
  );
  Envelope named(String name) => page.envelopes.firstWhere((e) => e.name == name);
  final groceries = named('Groceries');
  final entries = [
    for (final e
        in (fixtureBody(
                  'envelopes',
                  state: 'envelopes',
                  path: '/api/envelopes/env-2026-08-01-groceries/entries?page=0&size=50',
                )!
                as JsonMap)['content']!
            as List<Object?>)
      EnvelopeEntry.fromJson(e! as JsonMap),
  ];
  final bigShop = entries.firstWhere((e) => e.note == 'big shop');

  group('an envelope', () {
    test('a new one omits a category it does not have', () {
      expect(
        createEnvelopeBody(
          const EnvelopeForm(name: ' Coffee ', period: '2026-08', amount: '40.00'),
        ),
        {'name': 'Coffee', 'period': '2026-08', 'amount': '40.00'},
      );
      expect(
        createEnvelopeBody(
          const EnvelopeForm(name: 'Fuel', period: '2026-08', amount: '1', categoryId: 'cat-fuel'),
        )['categoryId'],
        'cat-fuel',
      );
    });

    test('an edit is the version and only what changed — never a period', () {
      final untouched = envelopePatch(
        groceries,
        EnvelopeForm(
          name: 'Groceries',
          period: '2026-09',
          amount: envelopeAmountText(groceries),
          categoryId: groceries.categoryId!,
        ),
      );
      expect(untouched, {'version': groceries.version});
      expect(patchIsEmpty(untouched), isTrue);
      expect(
        envelopePatch(
          groceries,
          EnvelopeForm(
            name: 'Groceries',
            period: '2026-08',
            amount: '650.00',
            categoryId: groceries.categoryId!,
          ),
        ),
        {'version': groceries.version, 'amount': '650.00'},
      );
    });

    test('clearing the category is an explicit null; keeping it leaves the key out', () {
      final cleared = envelopePatch(
        groceries,
        const EnvelopeForm(name: 'Groceries', period: '2026-08', amount: '600'),
      );
      expect(cleared.containsKey('categoryId'), isTrue);
      expect(cleared['categoryId'], isNull);
      expect(cleared.containsKey('amount'), isFalse, reason: '600 is 600.0000');
    });
  });

  group('an entry', () {
    test('quick add sends the amount and the date, and omits an empty payee and note', () {
      expect(createEntryBody(EntryForm(amount: '12.50 ', date: CalendarDate.parse('2026-08-20'))), {
        'amount': '12.50',
        'date': '2026-08-20',
      });
      expect(
        createEntryBody(
          EntryForm(
            amount: '3',
            date: CalendarDate.parse('2026-08-20'),
            payeeName: ' Corner Café ',
            note: ' beans ',
          ),
        ),
        {'amount': '3', 'date': '2026-08-20', 'payeeName': 'Corner Café', 'note': 'beans'},
      );
    });

    test('a correction sends only what changed; clearing the note is an explicit null', () {
      final form = EntryForm(
        amount: entryAmountText(bigShop),
        date: bigShop.date,
        payeeName: bigShop.payeeName!,
        note: 'big shop',
      );
      expect(entryPatch(bigShop, form), {'version': bigShop.version});
      final cleared = entryPatch(
        bigShop,
        EntryForm(amount: '142.1', date: bigShop.date, payeeName: 'Greenfield Market'),
      );
      expect(cleared, {'version': bigShop.version, 'note': null});
    });

    test('every row says where its figure came from', () {
      expect(entries.map((e) => e.sourceKey).toSet(), {
        'envelopes.source.MANUAL',
        'envelopes.source.SYNCED',
      });
    });
  });

  group("the figures are the server's", () {
    test('remaining is read, never computed, and an overspend stays negative', () {
      final eating = named('Eating Out');
      expect(eating.overspent, isTrue);
      expect(eating.remaining.amount.toString(), '-11.2');
      expect(eating.spentPercent, 100, reason: 'the bar is clamped; the figure is not');
    });

    test('the meter is a whole percent from decimals: 282.70 of 600 is 47', () {
      expect(groceries.spentPercent, 47);
      expect(named('Fuel').spentPercent, 0);
    });

    test('a malformed amount is unreadable, not a zero', () {
      expect(
        () => EnvelopeEntry.fromJson({
          'id': 'x',
          'date': '2026-08-01',
          'amount': '1.5e3',
          'currency': 'USD',
          'source': 'MANUAL',
          'version': 0,
        }),
        throwsA(isA<UnexpectedJson>()),
      );
    });
  });

  group('months', () {
    test('step across a year in both directions', () {
      expect(shiftPeriod('2026-01', -1), '2025-12');
      expect(shiftPeriod('2025-12', 1), '2026-01');
      expect(shiftPeriod('2026-08', 0), '2026-08');
    });

    test('open on this month when it has envelopes, else on the latest that has', () {
      expect(openingPeriod(['2026-08', '2026-07'], '2026-08'), '2026-08');
      expect(openingPeriod(['2026-07', '2026-05'], '2026-08'), '2026-07');
      expect(openingPeriod([], '2026-08'), '2026-08');
    });

    test("are named in the reader's language, never 2026-08", () {
      expect(periodName('2026-08', 'en_US'), 'August 2026');
      expect(currentPeriod(DateTime.utc(2026, 8, 20, 12)), '2026-08');
    });

    test('an amount is unsigned and fits NUMERIC(19,4)', () {
      for (final ok in ['0', '12.5', '600.00', '999999999999999.9999']) {
        expect(isEnvelopeAmount(ok), isTrue, reason: ok);
      }
      for (final bad in ['', '-5', '+5', '1e3', '1.23456', '1,000']) {
        expect(isEnvelopeAmount(bad), isFalse, reason: bad);
      }
    });
  });
}
