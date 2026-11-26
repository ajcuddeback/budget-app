package com.budgetowl.goal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A goal's arithmetic, as a unit, with no database and no clock.
 *
 * <p>This is the part of the feature that would be wrong silently: every figure here is derived,
 * and a plausible-looking wrong one is indistinguishable from a right one on screen. The fixtures
 * are the designs' Japan goal — $890 of $3,500 by November 2027 at $110 a month, looked at from
 * August 2026 — because those are the figures the feature doc reconciles: the $163.13 that reaches
 * $3,500 by November 2027 is what the canvas's proposed $165 rounds up.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GoalProjectionTest {

    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth NOVEMBER_2027 = YearMonth.of(2027, 11);
    private static final Currency USD = Currency.getInstance("USD");

    // ------------------------------------------------------------------------------ saved

    @Test
    void saved_is_the_starting_amount_plus_every_contribution_withdrawals_included() {
        GoalLedger ledger =
                ledger(
                        "6000",
                        "2000",
                        null,
                        List.of(),
                        Map.of(
                                AUGUST.minusMonths(2),
                                "500.25",
                                AUGUST.minusMonths(1),
                                "-160.10",
                                AUGUST,
                                "0.0001"));

        assertThat(ledger.saved().amount()).isEqualByComparingTo("2340.1501");
        assertThat(GoalProjection.figures(ledger, AUGUST).remaining().amount())
                .isEqualByComparingTo("3659.8499");
    }

    @Test
    void saved_is_not_clamped_in_either_direction() {
        GoalLedger overdrawn =
                ledger("1000", "100", null, List.of(), Map.of(AUGUST.minusMonths(1), "-400"));
        GoalLedger overfunded =
                ledger("1000", "0", null, List.of(), Map.of(AUGUST.minusMonths(1), "1250"));

        assertThat(GoalProjection.figures(overdrawn, AUGUST).saved().amount())
                .isEqualByComparingTo("-300");
        assertThat(GoalProjection.figures(overfunded, AUGUST).saved().amount())
                .isEqualByComparingTo("1250");
        assertThat(GoalProjection.figures(overfunded, AUGUST).remaining().amount())
                .isEqualByComparingTo("-250");
        assertThat(GoalProjection.figures(overfunded, AUGUST).reached()).isTrue();
    }

    // -------------------------------------------------------------------- projected month

    @Test
    void the_projected_month_is_the_first_the_walk_reaches_and_the_month_before_falls_short() {
        GoalLedger japan = japan(Map.of(AUGUST.minusMonths(1), "890"));

        GoalFigures figures = GoalProjection.figures(japan, AUGUST);

        // 890 + 110 × 24 = 3,530 reaches 3,500 in the 24th month counting August: July 2028.
        assertThat(figures.projection().projectedMonth()).isEqualTo(YearMonth.of(2028, 7));
        assertThat(figures.projection().projectedReason()).isNull();
        // 890 + 110 × 23 = 3,420 does not: June 2028 is short.
        assertThat(
                        GoalProjection.walk(japan, AUGUST, 23)
                                instanceof GoalProjection.Walk.OutOfMonths)
                .isTrue();
    }

    @Test
    void a_step_in_a_future_month_changes_the_walk_from_that_month_and_not_before() {
        List<PlanStep> raised = List.of(step(AUGUST, "110"), step(AUGUST.plusMonths(2), "165"));
        List<PlanStep> flat = List.of(step(AUGUST, "110"));

        // 110 + 110 + 165 = 385 in October with the raise; 110 × 4 = 440 in November without it.
        GoalLedger withRaise = ledger("385", "0", null, raised, Map.of());
        GoalLedger withoutRaise = ledger("385", "0", null, flat, Map.of());
        assertThat(GoalProjection.figures(withRaise, AUGUST).projection().projectedMonth())
                .isEqualTo(AUGUST.plusMonths(2));
        assertThat(GoalProjection.figures(withoutRaise, AUGUST).projection().projectedMonth())
                .isEqualTo(AUGUST.plusMonths(3));

        // A raise in December cannot pull a September answer forwards.
        GoalLedger lateRaise =
                ledger(
                        "220",
                        "0",
                        null,
                        List.of(step(AUGUST, "110"), step(AUGUST.plusMonths(4), "999")),
                        Map.of());
        assertThat(GoalProjection.figures(lateRaise, AUGUST).projection().projectedMonth())
                .isEqualTo(AUGUST.plusMonths(1));
        assertThat(lateRaise.inForce(AUGUST.plusMonths(3)).amount()).isEqualByComparingTo("110");
        assertThat(lateRaise.inForce(AUGUST.plusMonths(4)).amount()).isEqualByComparingTo("999");
    }

    @Test
    void no_plan_at_all_answers_no_contribution() {
        GoalLedger unplanned = ledger("3500", "0", NOVEMBER_2027, List.of(), Map.of());

        GoalFigures.Projection projection = GoalProjection.figures(unplanned, AUGUST).projection();

        assertThat(projection.projectedMonth()).isNull();
        assertThat(projection.projectedReason()).isEqualTo(ProjectionReason.NO_CONTRIBUTION);
    }

    @Test
    void a_paused_plan_answers_no_contribution_rather_than_a_horizon() {
        GoalLedger paused =
                ledger(
                        "3500",
                        "0",
                        null,
                        List.of(step(AUGUST.minusMonths(3), "110"), step(AUGUST, "0")),
                        Map.of());
        GoalLedger stopping =
                ledger(
                        "3500",
                        "0",
                        null,
                        List.of(step(AUGUST, "110"), step(AUGUST.plusMonths(2), "0")),
                        Map.of());

        assertThat(GoalProjection.figures(paused, AUGUST).projection().projectedReason())
                .isEqualTo(ProjectionReason.NO_CONTRIBUTION);
        // Two months of 110 and then nothing, ever: not "fifty years away", but never.
        assertThat(GoalProjection.figures(stopping, AUGUST).projection().projectedReason())
                .isEqualTo(ProjectionReason.NO_CONTRIBUTION);
    }

    @Test
    void a_reached_goal_answers_reached_for_both_figures() {
        GoalLedger reached =
                ledger(
                        "1000",
                        "400",
                        NOVEMBER_2027,
                        List.of(step(AUGUST, "110")),
                        Map.of(AUGUST.minusMonths(1), "600"));

        GoalFigures figures = GoalProjection.figures(reached, AUGUST);

        assertThat(figures.reached()).isTrue();
        assertThat(figures.overdue()).isFalse();
        assertThat(figures.projection().projectedReason()).isEqualTo(ProjectionReason.REACHED);
        assertThat(figures.projection().requiredReason()).isEqualTo(ProjectionReason.REACHED);
    }

    @Test
    void a_passed_target_month_is_overdue_and_still_says_when_it_will_get_there() {
        GoalLedger late =
                ledger(
                        "3500",
                        "890",
                        AUGUST.minusMonths(1),
                        List.of(step(AUGUST, "110")),
                        Map.of());

        GoalFigures figures = GoalProjection.figures(late, AUGUST);

        assertThat(figures.overdue()).isTrue();
        assertThat(figures.projection().requiredMonthly()).isNull();
        assertThat(figures.projection().requiredReason())
                .isEqualTo(ProjectionReason.TARGET_MONTH_PASSED);
        assertThat(figures.projection().projectedMonth()).isEqualTo(YearMonth.of(2028, 7));
    }

    @Test
    void no_target_month_means_no_required_figure() {
        GoalFigures figures =
                GoalProjection.figures(
                        ledger("6000", "2340", null, List.of(step(AUGUST, "200")), Map.of()),
                        AUGUST);

        assertThat(figures.projection().requiredMonthly()).isNull();
        assertThat(figures.projection().requiredReason())
                .isEqualTo(ProjectionReason.NO_TARGET_MONTH);
        assertThat(figures.overdue()).isFalse();
    }

    /**
     * Threat model, attack 5: a plan of {@code 0.0001} against {@code 999999999999999} is about
     * 10^19 months of walking. Timed, so that removing the cap fails here rather than merely making
     * every list read slower.
     */
    @Test
    void the_walk_stops_at_the_horizon_and_answers_beyond_horizon_quickly() {
        GoalLedger attack =
                ledger(
                        "999999999999999",
                        "0",
                        AUGUST.plusMonths(GoalProjection.HORIZON_MONTHS + 12L),
                        List.of(step(AUGUST, "0.0001")),
                        Map.of());

        GoalFigures figures =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2), () -> GoalProjection.figures(attack, AUGUST));

        assertThat(figures.projection().projectedMonth()).isNull();
        assertThat(figures.projection().projectedReason())
                .isEqualTo(ProjectionReason.BEYOND_HORIZON);
        assertThat(figures.projection().requiredReason())
                .isEqualTo(ProjectionReason.BEYOND_HORIZON);
    }

    @Test
    void a_target_month_within_the_horizon_still_finds_the_required_figure_for_a_huge_target() {
        GoalLedger huge =
                ledger(
                        "999999999999999",
                        "0",
                        AUGUST.plusMonths(GoalProjection.HORIZON_MONTHS - 1L),
                        List.of(step(AUGUST, "0.0001")),
                        Map.of());

        GoalFigures figures =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2), () -> GoalProjection.figures(huge, AUGUST));

        // 999,999,999,999,999 ÷ 600, rounded up to the cent.
        assertThat(figures.projection().requiredMonthly().amount())
                .isEqualByComparingTo("1666666666666.67");
    }

    // ------------------------------------------------------------------- required monthly

    @Test
    void the_designs_japan_goal_needs_163_13_a_month_to_reach_3500_by_november_2027() {
        GoalFigures figures =
                GoalProjection.figures(japan(Map.of(AUGUST.minusMonths(1), "890")), AUGUST);

        // (3,500 − 890) ÷ 16 = 163.125, rounded UP to 163.13. HALF_EVEN would say 163.12, which
        // leaves the household eight cents short in November and a month late.
        assertThat(figures.projection().requiredMonthly().amount()).isEqualByComparingTo("163.13");
        assertThat(figures.projection().requiredReason()).isNull();
        assertThat(figures.monthlyContribution().amount()).isEqualByComparingTo("110");
    }

    /**
     * The boundary test, in three currencies with three minor units. At {@code requiredMonthly} the
     * target is reached in or before the target month; at one minor unit less it is not. This is
     * the test that catches {@code HALF_EVEN} sneaking in.
     */
    @ParameterizedTest(name = "{0}: {1} by {2} months")
    @CsvSource({
        "GBP, 3500.00, 16, 890.00",
        "GBP, 1000.00, 7, 0.00",
        "GBP, 999.99, 3, 0.01",
        "JPY, 350000, 16, 89000",
        "JPY, 100000, 7, 1",
        "KWD, 3500.000, 7, 890.000",
        "KWD, 1000.000, 3, 0.001",
    })
    void at_the_required_figure_it_arrives_on_time_and_one_minor_unit_less_it_does_not(
            String currency, String target, int months, String start) {
        Currency unit = Currency.getInstance(currency);
        YearMonth deadline = AUGUST.plusMonths(months - 1L);
        GoalLedger goal =
                new GoalLedger(
                        Money.of(new BigDecimal(target), unit),
                        Money.of(new BigDecimal(start), unit),
                        deadline,
                        List.of(),
                        Map.of());

        Money required = GoalProjection.figures(goal, AUGUST).projection().requiredMonthly();
        BigDecimal oneLess =
                required.amount()
                        .subtract(BigDecimal.ONE.movePointLeft(unit.getDefaultFractionDigits()));

        assertThat(required.amount().stripTrailingZeros().scale())
                .as("at most the currency's own minor unit")
                .isLessThanOrEqualTo(unit.getDefaultFractionDigits());
        assertThat(projectedAt(goal, required.amount())).isBeforeOrEqualTo(deadline);
        assertThat(projectedAt(goal, oneLess)).isAfter(deadline);
    }

    @Test
    void a_yen_figure_has_no_decimals_and_rounds_up_to_the_next_yen() {
        Currency jpy = Currency.getInstance("JPY");
        GoalLedger goal =
                new GoalLedger(
                        Money.of(new BigDecimal("350000"), jpy),
                        Money.of(new BigDecimal("89000"), jpy),
                        NOVEMBER_2027,
                        List.of(),
                        Map.of());

        // 261,000 ÷ 16 = 16,312.5 yen; there is no half yen to pay.
        assertThat(GoalProjection.figures(goal, AUGUST).projection().requiredMonthly().amount())
                .isEqualByComparingTo("16313");
    }

    @Test
    void a_target_month_equal_to_this_month_asks_for_everything_still_remaining_now() {
        GoalLedger dueNow = ledger("1000", "400", AUGUST, List.of(), Map.of(AUGUST, "100"));

        // The plan for August would have to be 600, of which 100 is already recorded.
        assertThat(GoalProjection.figures(dueNow, AUGUST).projection().requiredMonthly().amount())
                .isEqualByComparingTo("600");
    }

    @Test
    void the_required_figure_moves_to_the_months_after_this_one_once_this_months_slot_is_filled() {
        // 200 recorded in August against a required 163.13 a month: August is done, and the rest,
        // 3,500 − 1,090 = 2,410, is spread over the fifteen months after it.
        GoalLedger topped = japan(Map.of(AUGUST.minusMonths(1), "890", AUGUST, "200"));

        Money required = GoalProjection.figures(topped, AUGUST).projection().requiredMonthly();

        assertThat(required.amount()).isEqualByComparingTo("160.67");
        assertThat(projectedAt(topped, required.amount())).isEqualTo(NOVEMBER_2027);
        assertThat(projectedAt(topped, new BigDecimal("160.66"))).isAfter(NOVEMBER_2027);
    }

    @Test
    void
            no_amount_reaches_it_when_this_month_is_the_deadline_and_this_months_plan_does_not_count() {
        // The month began reached (1,000 saved by July) and a withdrawal took it back below. This
        // month's plan therefore does not count, and next month is past the deadline.
        GoalLedger withdrawn =
                ledger(
                        "1000",
                        "0",
                        AUGUST,
                        List.of(step(AUGUST, "110")),
                        Map.of(AUGUST.minusMonths(1), "1000", AUGUST, "-300"));

        GoalFigures.Projection projection = GoalProjection.figures(withdrawn, AUGUST).projection();

        assertThat(projection.requiredMonthly()).isNull();
        assertThat(projection.requiredReason())
                .isEqualTo(ProjectionReason.NOT_REACHABLE_BY_TARGET_MONTH);
    }

    // ---------------------------------------------------------------- this month's rule

    @ParameterizedTest(name = "recorded {0} against a plan of 110")
    @CsvSource({"0, 110, 110", "50, 60, 110", "110, 0, 110", "200, 0, 200", "-500, 110, 110"})
    void still_to_come_and_this_months_contribution_follow_the_greater_of_rule(
            String recorded, String stillToCome, String contribution) {
        Map<YearMonth, String> months = new HashMap<>();
        months.put(AUGUST.minusMonths(1), "890");
        if (!"0".equals(recorded)) {
            months.put(AUGUST, recorded);
        }
        GoalLedger japan = japan(months);

        GoalFigures.ThisPeriod thisPeriod = GoalProjection.figures(japan, AUGUST).thisPeriod();

        assertThat(thisPeriod.planned().amount()).isEqualByComparingTo("110");
        assertThat(thisPeriod.recorded().amount()).isEqualByComparingTo(recorded);
        assertThat(thisPeriod.stillToCome().amount()).isEqualByComparingTo(stillToCome);
        assertThat(japan.monthContribution(AUGUST).amount()).isEqualByComparingTo(contribution);
    }

    @Test
    void a_withdrawal_never_raises_what_a_month_leaves_free() {
        GoalLedger planned = japan(Map.of(AUGUST.minusMonths(1), "890", AUGUST, "-500"));
        GoalLedger paused =
                ledger(
                        "3500",
                        "0",
                        null,
                        List.of(step(AUGUST, "0")),
                        Map.of(AUGUST.minusMonths(1), "890", AUGUST, "-500"));

        assertThat(planned.monthContribution(AUGUST).amount()).isEqualByComparingTo("110");
        assertThat(paused.monthContribution(AUGUST).amount()).isEqualByComparingTo("0");
    }

    @Test
    void
            a_month_that_began_reached_counts_only_what_was_recorded_and_the_next_month_counts_again() {
        // Reached in July. August began reached, so its 110 is not spoken for.
        GoalLedger reachedInJuly =
                ledger(
                        "1000",
                        "0",
                        null,
                        List.of(step(AUGUST.minusMonths(1), "110")),
                        Map.of(AUGUST.minusMonths(1), "1000", AUGUST, "25"));

        assertThat(reachedInJuly.monthContribution(AUGUST.minusMonths(1)).amount())
                .as("the month it was reached in still counts in full")
                .isEqualByComparingTo("1000");
        assertThat(reachedInJuly.monthContribution(AUGUST).amount()).isEqualByComparingTo("25");

        // A withdrawal in August takes it back below: September counts the plan again.
        GoalLedger dippedBelow =
                ledger(
                        "1000",
                        "0",
                        null,
                        List.of(step(AUGUST.minusMonths(1), "110")),
                        Map.of(AUGUST.minusMonths(1), "1000", AUGUST, "-300"));
        assertThat(dippedBelow.monthContribution(AUGUST).amount()).isEqualByComparingTo("0");
        assertThat(dippedBelow.monthContribution(AUGUST.plusMonths(1)).amount())
                .isEqualByComparingTo("110");
    }

    @Test
    void the_starting_amount_counts_towards_saved_and_never_towards_a_month() {
        GoalLedger started = ledger("6000", "2000", null, List.of(), Map.of());

        assertThat(started.saved().amount()).isEqualByComparingTo("2000");
        assertThat(started.monthContribution(AUGUST).amount()).isEqualByComparingTo("0");
        assertThat(started.monthContribution(AUGUST.minusMonths(6)).amount())
                .isEqualByComparingTo("0");
    }

    @Test
    void a_plan_change_replaces_later_steps_and_an_unchanged_amount_adds_no_step() {
        GoalLedger planned =
                ledger(
                        "3500",
                        "0",
                        null,
                        List.of(step(AUGUST, "110"), step(AUGUST.plusMonths(3), "200")),
                        Map.of());

        List<PlanStep> raised = planned.stepsWith(AUGUST.plusMonths(1), money("165"));
        List<PlanStep> same = planned.stepsWith(AUGUST.plusMonths(1), money("110"));

        assertThat(raised)
                .extracting(PlanStep::fromPeriod)
                .containsExactly(AUGUST, AUGUST.plusMonths(1));
        assertThat(same).extracting(PlanStep::fromPeriod).containsExactly(AUGUST);
        assertThat(planned.withSteps(raised).stepsWith(AUGUST.plusMonths(1), money("165")))
                .as("idempotent: the same instruction again is the same plan")
                .isEqualTo(raised);
    }

    @Test
    void nothing_here_prints_an_amount() {
        GoalLedger japan = japan(Map.of(AUGUST.minusMonths(1), "890.77"));
        GoalFigures figures = GoalProjection.figures(japan, AUGUST);

        assertThat(japan.toString()).doesNotContain("890").doesNotContain("3500");
        assertThat(figures.toString()).doesNotContain("890").doesNotContain("163");
        assertThat(figures.projection().toString()).doesNotContain("163");
        assertThat(figures.thisPeriod().toString()).doesNotContain("110");
        assertThat(step(AUGUST, "110.77").toString()).doesNotContain("110");
    }

    // ---------------------------------------------------------------------------- helpers

    /** The designs' Japan goal: $3,500 by November 2027, at $110 a month from August 2026. */
    private static GoalLedger japan(Map<YearMonth, String> recorded) {
        return ledger("3500", "0", NOVEMBER_2027, List.of(step(AUGUST, "110")), recorded);
    }

    /**
     * Decision 6 of the weekly check-in changed the fifth term to {@code max(planned,
     * recordedOther) + recordedByCheckIn}. Without a check-in contribution that must be slice 11's
     * {@code max(planned, recorded)} to the cent, for every plan and every recorded figure,
     * withdrawals and over-funding included.
     */
    @Test
    void without_check_in_contributions_the_month_is_slice_elevens_figure_to_the_cent() {
        List<PlanStep> plan = List.of(step(AUGUST, "110"));
        for (String recorded :
                List.of("0", "50", "109.9999", "110", "110.0001", "250", "-40", "-0.0001")) {
            GoalLedger ledger = ledger("3500", "0", null, plan, Map.of(AUGUST, recorded));
            Money planned = ledger.planned(AUGUST);
            Money shipped = planned.compareTo(money(recorded)) >= 0 ? planned : money(recorded);
            assertThat(ledger.monthContribution(AUGUST))
                    .as("recorded %s", recorded)
                    .isEqualTo(shipped);
        }
    }

    @Test
    void a_check_in_contribution_counts_on_top_of_the_plan_and_only_on_top() {
        GoalLedger ledger =
                new GoalLedger(
                        money("3500"),
                        money("0"),
                        null,
                        List.of(step(AUGUST, "110")),
                        Map.of(AUGUST, money("113")),
                        Map.of(AUGUST, money("63")));

        // 113 recorded, 63 of it by the check-in: max(110, 50) + 63 = 173.
        assertThat(ledger.monthContribution(AUGUST)).isEqualTo(money("173"));
    }

    private static GoalLedger ledger(
            String target,
            String start,
            YearMonth deadline,
            List<PlanStep> steps,
            Map<YearMonth, String> recorded) {
        Map<YearMonth, Money> months = new HashMap<>();
        recorded.forEach((month, amount) -> months.put(month, money(amount)));
        return new GoalLedger(money(target), money(start), deadline, steps, months);
    }

    private static PlanStep step(YearMonth from, String amount) {
        return new PlanStep(from, money(amount));
    }

    private static Money money(String amount) {
        return Money.of(new BigDecimal(amount), USD);
    }

    /** Where the walk puts the goal with a plan of {@code amount} from August on. */
    private static YearMonth projectedAt(GoalLedger goal, BigDecimal amount) {
        GoalLedger planned =
                goal.withSteps(goal.stepsWith(AUGUST, Money.of(amount, goal.currency())));
        YearMonth month = GoalProjection.figures(planned, AUGUST).projection().projectedMonth();
        return month == null ? YearMonth.of(9999, 12) : month;
    }
}
