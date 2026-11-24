package com.budgetowl.debt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * The ladder as a unit: an order, an allocation and a roll-down, with no database and no context
 * (docs/features/debt-plan.md, Testing notes).
 *
 * <p>The three debts are the designs' shape — a card, a car loan and a student loan — but every
 * expected figure here is computed from the arithmetic. The canvas's own numbers do not reconcile
 * and are not fixtures.
 */
class PayoffLadderTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final YearMonth DECEMBER = YearMonth.of(2026, 12);
    private static final Money MONTHLY = money("905.00");

    private final PayoffLadder ladder = new PayoffLadder(new DebtTermsSolver());

    private final LadderDebt visa = debt(1, "Visa", "6180.00", "22.900", "185.00");
    private final LadderDebt car = debt(2, "Car loan", "10650.00", "6.490", "342.00");
    private final LadderDebt student = debt(3, "Student loan", "9650.00", "4.500", "118.00");

    private List<LadderDebt> threeDebts() {
        return List.of(car, student, visa);
    }

    // ------------------------------------------------------------------------- the order

    @Test
    void avalancheRanksByRateDescending() {
        assertThat(names(PayoffLadder.rank(PayoffStrategy.AVALANCHE, threeDebts())))
                .containsExactly("Visa", "Car loan", "Student loan");
    }

    @Test
    void snowballRanksByBalanceAscending() {
        assertThat(names(PayoffLadder.rank(PayoffStrategy.SNOWBALL, threeDebts())))
                .containsExactly("Visa", "Student loan", "Car loan");
    }

    @Test
    void avalancheBreaksARateTieOnTheSmallerBalance() {
        LadderDebt bigger = debt(4, "Bigger", "9000.00", "19.000", "100.00");
        LadderDebt smaller = debt(5, "Smaller", "3000.00", "19.000", "100.00");

        assertThat(names(PayoffLadder.rank(PayoffStrategy.AVALANCHE, List.of(bigger, smaller))))
                .containsExactly("Smaller", "Bigger");
    }

    @Test
    void snowballBreaksABalanceTieOnTheHigherRate() {
        LadderDebt cheaper = debt(4, "Cheaper", "4000.00", "7.000", "100.00");
        LadderDebt dearer = debt(5, "Dearer", "4000.00", "24.000", "100.00");

        assertThat(names(PayoffLadder.rank(PayoffStrategy.SNOWBALL, List.of(cheaper, dearer))))
                .containsExactly("Dearer", "Cheaper");
    }

    @Test
    void avalancheBreaksARateAndBalanceTieOnTheName() {
        LadderDebt zed = debt(4, "Zed", "4000.00", "19.000", "100.00");
        LadderDebt alpha = debt(5, "Alpha", "4000.00", "19.000", "100.00");

        assertThat(names(PayoffLadder.rank(PayoffStrategy.AVALANCHE, List.of(zed, alpha))))
                .containsExactly("Alpha", "Zed");
    }

    @Test
    void theOrderIsTotalEvenWhenTheRateTheBalanceAndTheNameAllTie() {
        LadderDebt first = debt(1, "Same", "4000.00", "19.000", "100.00");
        LadderDebt second = debt(2, "Same", "4000.00", "19.000", "100.00");

        List<LadderDebt> oneWay =
                PayoffLadder.rank(PayoffStrategy.AVALANCHE, List.of(first, second));
        List<LadderDebt> theOther =
                PayoffLadder.rank(PayoffStrategy.AVALANCHE, List.of(second, first));

        assertThat(oneWay)
                .as("the ladder must not reshuffle between two reads of the same data")
                .isEqualTo(theOther);
    }

    @Test
    void customOrderPlacesTheHouseholdsDebtsFirstAndTheRestByAvalanche() {
        LadderDebt placed =
                new LadderDebt(
                        id(9),
                        "Placed last by the household",
                        money("500.00"),
                        new BigDecimal("29.000"),
                        money("50.00"),
                        1);

        List<LadderDebt> ranked =
                PayoffLadder.rank(PayoffStrategy.CUSTOM, List.of(car, student, visa, placed));

        assertThat(names(ranked))
                .containsExactly(
                        "Placed last by the household", "Visa", "Car loan", "Student loan");
    }

    // -------------------------------------------------------------------- the allocation

    @Test
    void theSurplusGoesToRungOneOnly() {
        PayoffProjection.Ranked plan = avalanche();

        assertThat(plan.rungs().get(0).minimum()).isEqualTo(money("185.00"));
        assertThat(plan.rungs().get(0).extra()).isEqualTo(money("260.00"));
        assertThat(plan.rungs().get(0).monthlyAmount()).isEqualTo(money("445.00"));
        assertThat(plan.rungs().get(1).extra()).isEqualTo(Money.zero(GBP));
        assertThat(plan.rungs().get(2).extra()).isEqualTo(Money.zero(GBP));
    }

    @Test
    void everyRungBelowTheFirstPaysItsMinimumUntilTheRungAboveClears() {
        PayoffProjection.Ranked plan = avalanche();
        int firstWin = plan.rungs().get(0).months().size();

        for (int month = 1; month < firstWin; month++) {
            assertThat(plan.rungs().get(1).months().get(month - 1).amount())
                    .as("month %d", month)
                    .isEqualTo(money("342.00"));
            assertThat(plan.rungs().get(2).months().get(month - 1).amount())
                    .as("month %d", month)
                    .isEqualTo(money("118.00"));
        }
    }

    @Test
    void aClearedRungsMinimumAndTheSurplusRollDownToTheNextRung() {
        PayoffProjection.Ranked plan = avalanche();

        // The Visa clears in month 17, so from month 18 the car loan pays its own 342, the Visa's
        // 185 and the 260 of surplus.
        assertThat(plan.rungs().get(0).months()).hasSize(17);
        assertThat(plan.rungs().get(1).months().get(17).amount()).isEqualTo(money("787.00"));
        assertThat(plan.rungs().get(2).months().get(17).amount())
                .as("rung 3 still waits its turn")
                .isEqualTo(money("118.00"));
    }

    @Test
    void theMonthlyAmountIsSpentExactlyInEveryMonthButTheLast() {
        PayoffProjection.Ranked plan = avalanche();
        int lastMonth =
                plan.rungs().stream().mapToInt(rung -> rung.months().size()).max().orElseThrow();

        for (int month = 1; month < lastMonth; month++) {
            Money spent = Money.zero(GBP);
            for (PayoffProjection.Rung rung : plan.rungs()) {
                if (rung.months().size() >= month) {
                    spent = spent.plus(rung.months().get(month - 1).amount());
                }
            }
            assertThat(spent).as("month %d", month).isEqualTo(MONTHLY);
        }
    }

    @Test
    void noRungIsEverPaidMoreThanItOwes() {
        for (PayoffProjection.Rung rung : avalanche().rungs()) {
            for (ProjectionMonth row : rung.months()) {
                assertThat(row.balanceAfter().isNegative())
                        .as("%s, month %d", rung.debt().name(), row.monthNumber())
                        .isFalse();
            }
        }
    }

    @Test
    void everyRowKeepsInterestPlusPrincipalEqualToTheAmount() {
        for (PayoffProjection.Rung rung : avalanche().rungs()) {
            for (ProjectionMonth row : rung.months()) {
                assertThat(row.interest().plus(row.principal())).isEqualTo(row.amount());
            }
        }
    }

    // ------------------------------------------------------------------------ the output

    @Test
    void projectsAPayoffMonthForEveryRungAndADebtFreeMonthForThePlan() {
        PayoffProjection.Ranked plan = avalanche();

        assertThat(plan.rungs().get(0).payoffMonth()).isEqualTo(YearMonth.of(2028, 4));
        assertThat(plan.rungs().get(1).payoffMonth()).isEqualTo(YearMonth.of(2028, 11));
        assertThat(plan.rungs().get(2).payoffMonth()).isEqualTo(YearMonth.of(2029, 8));
        assertThat(plan.debtFreeMonth())
                .as("the latest of the per-debt payoff months")
                .isEqualTo(YearMonth.of(2029, 8));
        assertThat(plan.clearsEveryDebt()).isTrue();
    }

    @Test
    void theFirstWinIsRungOnesPayoffMonth() {
        PayoffProjection.Ranked plan = avalanche();

        assertThat(plan.firstWinMonth()).isEqualTo(plan.rungs().get(0).payoffMonth());
    }

    @Test
    void thePlansTotalInterestIsTheSumOfTheRungs() {
        PayoffProjection.Ranked plan = avalanche();

        Money summed = Money.zero(GBP);
        for (PayoffProjection.Rung rung : plan.rungs()) {
            summed = summed.plus(rung.totalInterest());
        }
        assertThat(plan.totalInterest()).isEqualTo(summed).isEqualTo(money("2861.4944"));
    }

    @Test
    void avalancheAndSnowballDifferOnlyInOrder() {
        PayoffProjection.Ranked snowball =
                (PayoffProjection.Ranked)
                        ladder.project(PayoffStrategy.SNOWBALL, MONTHLY, threeDebts(), DECEMBER);

        // The same debts, in the snowball order, told to the engine as a custom order: if the
        // strategy changed anything but the order, these two could not agree.
        List<LadderDebt> placedBySnowball = new ArrayList<>();
        for (int rung = 0; rung < snowball.rungs().size(); rung++) {
            LadderDebt original = snowball.rungs().get(rung).debt();
            placedBySnowball.add(
                    new LadderDebt(
                            original.id(),
                            original.name(),
                            original.balance(),
                            original.apr(),
                            original.minimumPayment(),
                            rung + 1));
        }
        PayoffProjection.Ranked asCustom =
                (PayoffProjection.Ranked)
                        ladder.project(PayoffStrategy.CUSTOM, MONTHLY, placedBySnowball, DECEMBER);

        assertThat(asCustom.totalInterest()).isEqualTo(snowball.totalInterest());
        assertThat(asCustom.debtFreeMonth()).isEqualTo(snowball.debtFreeMonth());
        assertThat(asCustom.rungs().stream().map(PayoffProjection.Rung::payoffMonth).toList())
                .isEqualTo(
                        snowball.rungs().stream().map(PayoffProjection.Rung::payoffMonth).toList());
    }

    @Test
    void avalancheCostsNoMoreInterestThanSnowballForTheSameMonthlyAmount() {
        PayoffProjection.Ranked snowball =
                (PayoffProjection.Ranked)
                        ladder.project(PayoffStrategy.SNOWBALL, MONTHLY, threeDebts(), DECEMBER);

        assertThat(avalanche().totalInterest()).isLessThanOrEqualTo(snowball.totalInterest());
        assertThat(snowball.totalInterest()).isEqualTo(money("2912.6501"));
    }

    @Property(tries = 150)
    void avalancheCostsNoMoreInterestThanSnowballForAnyLadder(
            @ForAll("ladders") List<LadderDebt> debts,
            @ForAll @IntRange(min = 0, max = 300) int surplusPercent) {
        Assume.that(distinctNames(debts));
        Money minimums = Money.zero(GBP);
        for (LadderDebt debt : debts) {
            minimums = minimums.plus(debt.minimumPayment());
        }
        Money monthly =
                minimums.plus(
                        Money.of(
                                minimums.amount()
                                        .multiply(BigDecimal.valueOf(surplusPercent))
                                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN),
                                GBP));

        PayoffProjection avalanche =
                ladder.project(PayoffStrategy.AVALANCHE, monthly, debts, DECEMBER);
        PayoffProjection snowball =
                ladder.project(PayoffStrategy.SNOWBALL, monthly, debts, DECEMBER);
        Assume.that(
                avalanche instanceof PayoffProjection.Ranked ranked && ranked.clearsEveryDebt());
        Assume.that(snowball instanceof PayoffProjection.Ranked ranked && ranked.clearsEveryDebt());

        assertThat(((PayoffProjection.Ranked) avalanche).totalInterest())
                .as("attacking the highest rate first is never the more expensive order")
                .isLessThanOrEqualTo(((PayoffProjection.Ranked) snowball).totalInterest());
    }

    // ------------------------------------------------------------------ the awkward cases

    @Test
    void aMonthlyAmountBelowTheSumOfTheMinimumsProducesNoProjection() {
        PayoffProjection plan =
                ladder.project(PayoffStrategy.AVALANCHE, money("644.99"), threeDebts(), DECEMBER);

        assertThat(plan)
                .as("a household that cannot meet its minimums is not invalid input")
                .isEqualTo(new PayoffProjection.None(Projection.Reason.BELOW_MINIMUMS));
    }

    @Test
    void aMonthlyAmountEqualToTheMinimumsIsAPlanWithNoSurplus() {
        PayoffProjection.Ranked plan =
                (PayoffProjection.Ranked)
                        ladder.project(
                                PayoffStrategy.AVALANCHE, money("645.00"), threeDebts(), DECEMBER);

        assertThat(plan.rungs().get(0).extra()).isEqualTo(Money.zero(GBP));
        assertThat(plan.rungs().get(0).monthlyAmount()).isEqualTo(money("185.00"));
    }

    @Test
    void aDebtThatNeverClearsLeavesThePlanWithoutADebtFreeMonthAndTheOthersWithDates() {
        LadderDebt underwater = debt(7, "Payday loan", "1000000.00", "29.990", "1.00");
        LadderDebt small = debt(8, "Store card", "500.00", "0.000", "100.00");

        PayoffProjection.Ranked plan =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(10),
                        () ->
                                (PayoffProjection.Ranked)
                                        ladder.project(
                                                PayoffStrategy.AVALANCHE,
                                                money("101.00"),
                                                List.of(underwater, small),
                                                DECEMBER));

        assertThat(plan.rungs().get(0).debt().name()).isEqualTo("Payday loan");
        assertThat(plan.rungs().get(0).payoffMonth()).isNull();
        assertThat(plan.rungs().get(0).clears()).isFalse();
        assertThat(plan.rungs().get(0).months())
                .as("the simulation stops at the cap rather than running on")
                .hasSize(PayoffLadder.MAX_MONTHS);
        assertThat(plan.rungs().get(1).payoffMonth())
                .as("a plan that refuses to render because one debt is underwater is useless")
                .isEqualTo(YearMonth.of(2027, 4));
        assertThat(plan.debtFreeMonth()).isNull();
        assertThat(plan.clearsEveryDebt()).isFalse();
    }

    @Test
    void refusesALadderWithNoDebts() {
        assertThatThrownBy(
                        () ->
                                ladder.project(
                                        PayoffStrategy.AVALANCHE, MONTHLY, List.of(), DECEMBER))
                .as("a household with no debt gets an empty state, not a plan of nothing")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesALadderThatMixesCurrencies() {
        LadderDebt inEuros =
                new LadderDebt(
                        id(6),
                        "Euro card",
                        Money.of("1000.00", "EUR"),
                        new BigDecimal("12.000"),
                        Money.of("50.00", "EUR"),
                        null);

        assertThatThrownBy(
                        () ->
                                ladder.project(
                                        PayoffStrategy.AVALANCHE,
                                        MONTHLY,
                                        List.of(visa, inEuros),
                                        DECEMBER))
                .as("the plan's totals are per currency; a converted figure is never an input")
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void refusesADebtThatIsAlreadyPaidOff() {
        assertThatThrownBy(() -> debt(9, "Settled", "0.00", "12.000", "50.00"))
                .as("a debt at zero has left the ladder")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------- fixtures

    @Provide
    Arbitrary<List<LadderDebt>> ladders() {
        Arbitrary<BigDecimal> balances =
                Arbitraries.bigDecimals()
                        .between(new BigDecimal("100.00"), new BigDecimal("40000.00"))
                        .ofScale(2);
        Arbitrary<BigDecimal> rates =
                Arbitraries.bigDecimals()
                        .between(BigDecimal.ZERO, new BigDecimal("34.999"))
                        .ofScale(3);
        Arbitrary<Integer> fractions = Arbitraries.integers().between(20, 90);
        Arbitrary<Integer> numbers = Arbitraries.integers().between(1, 999);
        Arbitrary<LadderDebt> debts =
                Combinators.combine(balances, rates, fractions, numbers)
                        .as(
                                (balance, rate, fraction, number) ->
                                        new LadderDebt(
                                                id(number),
                                                "Debt " + number,
                                                Money.of(balance, GBP),
                                                rate,
                                                Money.of(
                                                        balance.divide(
                                                                BigDecimal.valueOf(fraction),
                                                                2,
                                                                RoundingMode.CEILING),
                                                        GBP),
                                                null));
        return debts.list().ofMinSize(2).ofMaxSize(4);
    }

    private boolean distinctNames(List<LadderDebt> debts) {
        return debts.stream().map(LadderDebt::name).distinct().count() == debts.size();
    }

    private PayoffProjection.Ranked avalanche() {
        return (PayoffProjection.Ranked)
                ladder.project(PayoffStrategy.AVALANCHE, MONTHLY, threeDebts(), DECEMBER);
    }

    private List<String> names(List<LadderDebt> debts) {
        return debts.stream().map(LadderDebt::name).toList();
    }

    private static LadderDebt debt(
            int number, String name, String balance, String rate, String minimum) {
        return new LadderDebt(
                id(number), name, money(balance), new BigDecimal(rate), money(minimum), null);
    }

    private static UUID id(int number) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(number));
    }

    private static Money money(String amount) {
        return Money.of(amount, "GBP");
    }
}
