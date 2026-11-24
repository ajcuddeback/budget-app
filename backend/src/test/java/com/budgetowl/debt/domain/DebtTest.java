package com.budgetowl.debt.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.Money;
import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The debt entity validating itself, with no database and no Spring context.
 *
 * <p>Two properties are the reason this file exists. <b>Only entered values are stored</b>, so
 * releasing a field has to leave {@code null} behind rather than a zero or a stale figure — and
 * <b>the two faces of the horizon are one unknown</b>, so entering either has to clear the other.
 * Both are invisible in a response and would surface as a payoff date nobody can explain.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DebtTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 1);

    @Test
    void a_new_debt_knows_what_is_owed_and_nothing_it_was_not_told() {
        Household household = household();

        Debt debt = visa(household);

        assertThat(debt.householdId()).isEqualTo(household.id());
        assertThat(debt.name()).isEqualTo("Visa");
        assertThat(debt.kind()).isEqualTo(DebtKind.CREDIT_CARD);
        assertThat(debt.currency()).isEqualTo(GBP);
        assertThat(debt.anchorBalance()).isEqualTo(Money.of("6180.00", "GBP"));
        assertThat(debt.anchorDate()).isEqualTo(SEPTEMBER);
        assertThat(debt.balanceSource()).isEqualTo(BalanceSource.CONFIRMED);
        assertThat(debt.minimumPayment()).isEqualTo(Money.of("118.00", "GBP"));
        // Everything a solver could work out is absent, which is the whole convention.
        assertThat(debt.apr()).isEmpty();
        assertThat(debt.payment()).isEmpty();
        assertThat(debt.monthsLeft()).isEmpty();
        assertThat(debt.payoffMonth()).isEmpty();
        assertThat(debt.servicer()).isEmpty();
        assertThat(debt.originalBalance()).isEmpty();
        assertThat(debt.customOrder()).isEmpty();
        assertThat(debt.accountId()).isEmpty();
        assertThat(debt.billId()).isEmpty();
        assertThat(debt.paidOffAt()).isEmpty();
        assertThat(debt.isPaidOff()).isFalse();
        assertThat(debt.isArchived()).isFalse();
        assertThat(debt.createdAt()).isNull();
        assertThat(debt.updatedAt()).isNull();
        assertThat(debt.version()).isZero();
    }

    @Test
    void refuses_to_exist_without_a_household_a_name_a_kind_or_a_balance() {
        Household household = household();
        Money balance = Money.of("6180.00", "GBP");
        Money minimum = Money.of("118.00", "GBP");

        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                Debt.of(
                                        null,
                                        "Visa",
                                        DebtKind.CREDIT_CARD,
                                        balance,
                                        SEPTEMBER,
                                        minimum));
        assertThatNullPointerException()
                .isThrownBy(() -> Debt.of(household, "Visa", null, balance, SEPTEMBER, minimum));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                Debt.of(
                                        household,
                                        "  ",
                                        DebtKind.CREDIT_CARD,
                                        balance,
                                        SEPTEMBER,
                                        minimum));
    }

    @Test
    void a_debt_starts_from_a_positive_balance_even_though_it_may_reach_zero() {
        Household household = household();
        Money minimum = Money.of("118.00", "GBP");

        // Created at zero is nothing to reduce. REACHING zero is how one is paid off, and going
        // below it is an overpayment the screen has to be able to show — see anchorAt below.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                Debt.of(
                                        household,
                                        "Settled",
                                        DebtKind.CREDIT_CARD,
                                        Money.zero(GBP),
                                        SEPTEMBER,
                                        minimum));
    }

    @Test
    void the_anchor_accepts_zero_and_a_negative_figure() {
        Debt debt = visa(household());

        debt.anchorAt(Money.zero(GBP), SEPTEMBER, BalanceSource.CONFIRMED);
        assertThat(debt.anchorBalance()).isEqualTo(Money.zero(GBP));

        // Clamping an overpayment would hide a real error in somebody's favour, and the schema
        // deliberately carries no sign constraint here either.
        debt.anchorAt(Money.of("-118.44", "GBP"), SEPTEMBER, BalanceSource.ESTIMATED);
        assertThat(debt.anchorBalance()).isEqualTo(Money.of("-118.44", "GBP"));
        assertThat(debt.balanceSource()).isEqualTo(BalanceSource.ESTIMATED);
    }

    @Test
    void every_amount_has_to_be_in_the_debts_own_currency() {
        Debt debt = visa(household());

        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> debt.setMinimumPayment(Money.of("118.00", "EUR")));
        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> debt.enterPayment(Money.of("445.00", "EUR")));
        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> debt.setOriginalBalance(Money.of("10650.00", "EUR")));
        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(
                        () ->
                                debt.anchorAt(
                                        Money.of("1.00", "EUR"),
                                        SEPTEMBER,
                                        BalanceSource.CONFIRMED));
        assertThat(EUR).isNotEqualTo(debt.currency());
    }

    @Test
    void releasing_a_field_leaves_nothing_behind_rather_than_a_zero() {
        Debt debt = visa(household());
        debt.enterApr(new BigDecimal("22.900"));
        debt.enterPayment(Money.of("445.00", "GBP"));

        debt.releaseApr();
        debt.releasePayment();

        // NULL is the entered/solved flag. A zero would be a figure nobody asserted, in the one
        // place the API promises to say which is which.
        assertThat(debt.apr()).isEmpty();
        assertThat(debt.payment()).isEmpty();
    }

    @Test
    void entering_one_face_of_the_horizon_clears_the_other() {
        Debt debt = visa(household());

        debt.enterMonthsLeft(31);
        assertThat(debt.monthsLeft()).contains(31);
        assertThat(debt.payoffMonth()).isEmpty();
        assertThat(debt.hasEnteredHorizon()).isTrue();
        assertThat(debt.hasBothFacesOfTheHorizon()).isFalse();

        debt.enterPayoffMonth(YearMonth.of(2027, 4));
        assertThat(debt.payoffMonth()).contains(YearMonth.of(2027, 4));
        assertThat(debt.monthsLeft()).isEmpty();
        assertThat(debt.hasBothFacesOfTheHorizon()).isFalse();

        debt.releaseHorizon();
        assertThat(debt.hasEnteredHorizon()).isFalse();
        assertThat(debt.monthsLeft()).isEmpty();
        assertThat(debt.payoffMonth()).isEmpty();
    }

    @Test
    void three_entered_solvable_fields_are_reported_rather_than_silently_trimmed() {
        Debt debt = visa(household());
        debt.enterApr(new BigDecimal("22.900"));
        debt.enterPayment(Money.of("445.00", "GBP"));

        assertThat(debt.isOverDetermined()).isFalse();

        debt.enterMonthsLeft(31);

        // The entity reports it; the service answers 422 naming the fields. It is deliberately
        // not refused inside the setters: a terms edit that releases one field and enters
        // another passes through this state on its way, and refusing it would make the legal
        // edit unwritable.
        assertThat(debt.isOverDetermined()).isTrue();
    }

    @Test
    void a_blank_servicer_is_absent_rather_than_an_empty_string() {
        Debt debt = visa(household());

        debt.servicedBy("  Chase  ");
        assertThat(debt.servicer()).contains("Chase");

        debt.servicedBy("   ");
        assertThat(debt.servicer()).as("an empty string is a null that took a detour").isEmpty();

        debt.servicedBy(null);
        assertThat(debt.servicer()).isEmpty();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> debt.servicedBy("x".repeat(Debt.SERVICER_MAX_LENGTH + 1)));
    }

    @Test
    void a_progress_baseline_is_positive_or_absent() {
        Debt debt = visa(household());

        debt.setOriginalBalance(Money.of("10650.00", "GBP"));
        assertThat(debt.originalBalance()).contains(Money.of("10650.00", "GBP"));

        debt.setOriginalBalance(null);
        assertThat(debt.originalBalance()).isEmpty();

        // Progress is (original − balance) ÷ original, so zero is a division by zero and a
        // negative baseline inverts the percentage. Absent is the honest value.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> debt.setOriginalBalance(Money.zero(GBP)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> debt.setOriginalBalance(Money.of("-1.00", "GBP")));
    }

    @Test
    void the_figures_that_have_a_floor_say_what_it_is() {
        Debt debt = visa(household());

        // Zero is legal for a minimum: a promotional period with no minimum due, and a minimum
        // the household does not know yet, are both better recorded than refused.
        assertThatCode(() -> debt.setMinimumPayment(Money.zero(GBP))).doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> debt.setMinimumPayment(Money.of("-1.00", "GBP")));

        // An entered payment of zero clears nothing, and a horizon of zero months is a paid-off
        // debt rather than a projection.
        assertThatIllegalArgumentException().isThrownBy(() -> debt.enterPayment(Money.zero(GBP)));
        assertThatIllegalArgumentException().isThrownBy(() -> debt.enterMonthsLeft(0));
        assertThatIllegalArgumentException().isThrownBy(() -> debt.placeAt(-1));
    }

    @Test
    void a_rate_is_at_least_zero_and_under_a_thousand_percent() {
        Debt debt = visa(household());

        // Zero is a real promotional rate and a payday rate above 100% is real too: a household
        // that cannot record its worst debt is worse off than one looking at an alarming number.
        assertThatCode(() -> debt.enterApr(BigDecimal.ZERO)).doesNotThrowAnyException();
        assertThatCode(() -> debt.enterApr(new BigDecimal("664.000"))).doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> debt.enterApr(new BigDecimal("-0.001")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> debt.enterApr(new BigDecimal("1000.000")));
    }

    @Test
    void settling_and_reopening_are_both_recorded() {
        Debt debt = visa(household());
        Instant when = Instant.parse("2027-04-01T00:00:00Z");

        debt.settle(when);
        assertThat(debt.isPaidOff()).isTrue();
        assertThat(debt.paidOffAt()).contains(when);

        debt.reopen();
        assertThat(debt.isPaidOff()).isFalse();
        assertThat(debt.paidOffAt()).isEmpty();
    }

    @Test
    void archiving_retires_a_debt_without_losing_it() {
        Debt debt = visa(household());

        debt.archive();
        assertThat(debt.isArchived()).isTrue();

        debt.unarchive();
        assertThat(debt.isArchived()).isFalse();
    }

    @Test
    void the_links_are_set_and_cleared_by_id_and_never_inferred() {
        Debt debt = visa(household());
        java.util.UUID account = java.util.UUID.randomUUID();
        java.util.UUID bill = java.util.UUID.randomUUID();

        debt.heldAt(account);
        debt.minimumPaidBy(bill);
        debt.placeAt(2);
        debt.reclassify(DebtKind.INSTALLMENT);
        debt.rename("Car loan");

        assertThat(debt.accountId()).contains(account);
        assertThat(debt.billId()).contains(bill);
        assertThat(debt.customOrder()).contains(2);
        assertThat(debt.kind()).isEqualTo(DebtKind.INSTALLMENT);
        assertThat(debt.name()).isEqualTo("Car loan");

        debt.heldAt(null);
        debt.minimumPaidBy(null);
        debt.placeAt(null);
        assertThat(debt.accountId()).isEmpty();
        assertThat(debt.billId()).isEmpty();
        assertThat(debt.customOrder()).isEmpty();
    }

    @Test
    void a_name_is_one_to_sixty_characters() {
        Debt debt = visa(household());

        debt.rename("  Visa 8823  ");
        assertThat(debt.name()).isEqualTo("Visa 8823");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> debt.rename("x".repeat(Debt.NAME_MAX_LENGTH + 1)));
        assertThatNullPointerException().isThrownBy(() -> debt.rename(null));
    }

    @Test
    void prints_no_name_no_servicer_no_balance_and_no_rate() {
        Debt debt = Ids.withId(visa(household()));
        debt.servicedBy("Nelnet");
        debt.enterApr(new BigDecimal("22.900"));

        String printed = debt.toString();

        // The sharpest field this app holds is the servicer: one string, no amount attached, and
        // it still says this household has student debt.
        assertThat(printed)
                .doesNotContain("Nelnet")
                .doesNotContain("Visa")
                .doesNotContain("6180")
                .doesNotContain("22.9");
        assertThat(printed).contains(debt.id().toString());
    }

    private static Household household() {
        return Ids.withId(Household.named("Home", GBP));
    }

    private static Debt visa(Household household) {
        return Debt.of(
                household,
                "Visa",
                DebtKind.CREDIT_CARD,
                Money.of("6180.00", "GBP"),
                SEPTEMBER,
                Money.of("118.00", "GBP"));
    }
}
