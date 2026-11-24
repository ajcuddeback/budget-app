package com.budgetowl.debt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Currency;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * The terms solver as a unit: no Spring, no database, no clock (docs/features/debt-plan.md, Testing
 * notes).
 *
 * <p>This is the part of the slice that will be wrong silently, so most of the testing is here. The
 * fixtures are computed from the arithmetic rather than copied from the designs, whose figures are
 * an invented persona and do not reconcile (docs/features/debt-plan.md, <i>Where the designs and
 * the docs disagree</i>).
 */
class DebtTermsSolverTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final YearMonth DECEMBER = YearMonth.of(2026, 12);

    private final DebtTermsSolver solver = new DebtTermsSolver();

    // ------------------------------------------------------------------ the recurrence

    @Test
    void clearsAtTheFirstMonthTheBalanceReachesZero() {
        Projection projection =
                solver.project(money("6180.00"), apr("22.900"), money("445.00"), DECEMBER);

        assertThat(projection).isInstanceOf(Projection.Cleared.class);
        Projection.Cleared cleared = (Projection.Cleared) projection;
        assertThat(cleared.monthsLeft()).isEqualTo(17);
        assertThat(cleared.payoffMonth()).isEqualTo(YearMonth.of(2028, 4));
        assertThat(cleared.totalInterest()).isEqualTo(money("1069.37"));
        assertThat(cleared.months().getLast().clearsTheDebt()).isTrue();
    }

    @Test
    void interestIsTheNominalMonthlyRateAndNotTheEffectiveOne() {
        // 6180 × 22.9 ÷ 100 ÷ 12 = 117.935. The effective rate, (1 + 0.229)^(1/12) − 1, would give
        // about 107.05 — not what a card issuer quotes and not what the product promises.
        assertThat(solver.interestFor(money("6180.00"), apr("22.900")))
                .isEqualTo(money("117.9350"));
    }

    @Test
    void interestIsRoundedHalfEvenAtScaleFour() {
        // 190.845 × 12 ÷ 1200 = 1.908450 exactly — a tie at the fourth decimal, which HALF_EVEN
        // takes down to the even digit and HALF_UP would take up to 1.9085. The one assertion in
        // this file that can tell the two rounding modes apart.
        assertThat(solver.interestFor(money("190.8450"), apr("12.000"))).isEqualTo(money("1.9084"));
    }

    @Test
    void dividesByTwelveHundredInOneStepRatherThanRoundingTwice() {
        // The formula is one expression with one rounding. Rounding at ÷100 and again at ÷12 is a
        // different number: 6168.6933 at 18.992% is 97.6299 one way and 97.6298 the other. A tenth
        // of a penny, every month, in a chain of balances that has to reconcile exactly.
        Money balance = money("6168.6933");
        BigDecimal roundedTwice =
                balance.amount()
                        .multiply(apr("18.992"))
                        .divide(new BigDecimal("100"), Money.SCALE, RoundingMode.HALF_EVEN)
                        .divide(new BigDecimal("12"), Money.SCALE, RoundingMode.HALF_EVEN);

        assertThat(solver.interestFor(balance, apr("18.992"))).isEqualTo(money("97.6299"));
        assertThat(roundedTwice).isEqualByComparingTo(new BigDecimal("97.6298"));
    }

    @Test
    void aPaymentThatDoesNotCoverTheFirstMonthsInterestNeverClears() {
        // The normal case for a card minimum read off last month's statement: 118.00 a month
        // against 118.45 of interest. An answer, not an exception.
        Projection projection =
                solver.project(money("6205.60"), apr("22.900"), money("118.00"), DECEMBER);

        assertThat(projection).isEqualTo(new Projection.None(Projection.Reason.NEVER_CLEARS));
    }

    @Test
    void zeroAprClearsInTheCeilingOfTheBalanceOverThePaymentWithNoInterest() {
        Projection.Cleared cleared =
                (Projection.Cleared)
                        solver.project(money("1000.00"), apr("0.000"), money("300.00"), DECEMBER);

        assertThat(cleared.monthsLeft()).isEqualTo(4);
        assertThat(cleared.totalInterest()).isEqualTo(Money.zero(GBP));
    }

    @Test
    void interestPlusPrincipalEqualsTheAmountOnEveryRow() {
        Projection.Cleared cleared = longProjection();

        assertThat(cleared.monthsLeft()).isGreaterThan(100);
        for (ProjectionMonth row : cleared.months()) {
            assertThat(row.interest().plus(row.principal()))
                    .as("month %d", row.monthNumber())
                    .isEqualTo(row.amount());
        }
    }

    @Test
    void theChainOfBalancesEqualsTheAnchorMinusTheSumOfThePrincipals() {
        Projection.Cleared cleared = longProjection();

        Money principals = Money.zero(GBP);
        Money previousClose = null;
        for (ProjectionMonth row : cleared.months()) {
            if (previousClose != null) {
                assertThat(row.balance())
                        .as(
                                "month %d opens where month %d closed",
                                row.monthNumber(), row.monthNumber() - 1)
                        .isEqualTo(previousClose);
            }
            principals = principals.plus(row.principal());
            previousClose = row.balanceAfter();
        }

        assertThat(previousClose).isEqualTo(money("10000.00").minus(principals));
    }

    @Property(tries = 200)
    void theIterationAndTheLedgerAgree(
            @ForAll("balances") BigDecimal balance,
            @ForAll("rates") BigDecimal rate,
            @ForAll @IntRange(min = 2, max = 180) int fraction) {
        Money owed = Money.of(balance, GBP);
        Money payment =
                Money.of(
                        balance.divide(BigDecimal.valueOf(fraction), 2, RoundingMode.CEILING), GBP);
        Projection projection = solver.project(owed, rate, payment, DECEMBER);
        Assume.that(projection.clears());

        int rowsInALedger = rowsALoggedHistoryWouldProduce(balance, rate, payment.amount());

        assertThat(((Projection.Cleared) projection).monthsLeft()).isEqualTo(rowsInALedger);
    }

    // ----------------------------------------------------------------- solving the horizon

    @Test
    void solvesTheHorizonFromAPaymentAndARate() {
        TermsSolution solution =
                solver.solve(
                        DebtTerms.solvingHorizon(
                                money("6180.00"), apr("22.900"), money("445.00"), DECEMBER));

        TermsSolution.Solved solved = (TermsSolution.Solved) solution;
        assertThat(solved.solvedField()).isEqualTo(TermField.HORIZON);
        assertThat(solved.terms().horizon()).isEqualTo(Horizon.ofMonths(17));
        assertThat(solved.terms().payoffMonth()).isEqualTo(YearMonth.of(2028, 4));
        assertThat(solved.bisectionSteps()).as("a horizon is one run, not a search").isZero();
    }

    @Test
    void acceptsTermsWhosePaymentNeverClearsAndLeavesTheHorizonAbsent() {
        TermsSolution solution =
                solver.solve(
                        DebtTerms.solvingHorizon(
                                money("6205.60"), apr("22.900"), money("118.00"), DECEMBER));

        TermsSolution.Solved solved = (TermsSolution.Solved) solution;
        assertThat(solved.terms().horizon()).as("there is no horizon to report").isNull();
        assertThat(solved.terms().payoffMonth()).isNull();
        assertThat(solved.projection())
                .isEqualTo(new Projection.None(Projection.Reason.NEVER_CLEARS));
    }

    @Test
    void theHorizonsTwoFacesAreTheSameQuantity() {
        Horizon seventeenMonths = Horizon.ofMonths(17);

        assertThat(seventeenMonths.payoffMonth(DECEMBER)).isEqualTo(YearMonth.of(2028, 4));
        assertThat(Horizon.endingIn(YearMonth.of(2028, 4), DECEMBER)).isEqualTo(seventeenMonths);
        assertThat(Horizon.endingIn(DECEMBER, DECEMBER))
                .as("the first payment month is a one-month horizon, not a zero-month one")
                .isEqualTo(Horizon.ofMonths(1));
    }

    @Test
    void refusesAPayoffMonthBeforeTheFirstPayment() {
        assertThatThrownBy(() -> Horizon.endingIn(YearMonth.of(2026, 11), DECEMBER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------- solving the payment

    @Test
    void solvesThePaymentFromARateAndAHorizon() {
        TermsSolution.Solved solved = solvePaymentFor(31);

        assertThat(solved.solvedField()).isEqualTo(TermField.PAYMENT);
        assertThat(solved.terms().payment()).isEqualTo(money("265.95"));
        assertThat(((Projection.Cleared) solved.projection()).monthsLeft()).isEqualTo(31);
    }

    @Test
    void aSolvedPaymentIsTheSmallestOneThatReachesTheHorizon() {
        Money solved = solvePaymentFor(31).terms().payment();

        Money aPennyLess = solved.minus(money("0.01"));
        Projection.Cleared slower =
                (Projection.Cleared)
                        solver.project(money("6180.00"), apr("22.900"), aPennyLess, DECEMBER);
        assertThat(slower.monthsLeft())
                .as("one penny below the answer must miss the horizon, which is the tolerance")
                .isGreaterThan(31);
    }

    @Test
    void anEnteredPaymentRoundTripsToTheSmallestPaymentForTheSameNumberOfMonths() {
        // The feature doc asks for "the same payment back". What comes back is the *smallest*
        // payment that clears in those months, which is the entered one only when the household
        // happened to enter the minimum for its own horizon — 445.00 a month clears this balance in
        // 17 months with room to spare, and so does 408.95. What is required of the solver, and
        // asserted here, is that the round trip lands on the same horizon and never asks for more
        // than the household already pays. See the report accompanying this change.
        Money entered = money("445.00");
        Horizon horizon =
                ((Projection.Cleared)
                                solver.project(money("6180.00"), apr("22.900"), entered, DECEMBER))
                        .horizon();

        TermsSolution.Solved solved =
                (TermsSolution.Solved)
                        solver.solve(
                                DebtTerms.solvingPayment(
                                        money("6180.00"), apr("22.900"), horizon, DECEMBER));

        assertThat(solved.terms().payment()).isLessThanOrEqualTo(entered);
        assertThat(((Projection.Cleared) solved.projection()).horizon()).isEqualTo(horizon);
    }

    @Property(tries = 100)
    void aSolvedPaymentIsAFixedPointOfTheRoundTrip(
            @ForAll("balances") BigDecimal balance,
            @ForAll("rates") BigDecimal rate,
            @ForAll @IntRange(min = 2, max = 240) int months) {
        Money owed = Money.of(balance, GBP);
        TermsSolution first =
                solver.solve(
                        DebtTerms.solvingPayment(owed, rate, Horizon.ofMonths(months), DECEMBER));
        Assume.that(first instanceof TermsSolution.Solved);
        Money payment = ((TermsSolution.Solved) first).terms().payment();

        Horizon reached =
                ((Projection.Cleared) solver.project(owed, rate, payment, DECEMBER)).horizon();
        TermsSolution again = solver.solve(DebtTerms.solvingPayment(owed, rate, reached, DECEMBER));

        assertThat(((TermsSolution.Solved) again).terms().payment())
                .as(
                        "a solver that is not self-consistent produces a plan that changes on every"
                                + " read")
                .isEqualTo(payment);
    }

    // --------------------------------------------------------------------- solving the apr

    @Test
    void solvesTheAprFromAPaymentAndATerm() {
        // The installment-loan case: a statement gives 20,000 over 60 months at 404.58 and never
        // names the rate. 7.9% is the rate that payment was computed from.
        TermsSolution.Solved solved = solveAprFor(money("20000.00"), money("404.58"), 60);

        assertThat(solved.solvedField()).isEqualTo(TermField.APR);
        assertThat(solved.terms().apr()).isEqualByComparingTo(apr("7.900"));
    }

    @Test
    void aSolvedAprIsTheHighestOneThatReachesTheHorizonToATenthOfABasisPoint() {
        BigDecimal solved = solveAprFor(money("20000.00"), money("404.58"), 60).terms().apr();

        assertThat(solved.scale()).isLessThanOrEqualTo(3);
        Projection.Cleared atTheAnswer =
                (Projection.Cleared)
                        solver.project(money("20000.00"), solved, money("404.58"), DECEMBER);
        assertThat(atTheAnswer.monthsLeft()).isLessThanOrEqualTo(60);
        Projection oneStepHigher =
                solver.project(
                        money("20000.00"),
                        solved.add(DebtTermsSolver.APR_TOLERANCE),
                        money("404.58"),
                        DECEMBER);
        assertThat(monthsOrNever(oneStepHigher))
                .as("0.001 of a percentage point higher must miss the horizon")
                .isGreaterThan(60);
    }

    @Property(tries = 60)
    void aSolvedAprIsAFixedPointOfTheRoundTrip(
            @ForAll("balances") BigDecimal balance,
            @ForAll @IntRange(min = 6, max = 240) int months,
            @ForAll @IntRange(min = 7, max = 120) int fraction) {
        Money owed = Money.of(balance, GBP);
        Money payment =
                Money.of(
                        balance.divide(BigDecimal.valueOf(fraction), 2, RoundingMode.CEILING), GBP);
        TermsSolution first =
                solver.solve(
                        DebtTerms.solvingApr(owed, payment, Horizon.ofMonths(months), DECEMBER));
        Assume.that(first instanceof TermsSolution.Solved);
        BigDecimal rate = ((TermsSolution.Solved) first).terms().apr();

        Horizon reached =
                ((Projection.Cleared) solver.project(owed, rate, payment, DECEMBER)).horizon();
        TermsSolution again = solver.solve(DebtTerms.solvingApr(owed, payment, reached, DECEMBER));

        assertThat(((TermsSolution.Solved) again).terms().apr()).isEqualByComparingTo(rate);
    }

    @Test
    void aPaymentThatCannotReachTheHorizonAtAnyRateIsUnsolvable() {
        TermsSolution solution =
                solver.solve(
                        DebtTerms.solvingApr(
                                money("20000.00"),
                                money("100.00"),
                                Horizon.ofMonths(12),
                                DECEMBER));

        assertThat(solution)
                .isInstanceOf(TermsSolution.Unsolvable.class)
                .extracting(it -> ((TermsSolution.Unsolvable) it).field())
                .isEqualTo(TermField.HORIZON);
    }

    @Test
    void aPaymentThatClearsBeforeTheHorizonAtEveryRateIsUnsolvable() {
        TermsSolution solution =
                solver.solve(
                        DebtTerms.solvingApr(
                                money("1000.00"),
                                money("2000.00"),
                                Horizon.ofMonths(12),
                                DECEMBER));

        assertThat(((TermsSolution.Unsolvable) solution).field()).isEqualTo(TermField.PAYMENT);
    }

    // ------------------------------------------------------------- what the solver refuses

    @Test
    void refusesAnOverDeterminedTermSet() {
        DebtTerms allThree =
                new DebtTerms(
                        money("6180.00"),
                        apr("22.900"),
                        money("445.00"),
                        Horizon.ofMonths(17),
                        DECEMBER);

        assertThat(allThree.isOverDetermined()).isTrue();
        assertThatThrownBy(() -> solver.solve(allThree))
                .as("a number the household typed must never be quietly replaced by a computed one")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAnUnderDeterminedTermSet() {
        DebtTerms onlyARate = new DebtTerms(money("6180.00"), apr("22.900"), null, null, DECEMBER);

        assertThat(onlyARate.isUnderDetermined()).isTrue();
        assertThatThrownBy(() -> solver.solve(onlyARate)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNegativePaymentIsUnsolvable() {
        TermsSolution solution =
                solver.solve(
                        DebtTerms.solvingApr(
                                money("6180.00"),
                                money("-445.00"),
                                Horizon.ofMonths(17),
                                DECEMBER));

        assertThat(((TermsSolution.Unsolvable) solution).field()).isEqualTo(TermField.PAYMENT);
    }

    @Test
    void aNegativeRateIsUnsolvable() {
        TermsSolution solution =
                solver.solve(
                        DebtTerms.solvingHorizon(
                                money("6180.00"), apr("-1.000"), money("445.00"), DECEMBER));

        assertThat(((TermsSolution.Unsolvable) solution).field()).isEqualTo(TermField.APR);
    }

    @Test
    void aBalanceThatIsNotPositiveIsUnsolvable() {
        TermsSolution solution =
                solver.solve(
                        DebtTerms.solvingHorizon(
                                Money.zero(GBP), apr("22.900"), money("445.00"), DECEMBER));

        assertThat(((TermsSolution.Unsolvable) solution).field()).isEqualTo(TermField.BALANCE);
    }

    @Test
    void aHorizonBeyondTheMonthCapIsUnsolvable() {
        TermsSolution solution =
                solver.solve(
                        DebtTerms.solvingPayment(
                                money("6180.00"),
                                apr("22.900"),
                                Horizon.ofMonths(DebtTermsSolver.MAX_MONTHS + 1),
                                DECEMBER));

        assertThat(((TermsSolution.Unsolvable) solution).field()).isEqualTo(TermField.HORIZON);
    }

    // -------------------------------------------------------------------------- the caps

    @Test
    void theCapsAreSixHundredMonthsAndSixtyFourSteps() {
        // Asserted rather than assumed: these two numbers are the threat model's bound on one
        // request's cost (docs/features/debt-plan.md, Security considerations), so changing either
        // is a decision somebody has to take deliberately.
        assertThat(DebtTermsSolver.MAX_MONTHS).isEqualTo(600);
        assertThat(DebtTermsSolver.MAX_BISECTION_STEPS).isEqualTo(64);
    }

    @Test
    void theProjectionStopsAtTheMonthCapRatherThanRunningOn() {
        // A trillion-pound balance paid off at a pound a month is about 10^12 months. With the cap
        // this answers immediately; without it the call never returns, which is why the assertion
        // is preemptively timed — a control that fails open must fail a test rather than merely
        // get slower.
        TermsSolution solution =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(5),
                        () ->
                                solver.solve(
                                        DebtTerms.solvingHorizon(
                                                money("1000000000000.00"),
                                                apr("0.000"),
                                                money("1.00"),
                                                DECEMBER)));

        assertThat(((TermsSolution.Unsolvable) solution).field()).isEqualTo(TermField.HORIZON);
    }

    @Test
    void thePaymentSearchStopsAtTheStepCapRatherThanRunningOn() {
        // A balance this size needs about eighty halvings to pin a payment to the penny, so the
        // search hits the step cap. Far beyond NUMERIC(19,4) on purpose: the control has to hold
        // for numbers the column could never hold, because the solve endpoint is arithmetic over a
        // request body and reads nothing (docs/features/debt-plan.md).
        TermsSolution solution =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(5),
                        () ->
                                solver.solve(
                                        DebtTerms.solvingPayment(
                                                money("1000000000000000000000000.00"),
                                                apr("0.000"),
                                                Horizon.ofMonths(600),
                                                DECEMBER)));

        TermsSolution.Unsolvable unsolvable = (TermsSolution.Unsolvable) solution;
        assertThat(unsolvable.field()).isEqualTo(TermField.PAYMENT);
        assertThat(unsolvable.detail())
                .contains(String.valueOf(DebtTermsSolver.MAX_BISECTION_STEPS));
    }

    @Property(tries = 100)
    void noSolveEverExceedsTheBisectionCap(
            @ForAll("balances") BigDecimal balance,
            @ForAll("rates") BigDecimal rate,
            @ForAll @IntRange(min = 1, max = 600) int months) {
        TermsSolution solution =
                solver.solve(
                        DebtTerms.solvingPayment(
                                Money.of(balance, GBP), rate, Horizon.ofMonths(months), DECEMBER));
        Assume.that(solution instanceof TermsSolution.Solved);

        assertThat(((TermsSolution.Solved) solution).bisectionSteps())
                .isLessThanOrEqualTo(DebtTermsSolver.MAX_BISECTION_STEPS);
    }

    // -------------------------------------------------------------------------- fixtures

    @Provide
    Arbitrary<BigDecimal> balances() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("50.00"), new BigDecimal("500000.00"))
                .ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("39.999"))
                .ofScale(3);
    }

    private Projection.Cleared longProjection() {
        // 107 months, which is the "over a hundred months" the testing notes ask for.
        return (Projection.Cleared)
                solver.project(money("10000.00"), apr("15.000"), money("170.00"), DECEMBER);
    }

    private TermsSolution.Solved solvePaymentFor(int months) {
        return (TermsSolution.Solved)
                solver.solve(
                        DebtTerms.solvingPayment(
                                money("6180.00"),
                                apr("22.900"),
                                Horizon.ofMonths(months),
                                DECEMBER));
    }

    private TermsSolution.Solved solveAprFor(Money balance, Money payment, int months) {
        return (TermsSolution.Solved)
                solver.solve(
                        DebtTerms.solvingApr(balance, payment, Horizon.ofMonths(months), DECEMBER));
    }

    /**
     * The months a recorded payment history would have rows for, computed the way a logged
     * payment's split is: interest on the balance, principal the remainder, balance after the
     * difference. Deliberately a second implementation, in plain {@link BigDecimal} and without the
     * solver's never-clears short cut, because a test that calls the code under test to work out
     * what to expect proves nothing.
     */
    private int rowsALoggedHistoryWouldProduce(
            BigDecimal balance, BigDecimal rate, BigDecimal payment) {
        BigDecimal owed = balance.setScale(Money.SCALE, RoundingMode.UNNECESSARY);
        int rows = 0;
        while (owed.signum() > 0 && rows <= DebtTermsSolver.MAX_MONTHS) {
            BigDecimal interest =
                    owed.multiply(rate)
                            .divide(new BigDecimal("1200"), Money.SCALE, RoundingMode.HALF_EVEN);
            owed = owed.subtract(payment.subtract(interest));
            rows++;
        }
        return rows;
    }

    private int monthsOrNever(Projection projection) {
        return projection instanceof Projection.Cleared cleared
                ? cleared.monthsLeft()
                : Integer.MAX_VALUE;
    }

    private static Money money(String amount) {
        return Money.of(amount, "GBP");
    }

    private static BigDecimal apr(String percentage) {
        return new BigDecimal(percentage);
    }
}
