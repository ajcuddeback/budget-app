package com.budgetowl.debt.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.Money;
import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The payment row's two identities, which hold <b>by construction</b> rather than by everyone
 * remembering them: {@code interest + principal = amount}, and {@code balanceAfter = balanceBefore
 * − principal}.
 *
 * <p>An off-by-a-cent split is the defect nobody would see on screen — both figures look right and
 * only their sum is wrong — so it is proved here, where it costs nothing, as well as by {@code
 * ck_debt_payments_split_reconciles} in the database.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DebtPaymentTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final LocalDate PAID = LocalDate.of(2026, 9, 15);

    @Test
    void the_split_reconciles_exactly_and_the_principal_takes_the_remainder() {
        DebtPayment payment = payment(Money.of("445.00", "GBP"), Money.of("117.94", "GBP"));

        assertThat(payment.amount()).isEqualByComparingTo("445.0000");
        assertThat(payment.interest()).isEqualByComparingTo("117.94");
        assertThat(payment.principal()).isEqualByComparingTo("327.06");
        assertThat(payment.interest().add(payment.principal()))
                .isEqualByComparingTo(payment.amount());
        assertThat(payment.balanceAfter()).isEqualByComparingTo("5852.94");
        assertThat(payment.balanceBefore()).isEqualByComparingTo("6180.0000");
        assertThat(payment.provenance()).isEqualTo(PaymentProvenance.USER_LOGGED);
        assertThat(payment.paidDate()).isEqualTo(PAID);
    }

    @Test
    void an_overpayment_takes_the_balance_below_zero_rather_than_being_clamped() {
        DebtPayment payment = payment(Money.of("6500.00", "GBP"), Money.of("117.94", "GBP"));

        // The screen says the debt is overpaid by that much. Clamping would hide a real error in
        // somebody's favour.
        assertThat(payment.balanceAfter()).isNegative();
        assertThat(payment.interest().add(payment.principal()))
                .isEqualByComparingTo(payment.amount());
    }

    @Test
    void a_statement_correction_runs_the_other_way_and_is_still_a_payment_row() {
        Debt debt = debt();

        DebtPayment correction =
                DebtPayment.of(
                        debt,
                        PAID,
                        Money.of("-4.10", "GBP"),
                        Money.zero(GBP),
                        Money.of("5852.94", "GBP"),
                        PaymentProvenance.STATEMENT_CORRECTION);

        // "Statement corrected +$4.10": a negative amount against a rising balance, which is why
        // no sign constraint exists on these columns.
        assertThat(correction.amount()).isEqualByComparingTo("-4.1000");
        assertThat(correction.principal()).isEqualByComparingTo("-4.1000");
        assertThat(correction.balanceAfter()).isEqualByComparingTo("5857.04");
        assertThat(correction.provenance()).isEqualTo(PaymentProvenance.STATEMENT_CORRECTION);
    }

    @Test
    void correcting_the_amount_keeps_the_interest_that_was_true_on_the_day() {
        DebtPayment payment = payment(Money.of("445.00", "GBP"), Money.of("117.94", "GBP"));

        payment.correctAmount(Money.of("500.00", "GBP"));

        // A household correcting a mistyped figure is not telling us the rate was different.
        // Recomputing the interest here would rewrite history to agree with the present.
        assertThat(payment.interest()).isEqualByComparingTo("117.94");
        assertThat(payment.principal()).isEqualByComparingTo("382.06");
        assertThat(payment.balanceAfter()).isEqualByComparingTo("5797.94");
        assertThat(payment.interest().add(payment.principal()))
                .isEqualByComparingTo(payment.amount());
    }

    @Test
    void the_links_are_evidence_and_are_set_and_cleared_by_id() {
        DebtPayment payment = payment(Money.of("445.00", "GBP"), Money.of("117.94", "GBP"));
        UUID transaction = UUID.randomUUID();
        UUID occurrence = UUID.randomUUID();

        payment.settledBy(transaction);
        payment.recordedFrom(occurrence);
        payment.on(LocalDate.of(2026, 9, 16));

        assertThat(payment.transactionId()).contains(transaction);
        assertThat(payment.billOccurrenceId()).contains(occurrence);
        assertThat(payment.paidDate()).isEqualTo(LocalDate.of(2026, 9, 16));

        // Deleting the ledger row clears the link and leaves everything else exactly as it was
        // (ADR-0035) — which is this, called by the database's own ON DELETE SET NULL.
        payment.settledBy(null);
        payment.recordedFrom(null);
        assertThat(payment.transactionId()).isEmpty();
        assertThat(payment.billOccurrenceId()).isEmpty();
        assertThat(payment.balanceAfter()).isEqualByComparingTo("5852.94");
    }

    @Test
    void a_blank_note_is_absent_rather_than_an_empty_string() {
        DebtPayment payment = payment(Money.of("445.00", "GBP"), Money.of("117.94", "GBP"));

        payment.annotate("  paid early  ");
        assertThat(payment.note()).contains("paid early");

        payment.annotate("   ");
        assertThat(payment.note()).isEmpty();

        payment.annotate(null);
        assertThat(payment.note()).isEmpty();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> payment.annotate("x".repeat(DebtPayment.NOTE_MAX_LENGTH + 1)));
    }

    @Test
    void every_figure_has_to_be_in_the_debts_currency() {
        Debt debt = debt();

        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(
                        () ->
                                DebtPayment.of(
                                        debt,
                                        PAID,
                                        Money.of("445.00", "EUR"),
                                        Money.zero(GBP),
                                        Money.of("6180.00", "GBP"),
                                        PaymentProvenance.USER_LOGGED));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                DebtPayment.of(
                                        debt,
                                        null,
                                        Money.of("445.00", "GBP"),
                                        Money.zero(GBP),
                                        Money.of("6180.00", "GBP"),
                                        PaymentProvenance.USER_LOGGED));
    }

    @Test
    void knows_the_household_that_owns_it_and_the_debt_it_belongs_to() {
        Household household = household();
        Debt debt = Ids.withId(debt(household));

        DebtPayment payment =
                DebtPayment.of(
                        debt,
                        PAID,
                        Money.of("445.00", "GBP"),
                        Money.zero(GBP),
                        Money.of("6180.00", "GBP"),
                        PaymentProvenance.USER_LOGGED);

        assertThat(payment.householdId()).isEqualTo(household.id());
        assertThat(payment.debtId()).isEqualTo(debt.id());
        assertThat(payment.version()).isZero();
        assertThat(payment.createdAt()).isNull();
        assertThat(payment.updatedAt()).isNull();
    }

    @Test
    void prints_no_amount() {
        DebtPayment payment =
                Ids.withId(payment(Money.of("445.00", "GBP"), Money.of("117.94", "GBP")));

        String printed = payment.toString();

        // A payment history is a behavioural record: who pays late, who pays extra, and when
        // money appeared.
        assertThat(printed).doesNotContain("445").doesNotContain("117.9");
        assertThat(printed).contains(PAID.toString()).contains("USER_LOGGED");
    }

    private static DebtPayment payment(Money amount, Money interest) {
        return DebtPayment.of(
                debt(),
                PAID,
                amount,
                interest,
                Money.of(new BigDecimal("6180.0000"), GBP),
                PaymentProvenance.USER_LOGGED);
    }

    private static Debt debt() {
        return debt(household());
    }

    private static Debt debt(Household household) {
        return Debt.of(
                household,
                "Visa",
                DebtKind.CREDIT_CARD,
                Money.of("6180.00", "GBP"),
                LocalDate.of(2026, 9, 1),
                Money.of("118.00", "GBP"));
    }

    private static Household household() {
        return Ids.withId(Household.named("Home", GBP));
    }
}
