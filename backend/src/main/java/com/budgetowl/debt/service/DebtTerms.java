package com.budgetowl.debt.service;

import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

/**
 * A debt's terms as the solver sees them: four mutually constrained quantities, one equation
 * (docs/features/debt-plan.md).
 *
 * <p><b>Nullable means "solve for me".</b> That is the whole convention, and it is the same one the
 * {@code debts} row uses — a solved field is {@code NULL} in the database, so nullability is the
 * {@code entered} / {@code solved} flag and there is no parallel set of booleans that could
 * disagree with the values they describe.
 *
 * <p>Three degrees of freedom means <b>exactly one</b> of {@link #apr()}, {@link #payment()} and
 * {@link #horizon()} may be absent when the solver is asked to work. All three present is
 * over-determined ({@code 422 terms-over-determined}); two or more absent is under-determined.
 * Neither is a judgement this record makes — see {@link #isOverDetermined()} and {@link
 * #solveTarget()} — because an editor legitimately holds a half-filled set while somebody types.
 *
 * @param balance always supplied, never solved. Positive for a debt with anything left on it; zero
 *     is how a debt is paid off and negative is an overpayment the screen has to be able to show,
 *     so neither is rejected here
 * @param apr the annual rate as a <b>percentage</b> — {@code 22.900}, because that is what a
 *     statement prints. The monthly rate is {@code apr ÷ 100 ÷ 12} and nothing stores it. Null
 *     means solve for it
 * @param payment the payment being made, not the minimum. Normally supplied by the plan. Null means
 *     solve for it
 * @param horizon one unknown with two faces. Null means solve for it
 * @param firstPaymentMonth the month the projection's first period starts in — the debt's next
 *     payment date. Not one of the four quantities: it is the anchor that turns a month count into
 *     a payoff month and back
 * @param firstMonth what is already true of month 1 when it is the current month (ADR-0043): its
 *     interest booked by a recorded payment, so month 1 is charged none, and what was already paid,
 *     so month 1 plans only the rest of the payment. Untouched for a pure calculation that reads no
 *     household data
 */
public record DebtTerms(
        Money balance,
        BigDecimal apr,
        Money payment,
        Horizon horizon,
        YearMonth firstPaymentMonth,
        FirstMonth firstMonth) {

    public DebtTerms {
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(firstPaymentMonth, "firstPaymentMonth");
        Objects.requireNonNull(firstMonth, "firstMonth");
        if (payment != null && !payment.currency().equals(balance.currency())) {
            throw new CurrencyMismatchException(balance.currency(), payment.currency());
        }
    }

    /** Terms whose first month nothing has happened in yet: the ordinary case. */
    public DebtTerms(
            Money balance,
            BigDecimal apr,
            Money payment,
            Horizon horizon,
            YearMonth firstPaymentMonth) {
        this(
                balance,
                apr,
                payment,
                horizon,
                firstPaymentMonth,
                FirstMonth.untouched(balance.currency()));
    }

    /** A debt with no plan and no entered horizon: the horizon is the solve target. */
    public static DebtTerms solvingHorizon(
            Money balance, BigDecimal apr, Money payment, YearMonth firstPaymentMonth) {
        return new DebtTerms(balance, apr, payment, null, firstPaymentMonth);
    }

    /** <em>"I want this gone by Dec 2027"</em>: the payment is the solve target. */
    public static DebtTerms solvingPayment(
            Money balance, BigDecimal apr, Horizon horizon, YearMonth firstPaymentMonth) {
        return new DebtTerms(balance, apr, null, horizon, firstPaymentMonth);
    }

    /**
     * The installment-loan case: a statement gives a balance, a payment and a term but never names
     * the rate, so the APR is the solve target.
     */
    public static DebtTerms solvingApr(
            Money balance, Money payment, Horizon horizon, YearMonth firstPaymentMonth) {
        return new DebtTerms(balance, null, payment, horizon, firstPaymentMonth);
    }

    public DebtTerms withApr(BigDecimal solvedApr) {
        return new DebtTerms(balance, solvedApr, payment, horizon, firstPaymentMonth, firstMonth);
    }

    public DebtTerms withPayment(Money solvedPayment) {
        return new DebtTerms(balance, apr, solvedPayment, horizon, firstPaymentMonth, firstMonth);
    }

    public DebtTerms withHorizon(Horizon solvedHorizon) {
        return new DebtTerms(balance, apr, payment, solvedHorizon, firstPaymentMonth, firstMonth);
    }

    /** The same terms, with what is already true of month 1 (ADR-0043). */
    public DebtTerms withFirstMonth(FirstMonth first) {
        return new DebtTerms(balance, apr, payment, horizon, firstPaymentMonth, first);
    }

    /** The horizon's second face, or null when there is no horizon to show. */
    public YearMonth payoffMonth() {
        return horizon == null ? null : horizon.payoffMonth(firstPaymentMonth);
    }

    /** All three solvable quantities entered: {@code 422 terms-over-determined}. */
    public boolean isOverDetermined() {
        return absentCount() == 0;
    }

    /** Two or more solvable quantities absent: nothing follows from one equation. */
    public boolean isUnderDetermined() {
        return absentCount() > 1;
    }

    /**
     * Whichever one slot of {@code apr}, {@code payment} and the horizon is not supplied.
     *
     * @throws IllegalStateException if the set is over- or under-determined. Both are the caller's
     *     to reject — at the edge they are {@code 422 terms-over-determined} and a validation
     *     failure respectively — and guessing which field to drop is the one thing the solver must
     *     never do: a number the household typed must not be quietly replaced by a computed one
     */
    public TermField solveTarget() {
        if (apr == null && payment != null && horizon != null) {
            return TermField.APR;
        }
        if (payment == null && apr != null && horizon != null) {
            return TermField.PAYMENT;
        }
        if (horizon == null && apr != null && payment != null) {
            return TermField.HORIZON;
        }
        throw new IllegalStateException(
                "Exactly one of apr, payment and the horizon must be absent, not "
                        + absentCount()
                        + " of them");
    }

    private int absentCount() {
        int absent = 0;
        if (apr == null) {
            absent++;
        }
        if (payment == null) {
            absent++;
        }
        if (horizon == null) {
            absent++;
        }
        return absent;
    }

    /**
     * Ids only, amounts never (docs/architecture/security-model.md). A balance is what somebody
     * owes and an APR is a statement about their credit rating; neither belongs in a log line.
     */
    @Override
    public String toString() {
        return "DebtTerms[balance=<redacted> apr="
                + (apr == null ? "solved" : "<redacted>")
                + " payment="
                + (payment == null ? "solved" : "<redacted>")
                + " horizon="
                + (horizon == null ? "solved" : "entered")
                + "]";
    }
}
