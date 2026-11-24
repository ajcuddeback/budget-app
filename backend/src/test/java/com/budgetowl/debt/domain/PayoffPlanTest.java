package com.budgetowl.debt.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.common.Money;
import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Currency;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The plan and its snapshot: two figures the household chose, and what they projected at the moment
 * they last changed.
 *
 * <p>The property worth proving without a database is the one the schema cannot express on its own:
 * a monthly amount <b>below the sum of the minimums is accepted</b>. A household that genuinely
 * cannot meet its minimums exists, and refusing their plan — at the edge, here, or in the database
 * — tells somebody in trouble that their situation is invalid input.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PayoffPlanTest {

    private static final Currency GBP = Currency.getInstance("GBP");

    @Test
    void a_new_plan_commits_what_it_was_given_and_derives_nothing() {
        Household household = household();

        PayoffPlan plan = PayoffPlan.of(household, "AVALANCHE", Money.of("905.00", "GBP"));

        assertThat(plan.householdId()).isEqualTo(household.id());
        assertThat(plan.strategy()).isEqualTo("AVALANCHE");
        assertThat(plan.monthlyAmount()).isEqualByComparingTo("905.0000");
        assertThat(plan.monthlyAmount(GBP)).isEqualTo(Money.of("905.00", "GBP"));
        assertThat(plan.id()).isNull();
        assertThat(plan.version()).isZero();
        assertThat(plan.createdAt()).isNull();
        assertThat(plan.updatedAt()).isNull();
    }

    @Test
    void an_amount_below_the_minimums_is_accepted_and_a_negative_one_is_not() {
        PayoffPlan plan = PayoffPlan.of(household(), "AVALANCHE", Money.zero(GBP));

        // Zero rather than the sum of the minimums for a household that has not set a plan: that
        // would be a figure nobody chose, presented as one they had.
        assertThat(plan.monthlyAmount()).isEqualByComparingTo("0.0000");
        assertThatCode(() -> plan.commit(Money.of("1.00", "GBP"))).doesNotThrowAnyException();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> plan.commit(Money.of("-1.00", "GBP")));
        assertThatNullPointerException().isThrownBy(() -> plan.commit(null));
    }

    @Test
    void a_plan_always_has_a_strategy() {
        PayoffPlan plan = PayoffPlan.of(household(), "AVALANCHE", Money.zero(GBP));

        plan.attackIn("  SNOWBALL  ");
        assertThat(plan.strategy()).isEqualTo("SNOWBALL");
        assertThatIllegalArgumentException().isThrownBy(() -> plan.attackIn("   "));
        assertThatNullPointerException().isThrownBy(() -> plan.attackIn(null));
    }

    @Test
    void prints_the_strategy_and_not_the_amount() {
        PayoffPlan plan =
                Ids.withId(PayoffPlan.of(household(), "AVALANCHE", Money.of("905.00", "GBP")));

        // The monthly amount is a statement of disposable income.
        assertThat(plan.toString()).contains("AVALANCHE").doesNotContain("905");
    }

    @Test
    void a_snapshot_carries_a_projection_or_neither_of_its_halves() {
        Household household = household();
        Instant takenAt = Instant.parse("2026-11-24T09:00:00Z");

        PayoffPlanSnapshot taken =
                PayoffPlanSnapshot.of(
                        household,
                        takenAt,
                        "AVALANCHE",
                        Money.of("905.00", "GBP"),
                        YearMonth.of(2029, 5),
                        Money.of("3456.78", "GBP"));

        assertThat(taken.householdId()).isEqualTo(household.id());
        assertThat(taken.takenAt()).isEqualTo(takenAt);
        assertThat(taken.strategy()).isEqualTo("AVALANCHE");
        assertThat(taken.monthlyAmount()).isEqualByComparingTo("905.0000");
        assertThat(taken.currency()).isEqualTo("GBP");
        assertThat(taken.debtFreeMonth()).contains(YearMonth.of(2029, 5));
        assertThat(taken.totalInterest()).isPresent();
        assertThat(taken.id()).isNull();
        assertThat(taken.createdAt()).isNull();

        PayoffPlanSnapshot nothingToProject =
                PayoffPlanSnapshot.of(
                        household, takenAt, "AVALANCHE", Money.of("10.00", "GBP"), null, null);
        assertThat(nothingToProject.debtFreeMonth()).isEmpty();
        assertThat(nothingToProject.totalInterest()).isEmpty();

        // Half a projection renders as half a comparison: a date the household cannot check, or a
        // cost with no end. ck_payoff_plan_snapshots_projection_is_whole says the same thing.
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                PayoffPlanSnapshot.of(
                                        household,
                                        takenAt,
                                        "AVALANCHE",
                                        Money.of("905.00", "GBP"),
                                        YearMonth.of(2029, 5),
                                        null));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                PayoffPlanSnapshot.of(
                                        household,
                                        null,
                                        "AVALANCHE",
                                        Money.of("905.00", "GBP"),
                                        null,
                                        null));
    }

    @Test
    void a_snapshot_prints_when_and_in_what_currency_and_nothing_else() {
        PayoffPlanSnapshot taken =
                Ids.withId(
                        PayoffPlanSnapshot.of(
                                household(),
                                Instant.parse("2026-11-24T09:00:00Z"),
                                "AVALANCHE",
                                Money.of("905.00", "GBP"),
                                YearMonth.of(2029, 5),
                                Money.of("3456.78", "GBP")));

        assertThat(taken.toString())
                .contains("GBP")
                .doesNotContain("905")
                .doesNotContain("3456")
                .doesNotContain("2029-05");
    }

    private static Household household() {
        return Ids.withId(Household.named("Home", GBP));
    }
}
