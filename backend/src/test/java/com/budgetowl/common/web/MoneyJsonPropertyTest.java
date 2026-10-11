package com.budgetowl.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wire format loses nothing, for amounts nobody enumerated.
 *
 * <p>{@code AccountPropertyTest} proves the same of the entity and the column. This proves it of
 * the serializer, which is the other end of the round trip the feature doc asks for: "string in,
 * string out, no float anywhere".
 */
class MoneyJsonPropertyTest {

    private final ObjectMapper json = JsonMapper.builder().addModule(new MoneyJsonModule()).build();

    record Holder(BigDecimal amount) {}

    /** Everything {@code NUMERIC(19,4)} can hold: 15 integer digits, 4 decimal places, signed. */
    @Provide
    Arbitrary<String> amounts() {
        Arbitrary<String> integerPart =
                Arbitraries.strings().numeric().ofMinLength(1).ofMaxLength(15);
        Arbitrary<String> fraction = Arbitraries.strings().numeric().ofMinLength(0).ofMaxLength(4);
        Arbitrary<String> sign = Arbitraries.of("", "-");
        return Combinators.combine(sign, integerPart, fraction)
                .as(
                        (minus, whole, decimals) ->
                                minus + whole + (decimals.isEmpty() ? "" : "." + decimals));
    }

    @Property
    void readingThenWritingAnAmountChangesNoDigitAndNoScale(@ForAll("amounts") String text) {
        BigDecimal original = new BigDecimal(text);

        Holder read = json.readValue("{\"amount\":\"" + text + "\"}", Holder.class);
        String written = json.writeValueAsString(read);

        assertThat(read.amount()).isEqualByComparingTo(original);
        assertThat(read.amount().scale()).isEqualTo(original.scale());
        assertThat(written).isEqualTo("{\"amount\":\"" + original.toPlainString() + "\"}");
    }

    @Property
    void everyAmountIsQuotedOnTheWayOut(@ForAll("amounts") String text) {
        String written = json.writeValueAsString(new Holder(new BigDecimal(text)));

        assertThat(written).matches("\\{\"amount\":\"-?[0-9.]+\"\\}");
    }
}
