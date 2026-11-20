package com.budgetowl.common;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;

/**
 * Turns a decimal string from the wire into {@link Money}.
 *
 * <p>Amounts cross the wire as strings and never as JSON numbers (ADR-0006), so every service that
 * takes one has to parse it, and every one of them has the same three things to say: it is missing,
 * it is not a number, or it has more precision than money has. This is that, once.
 *
 * <p><b>The rejected text is never echoed back.</b> It is an amount — somebody's rent or salary —
 * and a validation message is a log line waiting to happen. The response names the field and says
 * what shape was wanted.
 *
 * <p>The edge's {@code @Pattern} produces the better message for a malformed amount and does it
 * before the request reaches a service. This is the backstop for anything that arrives without
 * having passed one, and the only check on the sign.
 */
public final class AmountText {

    private AmountText() {}

    /** What an amount is allowed to be, which is not the same question in two places. */
    public enum Sign {

        /** A transaction: the sign carries the direction, and zero moves nothing. */
        NON_ZERO,

        /** A bill, an envelope, an income source: deciding on nothing is a real decision. */
        NOT_NEGATIVE,

        /** A log entry: zero used none of the envelope, so there is nothing to record. */
        POSITIVE
    }

    /**
     * @param field the request field to name in the problem's {@code params}, so a form can put the
     *     message beside the input it belongs to
     * @throws InvalidRequestException {@code validation-failed}, always naming {@code field}
     */
    public static Money parse(String text, Currency currency, Sign sign, String field) {
        // Checked rather than caught: a missing amount and an unparseable one are the same answer
        // to the caller, but catching NullPointerException to discover it is the kind of control
        // flow that hides a real NPE from somewhere else in the method.
        if (text == null) {
            throw refuse("an amount is required", field);
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(text.strip());
        } catch (NumberFormatException malformed) {
            throw refuse("unparseable amount", field);
        }
        if (parsed.stripTrailingZeros().scale() > Money.SCALE) {
            // Refused rather than rounded (ADR-0006): rounding somebody's money without telling
            // them is the whole class of defect that decision exists to prevent.
            throw refuse("an amount may have at most " + Money.SCALE + " decimal places", field);
        }
        switch (sign) {
            case NON_ZERO -> {
                if (parsed.signum() == 0) {
                    throw refuse("an amount of zero moves no money", field);
                }
            }
            case NOT_NEGATIVE -> {
                if (parsed.signum() < 0) {
                    throw refuse("an amount cannot be negative", field);
                }
            }
            case POSITIVE -> {
                if (parsed.signum() <= 0) {
                    throw refuse("an amount must be more than zero", field);
                }
            }
        }
        return Money.of(parsed, currency);
    }

    private static InvalidRequestException refuse(String detail, String field) {
        return new InvalidRequestException(
                ErrorCode.VALIDATION_FAILED, detail, Map.of("field", field));
    }
}
