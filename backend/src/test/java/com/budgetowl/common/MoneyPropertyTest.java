package com.budgetowl.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for money (ADR-0024), where the interesting inputs are the ones nobody
 * enumerated. Example tests prove the cases we thought of; these prove the invariant.
 */
class MoneyPropertyTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    @Provide
    Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-1000000"), new BigDecimal("1000000"))
                .ofScale(Money.SCALE);
    }

    @Property
    void aSplitAlwaysReconcilesExactly(
            @ForAll("amounts") BigDecimal amount, @ForAll @IntRange(min = 1, max = 50) int parts) {
        Money original = Money.of(amount, GBP);

        List<Money> shares = original.split(parts);
        Money total = shares.stream().reduce(Money.zero(GBP), Money::plus);

        assertThat(total).as("no value may be created or destroyed by a split").isEqualTo(original);
        assertThat(shares).hasSize(parts);
    }

    @Property
    void sharesNeverDifferByMoreThanOneMinorUnit(
            @ForAll("amounts") BigDecimal amount, @ForAll @IntRange(min = 1, max = 50) int parts) {
        List<Money> shares = Money.of(amount, GBP).split(parts);

        BigDecimal max =
                shares.stream().map(Money::amount).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal min =
                shares.stream().map(Money::amount).min(BigDecimal::compareTo).orElseThrow();

        assertThat(max.subtract(min))
                .as("a split should be fair, not merely exact")
                .isLessThanOrEqualTo(BigDecimal.ONE.movePointLeft(Money.SCALE));
    }

    @Property
    void additionIsCommutative(
            @ForAll("amounts") BigDecimal left, @ForAll("amounts") BigDecimal right) {
        Money a = Money.of(left, GBP);
        Money b = Money.of(right, GBP);

        assertThat(a.plus(b)).isEqualTo(b.plus(a));
    }

    @Property
    void subtractingIsAddingTheNegation(
            @ForAll("amounts") BigDecimal left, @ForAll("amounts") BigDecimal right) {
        Money a = Money.of(left, GBP);
        Money b = Money.of(right, GBP);

        assertThat(a.minus(b)).isEqualTo(a.plus(b.negated()));
    }

    @Property
    void aRoundTripThroughTheStorageScaleChangesNothing(@ForAll("amounts") BigDecimal amount) {
        Money money = Money.of(amount, GBP);

        assertThat(Money.of(money.amount(), GBP)).isEqualTo(money);
        assertThat(money.amount().scale()).isEqualTo(Money.SCALE);
    }
}
