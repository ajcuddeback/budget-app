package com.budgetowl.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.Money;
import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void aNewAccountKnowsWhereItLivesAndWhatItHeld() {
        Household household = household();

        Account account =
                Account.of(household, "Current", AccountType.CHECKING, Money.of("250.00", "GBP"));

        assertThat(account.householdId()).isEqualTo(household.id());
        assertThat(account.name()).isEqualTo("Current");
        assertThat(account.type()).isEqualTo(AccountType.CHECKING);
        assertThat(account.currency()).isEqualTo(GBP);
        assertThat(account.openingBalance()).isEqualTo(Money.of("250.00", "GBP"));
        assertThat(account.lastFour()).isEmpty();
        assertThat(account.isArchived()).isFalse();
        assertThat(account.id()).isNull();
    }

    @Test
    void takesItsCurrencyFromTheAmountRatherThanAsASecondParameter() {
        // One fact, one parameter. Two would let a caller disagree with itself and store an
        // amount labelled with a currency it is not denominated in.
        Account account =
                Account.of(household(), "Urlaub", AccountType.SAVINGS, Money.of("40.00", "EUR"));

        assertThat(account.currency()).isEqualTo(EUR);
        assertThat(account.openingBalance().currency()).isEqualTo(EUR);
    }

    @Test
    void refusesToExistWithoutAHouseholdANameATypeOrAnOpeningBalance() {
        Household household = household();
        Money opening = Money.of("0.00", "GBP");

        assertThatNullPointerException()
                .isThrownBy(() -> Account.of(null, "Current", AccountType.CHECKING, opening));
        assertThatNullPointerException()
                .isThrownBy(() -> Account.of(household, null, AccountType.CHECKING, opening));
        assertThatNullPointerException()
                .isThrownBy(() -> Account.of(household, "Current", null, opening));
        assertThatNullPointerException()
                .isThrownBy(() -> Account.of(household, "Current", AccountType.CHECKING, null));
    }

    @Test
    void refusesAnUnsavedHouseholdBecauseItHasNoIdToOwnAnythingWith() {
        Household unsaved = Household.named("Home", GBP);

        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                Account.of(
                                        unsaved,
                                        "Current",
                                        AccountType.CHECKING,
                                        Money.of("0.00", "GBP")))
                .withMessageContaining("household.id");
    }

    // ------------------------------------------------------------------------------------- name

    @Test
    void storesTheNameTrimmed() {
        Account account =
                Account.of(
                        household(), "  Current  ", AccountType.CHECKING, Money.of("0.00", "GBP"));

        assertThat(account.name()).isEqualTo("Current");
    }

    @Test
    void refusesANameThatIsOnlyWhitespace() {
        assertThatIllegalArgumentException().isThrownBy(() -> named(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> named("\t\n"));
        assertThatIllegalArgumentException().isThrownBy(() -> named(""));
    }

    @Test
    void refusesANameLongerThanEightyCharactersOnceTrimmed() {
        assertThatCode(() -> named("n".repeat(80))).doesNotThrowAnyException();
        assertThatCode(() -> named("  " + "n".repeat(80) + "  ")).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> named("n".repeat(81)));
    }

    @Test
    void allowsTwoAccountsToShareAName() {
        // Real households have two pots both called "Savings". Nothing may assume uniqueness.
        Household household = household();

        Account first = Account.of(household, "Savings", AccountType.SAVINGS, Money.zero(GBP));
        Account second = Account.of(household, "Savings", AccountType.SAVINGS, Money.zero(GBP));

        assertThat(first.name()).isEqualTo(second.name());
    }

    @Test
    void canBeRenamed() {
        Account account = named("Current");

        account.rename("  Everyday  ");

        assertThat(account.name()).isEqualTo("Everyday");
        assertThatIllegalArgumentException().isThrownBy(() -> account.rename("   "));
        assertThatNullPointerException().isThrownBy(() -> account.rename(null));
    }

    // -------------------------------------------------------------------------------- lastFour

    @Test
    void acceptsExactlyFourAsciiDigits() {
        Account account = named("Current");

        account.identifyBy("0000");
        assertThat(account.lastFour()).contains("0000");

        account.identifyBy("4412");
        assertThat(account.lastFour()).contains("4412");
    }

    @Test
    void rejectsAnythingThatIsNotExactlyFourAsciiDigits() {
        // ADR-0028. The field exists so that the column CANNOT hold a full account number, which
        // only holds while every one of these is refused.
        Account account = named("Current");

        for (String rejected :
                new String[] {
                    "", "123", "12345", "abcd", "12a4", "١٢٣٤", "+123", " 123", "123 ", "12 4",
                    "1234\n", "１２３４"
                }) {
            assertThatThrownBy(() -> account.identifyBy(rejected))
                    .as("lastFour %s", rejected.codePoints().boxed().toList())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void doesNotQuoteTheRejectedDigitsInTheMessage() {
        // The message can reach a log or a problem detail, and the value is a partial financial
        // identifier (ADR-0028).
        Account account = named("Current");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> account.identifyBy("99999999"))
                .withMessageNotContaining("99999999");
    }

    @Test
    void clearsTheDigitsWithNull() {
        Account account = named("Current");
        account.identifyBy("4412");

        account.identifyBy(null);

        assertThat(account.lastFour()).isEmpty();
    }

    // ----------------------------------------------------------------- type, currency, balance

    @Test
    void changesTypeFreelyBecauseNothingStoredDependsOnIt() {
        Account account = named("Current");

        account.changeTypeTo(AccountType.CREDIT_CARD);

        assertThat(account.type()).isEqualTo(AccountType.CREDIT_CARD);
        assertThatNullPointerException().isThrownBy(() -> account.changeTypeTo(null));
    }

    @Test
    void changingCurrencyRelabelsTheFigureWithoutMovingIt() {
        // Which is exactly why the service refuses it once a transaction exists: 100 pounds would
        // become 100 euros and nothing in the UI would admit it happened.
        Account account =
                Account.of(household(), "Current", AccountType.CHECKING, Money.of("100.00", "GBP"));

        account.changeCurrencyTo(EUR);

        assertThat(account.currency()).isEqualTo(EUR);
        assertThat(account.openingBalance()).isEqualTo(Money.of("100.00", "EUR"));
        assertThatNullPointerException().isThrownBy(() -> account.changeCurrencyTo(null));
    }

    @Test
    void correctsAMistypedOpeningBalance() {
        Account account =
                Account.of(household(), "Current", AccountType.CHECKING, Money.of("250.00", "GBP"));

        account.correctOpeningBalance(Money.of("25.00", "GBP"));

        assertThat(account.openingBalance()).isEqualTo(Money.of("25.00", "GBP"));
        assertThatNullPointerException().isThrownBy(() -> account.correctOpeningBalance(null));
    }

    @Test
    void refusesAnOpeningBalanceInTheWrongCurrency() {
        Account account =
                Account.of(household(), "Current", AccountType.CHECKING, Money.of("250.00", "GBP"));

        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> account.correctOpeningBalance(Money.of("250.00", "EUR")));
        assertThat(account.openingBalance()).isEqualTo(Money.of("250.00", "GBP"));
    }

    @Test
    void acceptsANegativeOpeningBalanceWithoutComment() {
        // A credit card you owe 400 on opens at -400. No warning, no sign coerced from the type.
        Account card =
                Account.of(
                        household(), "Amex", AccountType.CREDIT_CARD, Money.of("-400.00", "GBP"));

        assertThat(card.openingBalance().isNegative()).isTrue();
        assertThat(card.openingBalance().amount())
                .isEqualByComparingTo(new BigDecimal("-400.0000"));
    }

    @Test
    void treatsZeroAsAnAmountRatherThanAsUnset() {
        Account account = Account.of(household(), "Tin", AccountType.CASH, Money.zero(GBP));

        assertThat(account.openingBalance().isZero()).isTrue();
        assertThat(account.openingBalance()).isEqualTo(Money.of("0.0000", "GBP"));
    }

    @Test
    void keepsFourDecimalPlacesOfAnOpeningBalance() {
        Account account =
                Account.of(
                        household(),
                        "Current",
                        AccountType.CHECKING,
                        Money.of("-1234.5678", "GBP"));

        assertThat(account.openingBalance().amount())
                .isEqualByComparingTo(new BigDecimal("-1234.5678"));
    }

    // ------------------------------------------------------------------------------- archiving

    @Test
    void archivesAndUnarchives() {
        Account account = named("Old current");

        account.archive();
        assertThat(account.isArchived()).isTrue();

        account.unarchive();
        assertThat(account.isArchived()).isFalse();
    }

    @Test
    void archivingAnArchivedAccountIsTheStateTheCallerAskedFor() {
        Account account = named("Old current");

        account.archive();
        account.archive();

        assertThat(account.isArchived()).isTrue();
    }

    @Test
    void archivingDoesNotZeroTheBalance() {
        Account account =
                Account.of(household(), "Old", AccountType.SAVINGS, Money.of("12.34", "GBP"));

        account.archive();

        assertThat(account.openingBalance()).isEqualTo(Money.of("12.34", "GBP"));
    }

    // ---------------------------------------------------------------------- identity, printing

    @Test
    void equalsItselfBeforeItHasAnId() {
        Account account = named("Current");

        assertThat(account).isEqualTo(account).isNotEqualTo(named("Current"));
    }

    @Test
    void equalsAnotherInstanceOfTheSameRow() {
        UUID id = UUID.randomUUID();

        assertThat(Ids.withId(named("Current"), id))
                .isEqualTo(Ids.withId(named("Everyday"), id))
                .isNotEqualTo(Ids.withId(named("Current")))
                .isNotEqualTo("not an account")
                .hasSameHashCodeAs(Ids.withId(named("Current")));
    }

    @Test
    void printsNeitherItsNameNorItsDigits() {
        // An account's name is whatever the user typed and belongs in the database rather than the
        // log stream; lastFour is a partial financial identifier (ADR-0028).
        Account account = named("Ada's secret stash");
        account.identifyBy("4412");

        assertThat(account.toString())
                .doesNotContain("Ada's secret stash")
                .doesNotContain("4412")
                .contains("CHECKING");
    }

    private static Household household() {
        return Ids.withId(Household.named("Home", GBP));
    }

    private static Account named(String name) {
        return Account.of(household(), name, AccountType.CHECKING, Money.zero(GBP));
    }
}
