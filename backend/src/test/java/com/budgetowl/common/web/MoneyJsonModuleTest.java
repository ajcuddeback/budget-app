package com.budgetowl.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Money is a string on the wire, in both directions (ADR-0006).
 *
 * <p>Asserted against the serialized text rather than a round-tripped object, because the bug this
 * module exists to prevent is invisible on this side: a {@code BigDecimal} re-read from a JSON
 * number is exact here and was already rounded by whichever client composed it
 * (docs/memory/gotchas.md).
 */
class MoneyJsonModuleTest {

    private final ObjectMapper json = JsonMapper.builder().addModule(new MoneyJsonModule()).build();

    record Holder(BigDecimal amount) {}

    @Test
    void writesAnAmountAsAJsonStringAndNeverAsAJsonNumber() {
        String written = json.writeValueAsString(new Holder(new BigDecimal("-1234.5678")));

        assertThat(written).isEqualTo("{\"amount\":\"-1234.5678\"}");
    }

    @Test
    void readsAnAmountFromAStringWithEveryDigitIntact() {
        Holder read = json.readValue("{\"amount\":\"-1234.5678\"}", Holder.class);

        assertThat(read.amount()).isEqualByComparingTo("-1234.5678");
        assertThat(read.amount().scale()).isEqualTo(4);
    }

    @Test
    void roundTripsAnAmountToTheIdenticalString() {
        Holder read = json.readValue("{\"amount\":\"-1234.5678\"}", Holder.class);

        assertThat(json.writeValueAsString(read)).isEqualTo("{\"amount\":\"-1234.5678\"}");
    }

    @Test
    void writesPlainDigitsRatherThanScientificNotation() {
        // BigDecimal.toString() gives "1E+3" here, which is a number a client would have to parse
        // specially and a human would have to double-check.
        String written = json.writeValueAsString(new Holder(new BigDecimal("1E+3")));

        assertThat(written).isEqualTo("{\"amount\":\"1000\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234.56", "0", "-0.0001", "1e5", "true", "[]", "{}"})
    void refusesAJsonNumberOrAnythingElseThatIsNotAString(String token) {
        assertThatThrownBy(() -> json.readValue("{\"amount\":" + token + "}", Holder.class))
                .isInstanceOf(DatabindException.class);
    }

    @Test
    void readsAJsonNullAsAnAbsentAmountRatherThanAsZero() {
        // Whether a null amount is acceptable is the DTO's business (@NotBlank on the string
        // field), not this module's. What matters is that it never becomes a silent 0.
        Holder read = json.readValue("{\"amount\":null}", Holder.class);

        assertThat(read.amount()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "abc", "1,5", "12 34", "0x1f", "NaN"})
    void refusesAStringThatIsNotADecimalAmount(String text) {
        assertThatThrownBy(() -> json.readValue("{\"amount\":\"" + text + "\"}", Holder.class))
                .isInstanceOf(DatabindException.class);
    }

    @Test
    void neverPutsTheRejectedAmountInTheFailureMessage() {
        // The message reaches a log. An amount does not (docs/architecture/security-model.md).
        assertThatThrownBy(() -> json.readValue("{\"amount\":\"9876.5432x\"}", Holder.class))
                .isInstanceOf(DatabindException.class)
                .hasMessageNotContaining("9876.5432");
    }

    @Test
    void writesAStandaloneAmountWithTheCurrencyItIsDenominatedIn() {
        // docs/guides/api-style.md: an amount always carries its currency, never a converted one.
        String written = json.writeValueAsString(Money.of("1234.56", "GBP"));

        assertThat(written).isEqualTo("{\"amount\":\"1234.5600\",\"currency\":\"GBP\"}");
    }
}
