package com.budgetowl.common.web;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Money crosses the wire as a <b>string</b>, in both directions, for every type in this application
 * that can hold an amount (ADR-0006).
 *
 * <p><b>Why this is a module and not a {@code toPlainString()} in a mapper.</b> Most JSON parsers,
 * JavaScript's included, read a JSON number as an IEEE-754 double (docs/memory/gotchas.md), so
 * {@code {"balance": 1234.5678}} is a value the client may receive as something else. A mapper that
 * remembers to stringify is a mapper somebody can forget to write; registering the rule against the
 * types themselves means a future response DTO cannot emit a bare number by omission.
 *
 * <p><b>The deserializer refuses a JSON number outright</b> rather than converting one. {@code
 * BigDecimal} could be built from the token losslessly on this side, but by then the damage is done
 * at whichever client composed the request — the digits it sent are the digits its own double
 * rounded to. Refusing it turns a silently-rounded amount into a {@code 400} somebody can see.
 *
 * <p>Request DTOs in this codebase nevertheless declare their amounts as {@code String} with a
 * shape {@code @Pattern}, which is a different control for a different reason: a malformed amount
 * then fails Bean Validation and is answered as {@code validation-failed} <em>naming the
 * field</em>, where a deserializer failure can only be a {@code malformed-request} naming nothing.
 * This module is the backstop beneath that, for every amount that does not pass through an
 * annotated DTO.
 */
public final class MoneyJsonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public MoneyJsonModule() {
        super("budget-owl-money");
        addSerializer(BigDecimal.class, new PlainStringSerializer());
        addDeserializer(BigDecimal.class, new StringOnlyDeserializer());
        addSerializer(Money.class, new MoneySerializer());
    }

    /**
     * {@code toPlainString}, never {@code toString}: the latter switches to scientific notation at
     * some scales ({@code 1E+3}), which is a form a client would have to parse specially and a
     * human reading a log would have to think about.
     */
    private static final class PlainStringSerializer extends ValueSerializer<BigDecimal> {

        @Override
        public void serialize(
                BigDecimal value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(value.toPlainString());
        }
    }

    private static final class StringOnlyDeserializer extends ValueDeserializer<BigDecimal> {

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(
                        BigDecimal.class,
                        "an amount is a JSON string, never a JSON number (ADR-0006)");
            }
            // Neither branch below puts the rejected text in the failure message. It is an amount,
            // and an amount is not something this codebase writes to a log
            // (docs/architecture/security-model.md).
            try {
                return new BigDecimal(parser.getString().strip());
            } catch (NumberFormatException malformed) {
                return context.reportInputMismatch(
                        BigDecimal.class, "not a decimal amount (ADR-0006)");
            }
        }
    }

    /**
     * {@code {"amount": "1234.56", "currency": "GBP"}} — an amount always carries its currency
     * (docs/guides/api-style.md), and never a formatted or a converted one.
     *
     * <p>An account reports its own {@code currency} as a field of the account, so its {@code
     * balance} and {@code openingBalance} are plain strings beside it rather than two objects
     * repeating one code. This shape is for an amount that travels on its own, which is most of
     * them from slice 5 onwards.
     */
    private static final class MoneySerializer extends ValueSerializer<Money> {

        @Override
        public void serialize(Money value, JsonGenerator generator, SerializationContext context) {
            generator.writeStartObject();
            generator.writeStringProperty("amount", value.amount().toPlainString());
            generator.writeStringProperty("currency", value.currency().getCurrencyCode());
            generator.writeEndObject();
        }
    }
}
