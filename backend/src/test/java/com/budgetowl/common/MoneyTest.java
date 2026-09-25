package com.budgetowl.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void storesAtFourDecimalPlacesToMatchTheDatabaseColumn() {
        assertThat(Money.of("1.5", "GBP").amount()).isEqualByComparingTo(new BigDecimal("1.5000"));
    }

    @Test
    void addsAmountsOfTheSameCurrency() {
        assertThat(Money.of("10.00", "GBP").plus(Money.of("2.50", "GBP")))
                .isEqualTo(Money.of("12.50", "GBP"));
    }

    @Test
    void throwsWhenCurrenciesDiffer() {
        assertThatThrownBy(() -> Money.of("10.00", "GBP").plus(Money.of("10.00", "EUR")))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("GBP")
                .hasMessageContaining("EUR");
    }

    @Test
    void comparingAcrossCurrenciesThrowsRatherThanOrderingNonsense() {
        assertThatThrownBy(() -> Money.of("10.00", "GBP").compareTo(Money.of("1.00", "JPY")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void splitsTenPoundsThreeWaysWithoutLosingAPenny() {
        List<Money> shares = Money.of("10.00", "GBP").split(3);

        assertThat(shares)
                .containsExactly(
                        Money.of("3.3334", "GBP"),
                        Money.of("3.3333", "GBP"),
                        Money.of("3.3333", "GBP"));
        assertThat(shares.stream().reduce(Money.zero(shares.get(0).currency()), Money::plus))
                .isEqualTo(Money.of("10.00", "GBP"));
    }

    @Test
    void splitsANegativeAmountWithoutLosingAPenny() {
        List<Money> shares = Money.of("-10.00", "GBP").split(3);

        assertThat(shares.stream().reduce(Money.zero(Money.of("0", "GBP").currency()), Money::plus))
                .isEqualTo(Money.of("-10.00", "GBP"));
    }

    @Test
    void rejectsASplitIntoZeroOrFewerParts() {
        assertThatThrownBy(() -> Money.of("10.00", "GBP").split(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityIgnoresTrailingZeroesButNotCurrency() {
        assertThat(Money.of("5.0", "GBP")).isEqualTo(Money.of("5.00", "GBP"));
        assertThat(Money.of("5.00", "GBP")).isNotEqualTo(Money.of("5.00", "EUR"));
    }

    @Test
    void reportsSign() {
        assertThat(Money.of("0.00", "GBP").isZero()).isTrue();
        assertThat(Money.of("-0.01", "GBP").isNegative()).isTrue();
        assertThat(Money.of("0.01", "GBP").isPositive()).isTrue();
    }

    @Test
    void negatesAndAbsolutes() {
        assertThat(Money.of("-3.00", "GBP").negated()).isEqualTo(Money.of("3.00", "GBP"));
        assertThat(Money.of("-3.00", "GBP").abs()).isEqualTo(Money.of("3.00", "GBP"));
        assertThat(Money.of("10.00", "GBP").minus(Money.of("4.00", "GBP")))
                .isEqualTo(Money.of("6.00", "GBP"));
    }
}
