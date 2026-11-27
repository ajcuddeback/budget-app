import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';

/// Money is never a double (ADR-0006): formatting is built from integers, and these are the cases
/// testing-style.md makes mandatory wherever amounts are formatted — scale, rounding, negative and
/// very large values.
void main() {
  const minus = '−⁠';
  String usd(String wire) => formatMoney(Money.fromWire(wire, 'USD'), 'en_US');

  group('formatMoney', () {
    test("formats a dollar amount at the currency's two decimals", () {
      expect(usd('1234.5'), r'$1,234.50');
      expect(usd('1234.5600'), r'$1,234.56');
    });

    test('carries the sign as a typographic minus joined to the figure', () {
      expect(usd('-1234.5'), '$minus\$1,234.50');
    });

    test('keeps the sign of an amount under one unit', () {
      expect(usd('-0.5'), '$minus\$0.50');
    });

    test('rounds half away from zero, in both directions', () {
      expect(usd('2.345'), r'$2.35');
      expect(usd('2.3449'), r'$2.34');
      expect(usd('-2.345'), '$minus\$2.35');
    });

    test('drops the sign when the rounded figure is zero', () {
      expect(usd('-0.004'), r'$0.00');
    });

    test('keeps every digit of the largest amount the API stores', () {
      expect(usd('999999999999999.9999'), r'$1,000,000,000,000,000.00');
      expect(usd('123456789012345.67'), r'$123,456,789,012,345.67');
    });

    test("uses the locale's separators and symbol placement", () {
      expect(formatMoney(Money.fromWire('1234.5', 'EUR'), 'de_DE'), '1.234,50 €');
    });

    test("uses the currency's own decimals, not the locale's", () {
      expect(formatMoney(Money.fromWire('1234.5', 'JPY'), 'en_US'), '¥1,235');
      expect(formatMoney(Money.fromWire('1.2345', 'KWD'), 'en_US'), 'din1.235');
    });

    test('formats the amount in its own currency, whatever the locale', () {
      expect(formatMoney(Money.fromWire('10', 'GBP'), 'en_US'), '£10.00');
    });
  });

  group('parseWireAmount', () {
    test('reads what the API sends', () {
      expect(parseWireAmount('-92.1500'), Decimal.parse('-92.15'));
    });

    test('keeps every digit of the largest amount NUMERIC(19,4) holds — a double would not', () {
      // 19 significant digits: a double has about 16, so this would come back as 1e15.
      expect(parseWireAmount('999999999999999.9999').toString(), '999999999999999.9999');
      expect(parseWireAmount('-0.0001').toString(), '-0.0001');
      expect(usd('123456789012345.6789'), r'$123,456,789,012,345.68');
    });

    for (final refused in [
      '1e3',
      '1E+10000000',
      '+5',
      '1.23456',
      '1234567890123456',
      '١٢',
      '',
      ' 1',
      '1,5',
      'NaN',
    ]) {
      test('refuses "$refused" rather than guessing', () {
        expect(() => parseWireAmount(refused), throwsFormatException);
      });
    }
  });

  group('parseAmountInput', () {
    test('keeps what was typed as the string that is sent', () {
      expect((parseAmountInput('42.5') as ValidAmount).text, '42.5');
      expect((parseAmountInput(' 0042 ') as ValidAmount).text, '42');
    });

    test('refuses a sign in an unsigned field', () {
      expect((parseAmountInput('-4') as InvalidAmount).problem, AmountProblem.signed);
    });

    test('folds a typed sign into the direction where one is allowed', () {
      expect((parseAmountInput('-4.50', allowSign: true) as ValidAmount).text, '4.50');
    });

    test('refuses zero, which moves no money', () {
      expect((parseAmountInput('0.00') as InvalidAmount).problem, AmountProblem.zero);
    });

    test('refuses more than four decimals rather than rounding them away', () {
      expect((parseAmountInput('1.23456') as InvalidAmount).problem, AmountProblem.tooPrecise);
    });

    test('refuses more than fifteen integer digits', () {
      expect(
        (parseAmountInput('1234567890123456') as InvalidAmount).problem,
        AmountProblem.tooLarge,
      );
    });

    for (final malformed in ['1,5', '1e3', '1.2.3', 'abc', '١٢', '.5', '5.']) {
      test('refuses "$malformed"', () {
        expect((parseAmountInput(malformed) as InvalidAmount).problem, AmountProblem.malformed);
      });
    }

    test('says an empty field is empty', () {
      expect((parseAmountInput('  ') as InvalidAmount).problem, AmountProblem.empty);
    });
  });

  test('sameAmount compares figures, not strings', () {
    expect(sameAmount('-42.5', '-42.5000'), isTrue);
    expect(sameAmount('-42.5', '-42.51'), isFalse);
    expect(sameAmount('x', '1'), isFalse);
  });

  test('magnitudeOf drops the sign and keeps cents', () {
    expect(magnitudeOf('-4.5000'), '4.50');
    expect(magnitudeOf('200.0000'), '200.00');
    expect(magnitudeOf('0.1234'), '0.1234');
  });

  test('Money is a value', () {
    expect(Money.fromWire('1.50', 'USD'), Money.fromWire('1.5000', 'USD'));
    expect(Money.fromWire('1.50', 'USD'), isNot(Money.fromWire('1.50', 'EUR')));
    expect(Money.fromWire('-1', 'USD').isNegative, isTrue);
    expect(Money.fromWire('1', 'USD').toString(), contains('USD'));
  });

  test(
    'wireMoney turns a refused wire amount into UnexpectedJson naming the field, not the value',
    () {
      expect(wireMoney('-42.5000', 'USD', 'amount'), Money.fromWire('-42.5000', 'USD'));
      for (final malformed in ['1e9', '12.34567', '1234567890123456', '+1', '']) {
        expect(
          () => wireMoney(malformed, 'USD', 'amount'),
          throwsA(
            isA<UnexpectedJson>()
                .having((e) => e.what, 'what', 'amount')
                .having(
                  (e) => e.toString(),
                  'toString',
                  isNot(contains(malformed.isEmpty ? '#' : malformed)),
                ),
          ),
          reason: malformed,
        );
      }
    },
  );
}
