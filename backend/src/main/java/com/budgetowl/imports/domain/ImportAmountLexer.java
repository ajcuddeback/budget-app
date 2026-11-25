package com.budgetowl.imports.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns an amount cell from a file into a {@link BigDecimal}, through one strict lexer and never
 * through {@code double} (ADR-0006, docs/features/file-import.md rule 7).
 *
 * <p><b>Raw file text never reaches {@code new BigDecimal(...)}, {@code AmountText.parse}, {@code
 * Double.parseDouble}, {@code NumberFormat} or {@code DecimalFormat}.</b> Each accepts something it
 * must not, all verified on this repository's JDK: {@code new BigDecimal("1E+10000000")} is eleven
 * characters that cost seconds of CPU once {@code setScale(4)} builds the ten-million-digit result;
 * {@code new BigDecimal("١٢٣")} is 123; {@code Double.parseDouble("0x1p3")} is 8; and {@code
 * NumberFormat} both returns a {@code Double} and stops at the first character it does not like, so
 * {@code "12abc"} is 12. {@code ArchitectureTest} bans the last four from this package outright.
 *
 * <p>So the text is taken apart by hand: at most 64 characters, an optional currency that must be
 * the account's own, at most one sign marker, the configured grouping separator in valid positions
 * only, ASCII digits only, at most 15 integer digits and 4 decimal places. Only a canonical string
 * of the form {@code -?digits(.digits)?} is ever handed to {@code BigDecimal}, by which point
 * nothing in it can be expensive or surprising.
 *
 * <p>Two places this is slightly more permissive than the regex in the feature doc, both exact:
 * leading zeros in the integer part and trailing zeros in the fraction are not counted against the
 * 15- and 4-digit limits ({@code 0012.3400} is {@code 12.34}). Neither changes a value, and the
 * 64-character cap still bounds the work. And one place it is stricter: a grouping separator must
 * sit between groups of three digits, so {@code 12,50} with {@code ,} as the grouping separator is
 * unreadable rather than twelve hundred and fifty.
 */
public final class ImportAmountLexer {

    /** Text longer than this is refused before it is looked at (rule 3, "Text handed to …"). */
    public static final int MAXIMUM_LENGTH = 64;

    /** {@code NUMERIC(19,4)}: fifteen integer digits and four fraction digits. */
    public static final int MAXIMUM_INTEGER_DIGITS = 15;

    public static final int MAXIMUM_FRACTION_DIGITS = 4;

    /**
     * Symbols accepted in place of an ISO code, and the currencies each may stand for. A symbol is
     * accepted only when the account's currency is one of them; {@code $} on a GBP account is a
     * currency mismatch, not a dollar sign to ignore.
     */
    private static final Map<String, Set<String>> SYMBOLS =
            Map.ofEntries(
                    Map.entry("US$", Set.of("USD")),
                    Map.entry("C$", Set.of("CAD")),
                    Map.entry("CA$", Set.of("CAD")),
                    Map.entry("A$", Set.of("AUD")),
                    Map.entry("AU$", Set.of("AUD")),
                    Map.entry("NZ$", Set.of("NZD")),
                    Map.entry("S$", Set.of("SGD")),
                    Map.entry("HK$", Set.of("HKD")),
                    Map.entry("MX$", Set.of("MXN")),
                    Map.entry("R$", Set.of("BRL")),
                    Map.entry("$", Set.of("USD", "CAD", "AUD", "NZD", "SGD", "HKD", "MXN")),
                    Map.entry("£", Set.of("GBP")),
                    Map.entry("€", Set.of("EUR")),
                    Map.entry("¥", Set.of("JPY", "CNY")),
                    Map.entry("₹", Set.of("INR")),
                    Map.entry("₩", Set.of("KRW")),
                    Map.entry("₽", Set.of("RUB")),
                    Map.entry("₺", Set.of("TRY")),
                    Map.entry("₪", Set.of("ILS")),
                    Map.entry("₫", Set.of("VND")),
                    Map.entry("฿", Set.of("THB")),
                    Map.entry("₱", Set.of("PHP")),
                    Map.entry("₦", Set.of("NGN")),
                    Map.entry("₴", Set.of("UAH")),
                    Map.entry("zł", Set.of("PLN")),
                    Map.entry("Kč", Set.of("CZK")),
                    Map.entry("kr", Set.of("SEK", "NOK", "DKK", "ISK")));

    /**
     * The ISO codes the JDK knows. Three letters are a currency only if they are one of these, so
     * {@code NaN} and {@code 12abc} are unreadable rather than a mismatch with currency "NAN".
     */
    private static final Set<String> ISO_CODES =
            Currency.getAvailableCurrencies().stream()
                    .map(Currency::getCurrencyCode)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Longest first, so {@code US$} is tried before {@code $}. */
    private static final List<String> SYMBOLS_LONGEST_FIRST =
            SYMBOLS.keySet().stream()
                    .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                    .toList();

    private ImportAmountLexer() {}

    /**
     * How the digits are written.
     *
     * @param decimalSeparators the characters that may separate the fraction. One for CSV and QIF;
     *     OFX's {@code TRNAMT} may use either {@code .} or {@code ,} and has no grouping
     * @param grouping the thousands separator, or {@code null} for none
     */
    public record Style(String decimalSeparators, Character grouping) {

        /** OFX: {@code .} or {@code ,} as the decimal separator, and no grouping. */
        public static final Style OFX = new Style(".,", null);

        /** QIF as Quicken writes it: {@code 1,234.56}. */
        public static final Style QIF = new Style(".", ',');

        public Style {
            Objects.requireNonNull(decimalSeparators, "decimalSeparators");
            if (decimalSeparators.isEmpty()) {
                throw new IllegalArgumentException("at least one decimal separator");
            }
            if (grouping != null && decimalSeparators.indexOf(grouping) >= 0) {
                throw new IllegalArgumentException("grouping must differ from the decimal");
            }
        }

        public static Style of(char decimal, Character grouping) {
            return new Style(String.valueOf(decimal), grouping);
        }
    }

    /**
     * A sign supplied by something other than the cell: a CSV debit, credit or direction column.
     */
    public enum Direction {
        NONE,
        IN,
        OUT
    }

    /** The outcome: an exact amount, or the reason there is none. */
    public sealed interface Lexed permits Lexed.Amount, Lexed.Refused {

        /** A value that fits {@code NUMERIC(19,4)}, possibly zero. */
        record Amount(BigDecimal value) implements Lexed {

            @Override
            public String toString() {
                return "Amount[redacted]";
            }
        }

        record Refused(RowProblem problem) implements Lexed {}
    }

    /**
     * @param text the cell, as decoded; never trimmed by the caller
     * @param style how the digits are written
     * @param currency the account's currency. A currency in the text must be this one
     * @param direction a sign from elsewhere, which counts as the one permitted sign marker
     */
    public static Lexed lex(String text, Style style, Currency currency, Direction direction) {
        if (text == null || text.length() > MAXIMUM_LENGTH) {
            return refused(text == null ? RowProblem.MISSING_FIELD : RowProblem.AMOUNT_UNREADABLE);
        }
        String rest = trimSpaces(text);
        if (rest.isEmpty()) {
            return refused(RowProblem.MISSING_FIELD);
        }

        int markers = 0;
        boolean negative = false;

        if (enclosedInParentheses(rest)) {
            markers++;
            negative = true;
            rest = trimSpaces(rest.substring(1, rest.length() - 1));
        }
        int leading = leadingSign(rest);
        if (leading != 0) {
            markers++;
            negative = negative || leading < 0;
            rest = trimSpaces(rest.substring(1));
        }

        CurrencyCut prefix = leadingCurrency(rest);
        if (prefix != null) {
            if (!prefix.matches(currency)) {
                return refused(RowProblem.CURRENCY_MISMATCH);
            }
            rest = trimSpaces(rest.substring(prefix.length()));
            int afterCurrency = leadingSign(rest);
            if (afterCurrency != 0) {
                markers++;
                negative = negative || afterCurrency < 0;
                rest = trimSpaces(rest.substring(1));
            }
        }

        CurrencyCut suffix = trailingCurrency(rest);
        if (suffix != null) {
            if (!suffix.matches(currency)) {
                return refused(RowProblem.CURRENCY_MISMATCH);
            }
            rest = trimSpaces(rest.substring(0, rest.length() - suffix.length()));
        }

        if (!rest.isEmpty() && isMinus(rest.charAt(rest.length() - 1))) {
            markers++;
            negative = true;
            rest = trimSpaces(rest.substring(0, rest.length() - 1));
        }
        if (enclosedInParentheses(rest)) {
            markers++;
            negative = true;
            rest = trimSpaces(rest.substring(1, rest.length() - 1));
        }

        if (direction != Direction.NONE) {
            markers++;
            negative = direction == Direction.OUT;
        }
        if (markers > 1 || rest.isEmpty()) {
            return refused(RowProblem.AMOUNT_UNREADABLE);
        }
        return digits(rest, style, negative);
    }

    // ------------------------------------------------------------------------------- internals

    private static Lexed digits(String body, Style style, boolean negative) {
        int decimalAt = -1;
        for (int index = 0; index < body.length(); index++) {
            if (style.decimalSeparators().indexOf(body.charAt(index)) >= 0) {
                if (decimalAt >= 0) {
                    // Two decimal separators: 1.2.3, or 1.234,56 under the OFX style.
                    return refused(RowProblem.AMOUNT_UNREADABLE);
                }
                decimalAt = index;
            }
        }
        String integerPart = decimalAt < 0 ? body : body.substring(0, decimalAt);
        String fraction = decimalAt < 0 ? "" : body.substring(decimalAt + 1);

        String integerDigits = ungrouped(integerPart, style.grouping());
        if (integerDigits == null || (decimalAt >= 0 && !allAsciiDigits(fraction))) {
            return refused(RowProblem.AMOUNT_UNREADABLE);
        }
        if (decimalAt >= 0 && fraction.isEmpty()) {
            return refused(RowProblem.AMOUNT_UNREADABLE);
        }

        String significantInteger = stripLeadingZeros(integerDigits);
        String significantFraction = stripTrailingZeros(fraction);
        if (significantInteger.length() > MAXIMUM_INTEGER_DIGITS) {
            return refused(RowProblem.AMOUNT_TOO_LARGE);
        }
        if (significantFraction.length() > MAXIMUM_FRACTION_DIGITS) {
            // Refused, never rounded: rounding somebody's money without telling them is the class
            // of defect ADR-0006 exists to prevent (transactions.md rule 3).
            return refused(RowProblem.AMOUNT_TOO_PRECISE);
        }

        // The only BigDecimal construction in the import path, from a string this method built:
        // an optional minus, at most fifteen ASCII digits, and at most four more after a dot.
        String canonical =
                (negative ? "-" : "")
                        + significantInteger
                        + (significantFraction.isEmpty() ? "" : "." + significantFraction);
        return new Lexed.Amount(new BigDecimal(canonical));
    }

    /**
     * The integer part with its grouping separators removed, or {@code null} if it is not a
     * well-formed run of ASCII digits — including a separator in the wrong place.
     */
    private static String ungrouped(String integerPart, Character grouping) {
        if (integerPart.isEmpty()) {
            return null;
        }
        if (grouping == null || integerPart.indexOf(grouping) < 0) {
            return allAsciiDigits(integerPart) ? integerPart : null;
        }
        String[] groups = integerPart.split(java.util.regex.Pattern.quote(grouping.toString()), -1);
        StringBuilder digits = new StringBuilder(integerPart.length());
        for (int index = 0; index < groups.length; index++) {
            String group = groups[index];
            boolean first = index == 0;
            boolean sized =
                    first ? group.length() >= 1 && group.length() <= 3 : group.length() == 3;
            if (!sized || !allAsciiDigits(group)) {
                return null;
            }
            digits.append(group);
        }
        return digits.toString();
    }

    private static boolean allAsciiDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            // Not Character.isDigit: that is true for Arabic-Indic and full-width digits too.
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static String stripLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }

    private static String stripTrailingZeros(String digits) {
        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
        }
        return digits.substring(0, end);
    }

    private static boolean enclosedInParentheses(String text) {
        return text.length() >= 2 && text.charAt(0) == '(' && text.charAt(text.length() - 1) == ')';
    }

    /** {@code -1}, {@code +1}, or {@code 0} for no leading sign. */
    private static int leadingSign(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        char first = text.charAt(0);
        if (isMinus(first)) {
            return -1;
        }
        return first == '+' ? 1 : 0;
    }

    /** ASCII hyphen-minus and U+2212 MINUS SIGN, which typeset statements use. */
    private static boolean isMinus(char character) {
        return character == '-' || character == '−';
    }

    /** A leading ISO code ({@code GBP 12.00}) or symbol ({@code £12.00}). */
    private static CurrencyCut leadingCurrency(String text) {
        if (text.length() >= 3 && isAsciiLetters(text, 0, 3)) {
            boolean bounded = text.length() == 3 || !Character.isLetter(text.charAt(3));
            String code = text.substring(0, 3).toUpperCase(java.util.Locale.ROOT);
            if (bounded && ISO_CODES.contains(code)) {
                return new CurrencyCut(3, Set.of(code));
            }
        }
        for (String symbol : SYMBOLS_LONGEST_FIRST) {
            if (text.startsWith(symbol)) {
                return new CurrencyCut(symbol.length(), SYMBOLS.get(symbol));
            }
        }
        return null;
    }

    /** A trailing ISO code ({@code 12.00 EUR}) or symbol ({@code 12,00 €}). */
    private static CurrencyCut trailingCurrency(String text) {
        int length = text.length();
        if (length >= 3 && isAsciiLetters(text, length - 3, length)) {
            boolean bounded = length == 3 || !Character.isLetter(text.charAt(length - 4));
            String code = text.substring(length - 3).toUpperCase(java.util.Locale.ROOT);
            if (bounded && ISO_CODES.contains(code)) {
                return new CurrencyCut(3, Set.of(code));
            }
        }
        for (String symbol : SYMBOLS_LONGEST_FIRST) {
            if (text.endsWith(symbol)) {
                return new CurrencyCut(symbol.length(), SYMBOLS.get(symbol));
            }
        }
        return null;
    }

    private static boolean isAsciiLetters(String text, int from, int to) {
        for (int index = from; index < to; index++) {
            char character = text.charAt(index);
            boolean letter =
                    (character >= 'A' && character <= 'Z')
                            || (character >= 'a' && character <= 'z');
            if (!letter) {
                return false;
            }
        }
        return true;
    }

    /** ASCII whitespace, NO-BREAK SPACE and NARROW NO-BREAK SPACE, from both ends. */
    static String trimSpaces(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(char character) {
        return character == ' '
                || character == '\t'
                || character == '\n'
                || character == '\r'
                || character == '\f'
                || character == '\u000B'
                || character == ' '
                || character == ' ';
    }

    private static Lexed refused(RowProblem problem) {
        return new Lexed.Refused(problem);
    }

    /** A currency marker found at one end of the text, and the codes it may mean. */
    private record CurrencyCut(int length, Set<String> codes) {

        boolean matches(Currency accountCurrency) {
            return codes.contains(accountCurrency.getCurrencyCode());
        }
    }
}
