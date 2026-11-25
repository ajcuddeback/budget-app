package com.budgetowl.imports.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.imports.domain.ImportAmountLexer.Direction;
import com.budgetowl.imports.domain.ImportAmountLexer.Lexed;
import com.budgetowl.imports.domain.ImportAmountLexer.Style;
import java.math.BigDecimal;
import java.util.Currency;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * For every amount {@code NUMERIC(19,4)} can hold, written in every style a bank writes, the lexer
 * reads back exactly that amount (docs/features/file-import.md, testing notes).
 *
 * <p>The writer here builds the text by hand from {@code toPlainString} — never through {@code
 * NumberFormat}, which would put a {@code double} into the very test that guards against one.
 */
class ImportAmountLexerPropertyTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    /** A decimal separator and a grouping separator that differs from it, or none. */
    record Written(char decimal, Character grouping, int signStyle) {}

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals()
                .between(
                        new BigDecimal("-999999999999999.9999"),
                        new BigDecimal("999999999999999.9999"))
                .ofScale(4);
    }

    @Provide
    Arbitrary<Written> styles() {
        Arbitrary<Character> decimals = Arbitraries.of('.', ',');
        Arbitrary<Character> groupings =
                Arbitraries.of(',', '.', ' ', ' ', ' ', '\'').injectNull(0.3);
        Arbitrary<Integer> signs = Arbitraries.integers().between(0, 3);
        return Combinators.combine(decimals, groupings, signs)
                .as(Written::new)
                .filter(style -> style.grouping() == null || style.grouping() != style.decimal());
    }

    @Property(tries = 2000)
    void everyAmountInEveryStyleLexesBackExactly(
            @ForAll("amounts") BigDecimal amount, @ForAll("styles") Written style) {
        String text = write(amount, style);

        Lexed lexed =
                ImportAmountLexer.lex(
                        text, Style.of(style.decimal(), style.grouping()), GBP, Direction.NONE);

        assertThat(lexed).isInstanceOf(Lexed.Amount.class);
        assertThat(((Lexed.Amount) lexed).value()).isEqualByComparingTo(amount);
    }

    /** 1234567.8900 as {@code -1.234.567,89}, {@code (1 234 567.89)}, {@code 1'234'567.89-} … */
    static String write(BigDecimal amount, Written style) {
        String plain = amount.abs().stripTrailingZeros().toPlainString();
        int point = plain.indexOf('.');
        String integer = point < 0 ? plain : plain.substring(0, point);
        String fraction = point < 0 ? "" : plain.substring(point + 1);

        StringBuilder grouped = new StringBuilder();
        for (int index = 0; index < integer.length(); index++) {
            int fromEnd = integer.length() - index;
            grouped.append(integer.charAt(index));
            if (style.grouping() != null && fromEnd > 1 && (fromEnd - 1) % 3 == 0) {
                grouped.append(style.grouping());
            }
        }
        String body = grouped + (fraction.isEmpty() ? "" : style.decimal() + fraction);
        if (amount.signum() >= 0) {
            return body;
        }
        return switch (style.signStyle()) {
            case 0 -> "-" + body;
            case 1 -> body + "-";
            case 2 -> "(" + body + ")";
            default -> "−" + body;
        };
    }
}
