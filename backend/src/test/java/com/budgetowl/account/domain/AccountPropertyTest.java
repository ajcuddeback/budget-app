package com.budgetowl.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.Money;
import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.math.BigDecimal;
import java.util.Currency;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for the rules whose interesting inputs are unbounded (ADR-0024).
 *
 * <p>The example tests in {@link AccountTest} prove the cases somebody thought of — three digits,
 * five digits, Arabic-Indic digits, a leading plus. These prove the invariant, which is the thing
 * ADR-0028 actually relies on: <em>nothing</em> but four ASCII digits is accepted, so the column
 * cannot grow into a full account number by any input at all.
 */
class AccountPropertyTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    /** The full width of {@code NUMERIC(19,4)}: 15 digits before the point, four after. */
    @Provide
    Arbitrary<BigDecimal> openingBalances() {
        return Arbitraries.bigDecimals()
                .between(
                        new BigDecimal("-999999999999999.9999"),
                        new BigDecimal("999999999999999.9999"))
                .ofScale(Money.SCALE);
    }

    @Provide
    Arbitrary<String> fourAsciiDigits() {
        return Arbitraries.strings().withChars("0123456789".toCharArray()).ofLength(4);
    }

    @Provide
    Arbitrary<String> anythingButFourAsciiDigits() {
        return Arbitraries.strings()
                .withCharRange((char) 0, (char) 0x0660)
                .ofMaxLength(6)
                .filter(candidate -> !candidate.matches("[0-9]{4}"));
    }

    @Provide
    Arbitrary<String> anyText() {
        return Arbitraries.strings().withCharRange((char) 0, (char) 0x0660).ofMaxLength(90);
    }

    @Property
    void anOpeningBalanceIsNeverRoundedOrDrifted(@ForAll("openingBalances") BigDecimal amount) {
        Account account =
                Account.of(household(), "Current", AccountType.CHECKING, Money.of(amount, GBP));

        assertThat(account.openingBalance().amount())
                .as("no value may be created or destroyed by storing it")
                .isEqualByComparingTo(amount);
    }

    @Property
    void correctingAnOpeningBalanceReplacesItExactly(
            @ForAll("openingBalances") BigDecimal first,
            @ForAll("openingBalances") BigDecimal second) {
        Account account =
                Account.of(household(), "Current", AccountType.CHECKING, Money.of(first, GBP));

        account.correctOpeningBalance(Money.of(second, GBP));

        assertThat(account.openingBalance().amount()).isEqualByComparingTo(second);
    }

    @Property
    void everyFourDigitCombinationIsAcceptable(@ForAll("fourAsciiDigits") String digits) {
        Account account = account();

        account.identifyBy(digits);

        assertThat(account.lastFour()).contains(digits);
    }

    @Property
    void nothingElseIsAcceptableAsLastFour(@ForAll("anythingButFourAsciiDigits") String candidate) {
        // The invariant ADR-0028 rests on. A five-digit string, a four-character string with one
        // non-digit, a Unicode digit, a newline — the field cannot hold any of them, so it cannot
        // hold a full account number however it is written to.
        Account account = account();

        assertThatThrownBy(() -> account.identifyBy(candidate))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(account.lastFour()).isEmpty();
    }

    @Property
    void aNameIsAcceptedExactlyWhenItsTrimmedFormFitsAndIsStoredTrimmed(
            @ForAll("anyText") String candidate) {
        Account account = account();
        String trimmed = candidate.strip();

        if (trimmed.isEmpty() || trimmed.length() > Account.NAME_MAX_LENGTH) {
            assertThatThrownBy(() -> account.rename(candidate))
                    .isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThatCode(() -> account.rename(candidate)).doesNotThrowAnyException();
            assertThat(account.name()).isEqualTo(trimmed);
        }
    }

    private static Household household() {
        return Ids.withId(Household.named("Home", GBP));
    }

    private static Account account() {
        return Account.of(household(), "Current", AccountType.CHECKING, Money.zero(GBP));
    }
}
