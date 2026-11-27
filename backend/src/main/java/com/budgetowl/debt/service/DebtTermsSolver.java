package com.budgetowl.debt.service;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The terms solver: one equation, four quantities, three degrees of freedom
 * (docs/features/debt-plan.md).
 *
 * <p><b>The arithmetic.</b> Monthly compounding on the balance, interest first, payment at the end
 * of the period:
 *
 * <pre>
 *   interest(m) = round(balance(m-1) × apr ÷ 100 ÷ 12, minor unit, HALF_EVEN)
 *   balance(m)  = balance(m-1) + interest(m) − payment
 * </pre>
 *
 * <p>The <em>minor unit</em> is the currency's own — the cent, the whole yen — and why it is that
 * rather than the storage scale is on {@link #interestFor}.
 *
 * <p>The debt clears in the first month {@code m} where {@code balance(m) ≤ 0}. {@code apr ÷ 100 ÷
 * 12} is the <b>nominal</b> monthly rate, not the effective {@code (1 + apr)^(1/12) − 1} — that is
 * what card issuers quote and what the product promises. The division is by {@code 1200} in one
 * step with one rounding, because rounding twice (once per divisor) is a different number.
 *
 * <p><b>The projection is the iteration, not the closed form.</b> The closed-form annuity formula
 * disagrees with a row-by-row ledger by a few cents, and a few cents compounded eventually
 * disagrees by a whole month — so the payment table on screen and the payoff date above it would
 * contradict each other. The closed form appears here exactly once, as a <em>seed</em> for the
 * payment search, where being slightly wrong only costs a step.
 *
 * <p><b>Solving a non-horizon field is a bounded search.</b> {@code payment} and {@code apr} are
 * each monotonic in the number of months to clear, so each is found by bisection over the
 * iteration: {@code payment} to the currency's scale, {@code apr} to 0.001 percentage points.
 *
 * <p><b>The caps are controls, not tuning.</b> {@link #MAX_MONTHS} months of iteration and {@link
 * #MAX_BISECTION_STEPS} probes bound one call at roughly 38,400 arithmetic steps — this is the
 * first calculation in the app whose cost depends on the numbers in the request. Hitting either cap
 * is {@link TermsSolution.Unsolvable}, never a longer loop.
 *
 * <p>Stateless, thread-safe, and free of any clock, repository or household: the first period's
 * month arrives in {@link DebtTerms#firstPaymentMonth()}, which is what makes this unit-testable
 * with no context and no database.
 */
public final class DebtTermsSolver {

    /**
     * 50 years of monthly periods. A threat-model cap: a projection that has not cleared by here is
     * reported as unsolvable rather than iterated further.
     */
    public static final int MAX_MONTHS = 600;

    /** Probes of the recurrence per solve. The other half of the cost bound. */
    public static final int MAX_BISECTION_STEPS = 64;

    /** The {@code apr} search's tolerance, in percentage points. */
    public static final BigDecimal APR_TOLERANCE = new BigDecimal("0.001");

    /** Scale 3, matching {@code apr NUMERIC(6,3)} and {@link #APR_TOLERANCE}. */
    private static final int APR_SCALE = 3;

    /** {@code apr < 1000}, as the {@code CHECK} constraint has it. */
    private static final BigDecimal APR_CEILING = new BigDecimal("1000");

    /** {@code ÷ 100 ÷ 12} in one step, so there is one rounding and not two. */
    private static final BigDecimal MONTHLY_RATE_DIVISOR = new BigDecimal("1200");

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /**
     * One month's interest: {@code round(balance × apr ÷ 100 ÷ 12, minor units, HALF_EVEN)}.
     *
     * <p>The only rounding step in the whole projection. Negative for an overpaid balance, which is
     * arithmetic rather than a case: the recurrence has already stopped by then.
     *
     * <p><b>Rounded to the currency's minor unit</b> (cents for USD, whole yen for JPY), as a
     * lender's statement is. Interest used to be kept to four places, and the interest and
     * principal halves of a payment then could not both be shown on screen: interest of $117.935
     * displays as $117.94, the $377.065 of principal as $377.07, and the split of a $495.00 payment
     * read $495.01. Kept to the cent, a payment in cents splits into two figures that add up
     * exactly, and every balance stays on the cent grid. A currency with no fixed minor unit
     * ({@code getDefaultFractionDigits() < 0}) keeps the storage scale.
     */
    public Money interestFor(Money balance, BigDecimal apr) {
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(apr, "apr");
        int minorUnits = balance.currency().getDefaultFractionDigits();
        int scale = minorUnits < 0 ? Money.SCALE : Math.min(minorUnits, Money.SCALE);
        BigDecimal interest =
                balance.amount()
                        .multiply(apr)
                        .divide(MONTHLY_RATE_DIVISOR, scale, RoundingMode.HALF_EVEN);
        return Money.of(interest, balance.currency());
    }

    /**
     * Iterates the recurrence until the balance reaches zero, or says why it did not.
     *
     * <p>This is the definition of a projection. Everything else in this class is a search over it.
     *
     * @param firstPaymentMonth the month month 1 falls in
     * @throws IllegalArgumentException if the balance is not positive — there is nothing to project
     *     for a settled or overpaid debt — or if the APR is negative
     */
    public Projection project(
            Money balance, BigDecimal apr, Money payment, YearMonth firstPaymentMonth) {
        return project(balance, apr, payment, firstPaymentMonth, false);
    }

    /**
     * The same, with month 1's interest already booked or not.
     *
     * <p><b>Interest is charged once per debt per calendar month</b> (ADR-0043). When a payment
     * recorded this month already carried this month's interest, the balance includes it, and a
     * projection whose month 1 is this month charges month 1 none — otherwise every payment logged
     * before the plan's own would make the plan count that month's interest twice.
     *
     * @param firstMonthInterestBooked month 1's interest is already in {@code balance}
     */
    public Projection project(
            Money balance,
            BigDecimal apr,
            Money payment,
            YearMonth firstPaymentMonth,
            boolean firstMonthInterestBooked) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(firstPaymentMonth, "firstPaymentMonth");
        if (!balance.isPositive()) {
            throw new IllegalArgumentException("A projection needs a positive balance");
        }
        if (apr.signum() < 0) {
            throw new IllegalArgumentException("A projection needs a non-negative APR");
        }

        // payment ≤ the interest it first has to cover: the balance never falls and there is no
        // horizon to solve for. The normal case for a card minimum read off last month's
        // statement, so it is an answer and not an exception. With month 1's interest booked, the
        // first interest the payment meets is month 2's, on what month 1 leaves.
        Money tested = firstMonthInterestBooked ? balance.minus(payment) : balance;
        if (tested.isPositive() && payment.compareTo(interestFor(tested, apr)) <= 0) {
            return new Projection.None(Projection.Reason.NEVER_CLEARS);
        }

        List<ProjectionMonth> months = new ArrayList<>();
        Money owed = balance;
        Money totalInterest = Money.zero(balance.currency());
        for (int monthNumber = 1; monthNumber <= MAX_MONTHS; monthNumber++) {
            YearMonth month = firstPaymentMonth.plusMonths(monthNumber - 1L);
            Money interest = interestIn(monthNumber, owed, apr, firstMonthInterestBooked);
            ProjectionMonth row = ProjectionMonth.of(monthNumber, month, owed, payment, interest);
            months.add(row);
            totalInterest = totalInterest.plus(interest);
            if (row.clearsTheDebt()) {
                return new Projection.Cleared(months, month, totalInterest);
            }
            owed = row.balanceAfter();
        }
        return new Projection.None(Projection.Reason.EXCEEDS_MONTH_CAP);
    }

    /** One month's interest, and none in month 1 when a recorded payment already carried it. */
    private Money interestIn(
            int monthNumber, Money owed, BigDecimal apr, boolean firstMonthInterestBooked) {
        return monthNumber == 1 && firstMonthInterestBooked
                ? Money.zero(owed.currency())
                : interestFor(owed, apr);
    }

    /**
     * Fills in whichever one of {@code apr}, {@code payment} and the horizon is absent.
     *
     * <p><b>What this expects.</b> Exactly one of the three absent. All three present is
     * over-determined and two or more absent is under-determined; both are the caller's to reject —
     * {@code 422 terms-over-determined} and a validation failure at the edge — and both throw here
     * rather than being guessed at, because silently dropping a number the household typed is the
     * one behaviour this feature's doc rules out in as many words.
     *
     * @throws IllegalStateException if the term set is over- or under-determined
     */
    public TermsSolution solve(DebtTerms terms) {
        Objects.requireNonNull(terms, "terms");
        TermField target = terms.solveTarget();
        if (!terms.balance().isPositive()) {
            return new TermsSolution.Unsolvable(
                    TermField.BALANCE, "a projection needs a positive balance");
        }
        if (terms.payment() != null && terms.payment().isNegative()) {
            return new TermsSolution.Unsolvable(TermField.PAYMENT, "a payment cannot be negative");
        }
        if (terms.apr() != null && terms.apr().signum() < 0) {
            return new TermsSolution.Unsolvable(TermField.APR, "a rate cannot be negative");
        }
        if (terms.horizon() != null && terms.horizon().monthsLeft() > MAX_MONTHS) {
            return new TermsSolution.Unsolvable(
                    TermField.HORIZON, "a horizon beyond " + MAX_MONTHS + " months");
        }
        return switch (target) {
            case HORIZON -> solveHorizon(terms);
            case PAYMENT -> solvePayment(terms);
            case APR -> solveApr(terms);
            case BALANCE ->
                    throw new IllegalStateException("The balance is never solved: " + target);
        };
    }

    // ------------------------------------------------------------------ the horizon

    private TermsSolution solveHorizon(DebtTerms terms) {
        Projection projection =
                project(
                        terms.balance(),
                        terms.apr(),
                        terms.payment(),
                        terms.firstPaymentMonth(),
                        terms.firstMonthInterestBooked());
        return switch (projection) {
            case Projection.Cleared cleared ->
                    new TermsSolution.Solved(
                            terms.withHorizon(cleared.horizon()), TermField.HORIZON, cleared, 0);
            case Projection.None none ->
                    none.reason() == Projection.Reason.NEVER_CLEARS
                            // Accepted, stored, and reported with its reason: there is no horizon,
                            // and that is a fact about the terms rather than a fault in them.
                            ? new TermsSolution.Solved(terms, TermField.HORIZON, none, 0)
                            : new TermsSolution.Unsolvable(
                                    TermField.HORIZON,
                                    "does not clear within " + MAX_MONTHS + " months");
        };
    }

    // ------------------------------------------------------------------ the payment

    /**
     * The smallest payment, at the currency's scale, that clears the balance by the horizon.
     *
     * <p>Rounded <em>up</em> to that scale: a payment rounded down would miss the month the
     * household asked for, and you cannot pay a fraction of a penny. The consequence is that the
     * resulting projection sometimes clears a month early — the overshoot of rounding up — and the
     * projection is what the table shows, because the iteration is the definition.
     */
    private TermsSolution solvePayment(DebtTerms terms) {
        Money balance = terms.balance();
        BigDecimal apr = terms.apr();
        int target = terms.horizon().monthsLeft();
        boolean booked = terms.firstMonthInterestBooked();
        Currency currency = balance.currency();
        int scale = paymentScale(currency);
        BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);

        // The bracket, by construction rather than by search:
        //   lo — a payment at or below the first month's interest never clears, so it never
        //        reaches the horizon either. With that interest already booked there is none to
        //        cover, and zero is the payment that certainly never clears;
        //   hi — balance plus that interest clears in month 1, so it reaches any horizon.
        Money firstInterest = interestIn(1, balance, apr, booked);
        BigDecimal low = firstInterest.amount().setScale(scale, RoundingMode.FLOOR);
        BigDecimal high =
                balance.plus(firstInterest).amount().setScale(scale, RoundingMode.CEILING);

        int steps = 0;
        BigDecimal seed =
                closedFormPayment(balance, apr, target).setScale(scale, RoundingMode.CEILING);
        if (seed.compareTo(low) > 0 && seed.compareTo(high) < 0) {
            steps++;
            if (clearsWithin(balance, apr, Money.of(seed, currency), target, booked)) {
                high = seed;
            } else {
                low = seed;
            }
        }

        while (high.subtract(low).compareTo(unit) > 0) {
            if (steps >= MAX_BISECTION_STEPS) {
                return new TermsSolution.Unsolvable(
                        TermField.PAYMENT,
                        "no payment found within " + MAX_BISECTION_STEPS + " bisection steps");
            }
            // The bracket is at least two units wide and both ends sit on the grid, so the
            // midpoint, floored to the grid, is strictly inside it. The loop cannot stall.
            BigDecimal middle = low.add(high).divide(TWO, scale, RoundingMode.FLOOR);
            steps++;
            if (clearsWithin(balance, apr, Money.of(middle, currency), target, booked)) {
                high = middle;
            } else {
                low = middle;
            }
        }

        Money payment = Money.of(high, currency);
        DebtTerms solved = terms.withPayment(payment);
        return new TermsSolution.Solved(
                solved,
                TermField.PAYMENT,
                project(balance, apr, payment, terms.firstPaymentMonth(), booked),
                steps);
    }

    /**
     * The annuity payment, used only to seed the search.
     *
     * <p>{@code P = B·r / (1 − (1 + r)^−n)}, and {@code B ÷ n} at a zero rate. Computed at {@link
     * MathContext#DECIMAL128} because it is a guess: the bisection that follows is what decides, so
     * an error here costs at most one step and can never reach a stored figure.
     */
    private BigDecimal closedFormPayment(Money balance, BigDecimal apr, int months) {
        BigDecimal rate = apr.divide(MONTHLY_RATE_DIVISOR, MathContext.DECIMAL128);
        if (rate.signum() == 0) {
            return balance.amount()
                    .divide(BigDecimal.valueOf(months), Money.SCALE, RoundingMode.CEILING);
        }
        BigDecimal growth = BigDecimal.ONE.add(rate).pow(months, MathContext.DECIMAL128);
        BigDecimal discount =
                BigDecimal.ONE.subtract(BigDecimal.ONE.divide(growth, MathContext.DECIMAL128));
        return balance.amount().multiply(rate).divide(discount, MathContext.DECIMAL128);
    }

    // ---------------------------------------------------------------------- the apr

    /**
     * The highest rate, to 0.001 percentage points, at which this payment still clears this balance
     * by the horizon.
     *
     * <p>The installment-loan case: a statement gives a balance, a payment and a term but never
     * names the rate. There is no closed form to invert, so there is no seed either — bisection
     * over {@code [0, 1000)} reaches the tolerance in about twenty probes.
     *
     * <p><b>A solved APR is the boundary of an interval, not a measurement.</b> Many rates clear
     * the same balance in the same number of months; this returns the largest of them, so the
     * figure is an upper bound on the household's real rate and the UI says {@code Solved}. What is
     * exact is the tolerance: the next grid step up does not reach the horizon.
     */
    private TermsSolution solveApr(DebtTerms terms) {
        Money balance = terms.balance();
        Money payment = terms.payment();
        int target = terms.horizon().monthsLeft();
        boolean booked = terms.firstMonthInterestBooked();

        BigDecimal low = BigDecimal.ZERO.setScale(APR_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal high =
                APR_CEILING.subtract(APR_TOLERANCE).setScale(APR_SCALE, RoundingMode.UNNECESSARY);

        // The bracket, probed rather than assumed: a rate of zero is the fastest this payment can
        // ever clear the balance, and a rate just under the ceiling the slowest.
        int steps = 1;
        if (!clearsWithin(balance, low, payment, target, booked)) {
            return new TermsSolution.Unsolvable(
                    TermField.HORIZON,
                    "this payment cannot clear this balance by that month at any rate, including"
                            + " zero");
        }
        steps++;
        if (clearsWithin(balance, high, payment, target, booked)) {
            return new TermsSolution.Unsolvable(
                    TermField.PAYMENT,
                    "this payment clears this balance before that month at every rate under "
                            + APR_CEILING.toPlainString()
                            + "%, so no rate follows from it");
        }

        while (high.subtract(low).compareTo(APR_TOLERANCE) > 0) {
            if (steps >= MAX_BISECTION_STEPS) {
                return new TermsSolution.Unsolvable(
                        TermField.APR,
                        "no rate found within " + MAX_BISECTION_STEPS + " bisection steps");
            }
            BigDecimal middle = low.add(high).divide(TWO, APR_SCALE, RoundingMode.FLOOR);
            steps++;
            if (clearsWithin(balance, middle, payment, target, booked)) {
                low = middle;
            } else {
                high = middle;
            }
        }

        DebtTerms solved = terms.withApr(low);
        return new TermsSolution.Solved(
                solved,
                TermField.APR,
                project(balance, low, payment, terms.firstPaymentMonth(), booked),
                steps);
    }

    // ------------------------------------------------------------------- the search

    /**
     * Whether the recurrence reaches zero within {@code months} periods.
     *
     * <p>The same arithmetic {@link #project} runs, without building the rows — a probe is asked
     * one yes-or-no question up to sixty-four times, and the rows of the sixty-three rejected
     * answers are not wanted. Bounded twice over: by the horizon asked about, which is never more
     * than {@link #MAX_MONTHS}, and by the balance ceasing to fall.
     */
    private boolean clearsWithin(
            Money balance, BigDecimal apr, Money payment, int months, boolean firstMonthBooked) {
        Money owed = balance;
        for (int month = 1; month <= Math.min(months, MAX_MONTHS); month++) {
            Money after = owed.plus(interestIn(month, owed, apr, firstMonthBooked)).minus(payment);
            if (!after.isPositive()) {
                return true;
            }
            if (after.compareTo(owed) >= 0) {
                // The balance did not fall, so next month's interest is at least this month's and
                // it never will. No horizon reaches it.
                return false;
            }
            owed = after;
        }
        return false;
    }

    /**
     * The scale the payment is solved to: the currency's own, so pounds land on a penny and yen on
     * a yen. A currency with no minor unit defined (a pseudo-currency) falls back to {@link
     * Money#SCALE} rather than to zero, because rounding somebody's money to the nearest whole unit
     * on the strength of a missing lookup is worse than carrying four decimals.
     */
    private static int paymentScale(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        return digits < 0 ? Money.SCALE : digits;
    }
}
