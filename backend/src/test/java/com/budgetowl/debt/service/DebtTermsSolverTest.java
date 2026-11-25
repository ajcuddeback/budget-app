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

        // Month 1: 6180.00 × 22.9 ÷ 1200 = 117.935 → 117.94, closing 5852.94. Month 2: 5852.94 ×
        // 22.9 ÷ 1200 = 111.6936… → 111.69, closing 5519.63. … Month 16 closes at 126.94; month
        // 17 takes 2.42 of interest and closes at −315.64, the first close at or below zero. Every
        // interest figure is a cent, so the total is the payments less the fall in the balance:
        // 17 × 445.00 − (6180.00 − (−315.64)) = 7565.00 − 6495.64 = 1069.36.
        assertThat(projection).isInstanceOf(Projection.Cleared.class);
        Projection.Cleared cleared = (Projection.Cleared) projection;
        assertThat(cleared.monthsLeft()).isEqualTo(17);
        assertThat(cleared.payoffMonth()).isEqualTo(YearMonth.of(2028, 4));
        assertThat(cleared.totalInterest()).isEqualTo(money("1069.36"));
        assertThat(cleared.months().getLast().clearsTheDebt()).isTrue();
    }

    @Test
    void interestIsTheNominalMonthlyRateAndNotTheEffectiveOne() {
        // 6180 × 22.9 ÷ 100 ÷ 12 = 117.935, which is 117.94 to the penny. The effective rate,
        // (1 + 0.229)^(1/12) − 1, would give about 107.05 — not what a card issuer quotes and not
        // what the product promises.
        assertThat(solver.interestFor(money("6180.00"), apr("22.900"))).isEqualTo(money("117.94"));
    }

    @Test
    void interestIsRoundedHalfEvenToTheMinorUnit() {
        // Both are exact ties at the penny, so only the rounding mode decides them.
        //   2412.00 × 12.5 ÷ 1200 = 25.125   → 25.12, down to the even penny (HALF_UP: 25.13)
        //   6180.00 × 22.9 ÷ 1200 = 117.935  → 117.94, up to the even penny (HALF_DOWN: 117.93)
        // Together they tell HALF_EVEN apart from every other mode that rounds to the nearest.
        assertThat(solver.interestFor(money("2412.00"), apr("12.500"))).isEqualTo(money("25.12"));
        assertThat(solver.interestFor(money("6180.00"), apr("22.900"))).isEqualTo(money("117.94"));
    }

    @Test
    void interestOnAYenBalanceIsRoundedToTheWholeYen() {
        // JPY has no minor unit: 618000 × 22.9 ÷ 1200 = 11793.5, a tie at the yen, which HALF_EVEN
        // takes to the even yen, 11794. Rounded at scale 2 it would have been 11793.50.
        Currency yen = Currency.getInstance("JPY");

        Money interest = solver.interestFor(Money.of(new BigDecimal("618000"), yen), apr("22.900"));

        assertThat(interest).isEqualTo(Money.of(new BigDecimal("11794"), yen));
    }

    @Test
    void aCurrencyWithNoDefinedMinorUnitKeepsTheStorageScale() {
        // XAU (gold) reports getDefaultFractionDigits() == -1. Rounding it to a whole unit on the
        // strength of a missing lookup would be worse than carrying four places, so it keeps the
        // storage scale: 190.845 × 12 ÷ 1200 = 1.90845, a tie at the fourth place, → 1.9084.
        Currency gold = Currency.getInstance("XAU");

        Money interest =
                solver.interestFor(Money.of(new BigDecimal("190.8450"), gold), apr("12.000"));

        assertThat(gold.getDefaultFractionDigits()).isNegative();
        assertThat(interest).isEqualTo(Money.of(new BigDecimal("1.9084"), gold));
    }

    @Property(tries = 500)
    void aPaymentInPenniesSplitsIntoPenniesThatAddUpExactly(
            @ForAll("balances") BigDecimal balance,
            @ForAll("rates") BigDecimal rate,
            @ForAll("payments") BigDecimal paid) {
        // The property interest is rounded to the minor unit for: a payment in pennies against a
        // balance in pennies splits into an interest and a principal that are each whole pennies,
        // sum to the payment exactly, and leave a balance in whole pennies — so both halves can be
        // shown to the penny and still add up, and the next month starts on the grid too.
        Money owed = Money.of(balance, GBP);
        Money amount = Money.of(paid, GBP);

        ProjectionMonth row =
                ProjectionMonth.of(1, DECEMBER, owed, amount, solver.interestFor(owed, rate));

        assertThat(row.interest().plus(row.principal())).isEqualTo(amount);
        assertThat(pennies(row.interest())).as("interest %s", row.interest()).isTrue();
        assertThat(pennies(row.principal())).as("principal %s", row.principal()).isTrue();
        assertThat(pennies(row.balanceAfter())).as("balance %s", row.balanceAfter()).isTrue();
    }

    @Test
    void dividesByTwelveHundredInOneStepRatherThanRoundingTwice() {
        // The formula is one expression with one rounding. Rounding at ÷100 and again at ÷12 is a
        // different number, even on the penny grid: 6180.00 at 3.032% is
        //   once:  18737.76 ÷ 1200 = 15.6148           → 15.61
        //   twice: 18737.76 ÷ 100 = 187.3776 → 187.38; 187.38 ÷ 12 = 15.615 → 15.62 (half-even)
        // A penny, every month, in a chain of balances that has to reconcile exactly.
        Money balance = money("6180.00");
        BigDecimal roundedTwice =
                balance.amount()
                        .multiply(apr("3.032"))
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_EVEN)
                        .divide(new BigDecimal("12"), 2, RoundingMode.HALF_EVEN);

        assertThat(solver.interestFor(balance, apr("3.032"))).isEqualTo(money("15.61"));
        assertThat(roundedTwice).isEqualByComparingTo(new BigDecimal("15.62"));
    }

    @Test
    void aPaymentThatDoesNotCoverTheFirstMonthsInterestNeverClears() {
        // The normal case for a card minimum read off last month's statement: 118.00 a month
        // against 6205.60 × 22.9 ÷ 1200 = 118.4235… → 118.42 of interest. An answer, not an
        // exception.
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
        // 17 months with room to spare, and so does 429.11. What is required of the solver, and
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

    @Provide
    Arbitrary<BigDecimal> payments() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("50000.00"))
                .ofScale(2);
    }

    /** No digit below the penny: what a lender's statement, and the screen, can show exactly. */
    private static boolean pennies(Money money) {
        return money.amount().stripTrailingZeros().scale() <= GBP.getDefaultFractionDigits();
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
        int pennies = GBP.getDefaultFractionDigits();
        int rows = 0;
        while (owed.signum() > 0 && rows <= DebtTermsSolver.MAX_MONTHS) {
            BigDecimal interest =
                    owed.multiply(rate)
                            .divide(new BigDecimal("1200"), pennies, RoundingMode.HALF_EVEN);
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
