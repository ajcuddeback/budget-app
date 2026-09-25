package com.budgetowl.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * An amount of money in a specific currency (ADR-0006).
 *
 * <p>Never {@code float} or {@code double} — not for amounts, not for totals, not "just for
 * display". Stored as {@code NUMERIC(19,4)}: four decimal places so intermediate results keep
 * precision before an explicit rounding step.
 *
 * <p>Arithmetic between different currencies throws. It does not coerce, and it does not convert —
 * conversion is a display concern that needs a rate and a date (ADR-0022).
 *
 * <p>Immutable. Every operation returns a new instance.
 */
public final class Money implements Comparable<Money> {

    /**
     * Storage scale. Matches {@code NUMERIC(19,4)} so a round trip through the database is exact.
     */
    public static final int SCALE = 4;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        return new Money(amount.setScale(SCALE, RoundingMode.HALF_EVEN), currency);
    }

    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negated() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /**
     * Splits this amount into {@code parts} shares that sum back to exactly this amount.
     *
     * <p>The remainder is distributed one minor unit at a time from the first share onward, so a
     * three-way split of 10.00 is 3.34 / 3.33 / 3.33 rather than three of 3.33 and a lost cent.
     * Where the remainder goes is deterministic, and it never vanishes — that is the whole
     * contract, and the property test asserts it for arbitrary amounts and part counts.
     */
    public List<Money> split(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("Cannot split into " + parts + " parts");
        }
        BigDecimal divisor = BigDecimal.valueOf(parts);
        BigDecimal base = amount.divide(divisor, SCALE, RoundingMode.DOWN);
        BigDecimal distributed = base.multiply(divisor).setScale(SCALE, RoundingMode.HALF_EVEN);
        BigDecimal remainder = amount.subtract(distributed);

        // The remainder is always a whole number of minor units at this scale.
        BigDecimal unit = BigDecimal.ONE.movePointLeft(SCALE);
        long units = remainder.divide(unit, 0, RoundingMode.HALF_EVEN).longValueExact();
        long step = units < 0 ? -1 : 1;

        List<Money> shares = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            BigDecimal share = base;
            if (Math.abs(units) > i) {
                share = share.add(unit.multiply(BigDecimal.valueOf(step)));
            }
            shares.add(new Money(share.setScale(SCALE, RoundingMode.HALF_EVEN), currency));
        }
        return List.copyOf(shares);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return amount.compareTo(other.amount) == 0 && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    /** {@code "1234.5600 GBP"}. Never used for display — formatting is locale-dependent. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
