package com.budgetowl.debt.service;

/**
 * The four mutually constrained quantities of a debt's terms (docs/features/debt-plan.md).
 *
 * <p>One equation among four quantities means three degrees of freedom: give any three and the
 * fourth follows. Three of these are therefore legal solve targets; {@link #BALANCE} is not, and
 * says so.
 *
 * <p>The {@code code} is the name used when a field is named in a problem response — the same
 * spelling the API uses for the field itself, so a client can put the message beside the input it
 * belongs to.
 */
public enum TermField {

    /**
     * Always supplied, never solved. It is the one number a household can read off a statement, and
     * an app that <em>derives</em> what you owe from a rate and a payment schedule has replaced a
     * fact with an inference and then shown it in the headline position.
     */
    BALANCE("balance", false),

    /** The annual rate as a percentage, e.g. {@code 22.900}. Solvable. */
    APR("apr", true),

    /** The payment being made — not the minimum. Solvable. */
    PAYMENT("payment", true),

    /** One unknown with two faces, {@code monthsLeft} and {@code payoffMonth}. Solvable. */
    HORIZON("horizon", true);

    private final String code;
    private final boolean solvable;

    TermField(String code, boolean solvable) {
        this.code = code;
        this.solvable = solvable;
    }

    public String code() {
        return code;
    }

    public boolean isSolvable() {
        return solvable;
    }
}
