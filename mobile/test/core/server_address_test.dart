import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:flutter_test/flutter_test.dart';

/// HTTPS only, with a debug-only exception for the three local development hosts
/// (mobile-app.md, controls table: `ServerAddress.parse`).
void main() {
  AddressParse release(String typed) => ServerAddress.parse(typed, allowLocalCleartext: false);
  AddressParse debug(String typed) => ServerAddress.parse(typed, allowLocalCleartext: true);
  String accepted(AddressParse p) => (p as AddressAccepted).address.stored;
  AddressProblem refused(AddressParse p) => (p as AddressRefused).problem;

  test('assumes https when no scheme is typed', () {
    expect(accepted(release('owl.example.test')), 'https://owl.example.test');
  });

  test('drops a trailing slash and lowercases the host', () {
    expect(accepted(release('https://Owl.Example.TEST/')), 'https://owl.example.test');
  });

  test('keeps a non-standard port', () {
    final parsed = release('owl.example.test:8443') as AddressAccepted;
    expect(parsed.address.stored, 'https://owl.example.test:8443');
    expect(parsed.address.display, 'owl.example.test:8443');
  });

  test('refuses plain http in a release build, even on a home network', () {
    expect(refused(release('http://192.168.1.20:8080')), AddressProblem.cleartext);
    expect(refused(release('http://localhost:8080')), AddressProblem.cleartext);
  });

  test('allows plain http in a debug build only to the three local development hosts', () {
    for (final host in ['localhost', '127.0.0.1', '10.0.2.2']) {
      expect(accepted(debug('http://$host:8080')), 'http://$host:8080');
    }
    expect(refused(debug('http://192.168.1.20:8080')), AddressProblem.cleartext);
    expect(refused(debug('http://owl.example.test')), AddressProblem.cleartext);
  });

  test('refuses a path, since the API lives at /api under the origin', () {
    expect(refused(release('https://owl.example.test/api')), AddressProblem.path);
    expect(refused(release('owl.example.test/budget/')), AddressProblem.path);
  });

  test('refuses a query or a fragment', () {
    expect(refused(release('https://owl.example.test/?x=1')), AddressProblem.query);
    expect(refused(release('https://owl.example.test#top')), AddressProblem.query);
  });

  test('refuses credentials in the address', () {
    expect(refused(release('https://ada:secret@owl.example.test')), AddressProblem.credentials);
  });

  test('refuses what is not an address at all', () {
    expect(refused(release('')), AddressProblem.empty);
    expect(refused(release('owl example')), AddressProblem.invalid);
    expect(refused(release('ftp://owl.example.test')), AddressProblem.invalid);
    expect(refused(release('https://')), AddressProblem.invalid);
  });

  test('re-checks a stored address with the same rules', () {
    expect(
      ServerAddress.tryRestore('https://owl.example.test', allowLocalCleartext: false),
      isNotNull,
    );
    expect(ServerAddress.tryRestore('http://localhost:8080', allowLocalCleartext: false), isNull);
    expect(ServerAddress.tryRestore(null, allowLocalCleartext: false), isNull);
  });

  test('is a value', () {
    final a = (release('owl.example.test') as AddressAccepted).address;
    final b = (release('https://owl.example.test/') as AddressAccepted).address;
    expect(a, b);
    expect(a.hashCode, b.hashCode);
    expect(a.isCleartext, isFalse);
    expect(a.toString(), contains('owl.example.test'));
  });

  group('CalendarDate', () {
    test("reads and writes the API's ISO dates", () {
      expect(CalendarDate.parse('2026-08-20').iso, '2026-08-20');
    });

    test('refuses a date that does not exist', () {
      expect(() => CalendarDate.parse('2026-02-30'), throwsFormatException);
      expect(() => CalendarDate.parse('20-08-2026'), throwsFormatException);
    });

    test('formats the stored day, whatever the zone', () {
      expect(CalendarDate.parse('2026-08-20').medium('en_US'), 'Aug 20, 2026');
      expect(CalendarDate.parse('2026-08-20').heading('en_US'), 'Thursday, August 20');
    });

    test('takes today from the wall clock', () {
      expect(CalendarDate.of(DateTime(2026, 8, 20, 23, 59)).iso, '2026-08-20');
    });

    test('orders and compares by day', () {
      final a = CalendarDate.parse('2026-08-01');
      final b = CalendarDate.parse('2026-08-20');
      expect(a.compareTo(b), lessThan(0));
      expect(a, CalendarDate(2026, 8, 1));
      expect(a.hashCode, CalendarDate(2026, 8, 1).hashCode);
      expect(a.toString(), '2026-08-01');
      expect(a.forPicker, DateTime(2026, 8, 1, 12));
    });
  });
}
