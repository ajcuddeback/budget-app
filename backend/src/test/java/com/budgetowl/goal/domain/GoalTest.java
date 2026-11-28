package com.budgetowl.goal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.Money;
import com.budgetowl.household.domain.Household;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Currency;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** The goal entities' own rules, with no database. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GoalTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Household HOUSEHOLD = Household.named("Ada and Grace", GBP);

    @Test
    void a_name_is_trimmed_and_a_blank_one_is_refused() {
        Goal goal = Goal.of(HOUSEHOLD, "  Japan  ", Money.of("3500", "GBP"));

        assertThat(goal.name()).isEqualTo("Japan");
        assertThatThrownBy(() -> goal.rename("   ")).isInstanceOf(IllegalArgumentException.class);
        // An ideographic space is whitespace too: strip() knows that even where trim() does not.
        assertThatThrownBy(() -> goal.rename("　")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> goal.rename("x".repeat(Goal.NAME_MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_target_of_nothing_is_not_a_goal() {
        assertThatThrownBy(() -> Goal.of(HOUSEHOLD, "Japan", Money.of("0", "GBP")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Goal.of(HOUSEHOLD, "Japan", Money.of("-1", "GBP")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_currency_is_fixed_at_creation_and_every_amount_must_be_in_it() {
        Goal goal = Goal.of(HOUSEHOLD, "Japan", Money.of("3500", "GBP"));

        assertThat(goal.currency()).isEqualTo(GBP);
        assertThatThrownBy(() -> goal.retarget(Money.of("3500", "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> goal.startFrom(Money.of("10", "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
        // No method on the entity can change it: a goal in pounds cannot become one in euros
        // without every figure being reinterpreted.
        assertThat(
                        Arrays.stream(Goal.class.getDeclaredMethods())
                                .filter(method -> Modifier.isPublic(method.getModifiers()))
                                .map(Method::getName))
                .noneMatch(
                        name ->
                                name.toLowerCase(java.util.Locale.ROOT).contains("currency")
                                        && !name.equals("currency"));
        assertThat(goal.currency()).isNotEqualTo(EUR);
    }

    @Test
    void the_starting_amount_cannot_be_negative() {
        Goal goal = Goal.of(HOUSEHOLD, "Emergency fund", Money.of("6000", "GBP"));

        goal.startFrom(Money.of("0", "GBP"));
        assertThatThrownBy(() -> goal.startFrom(Money.of("-0.01", "GBP")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_target_month_is_kept_as_its_month_and_can_be_cleared() {
        Goal goal = Goal.of(HOUSEHOLD, "Japan", Money.of("3500", "GBP"));

        goal.aimFor(YearMonth.of(2027, 11));
        assertThat(goal.targetMonth()).contains(YearMonth.of(2027, 11));
        goal.aimFor(null);
        assertThat(goal.targetMonth()).isEmpty();
    }

    @Test
    void a_zero_contribution_is_refused_and_a_negative_one_is_a_withdrawal() {
        Goal goal = Goal.of(HOUSEHOLD, "Emergency fund", Money.of("6000", "GBP"));
        LocalDate day = LocalDate.of(2026, 8, 20);

        assertThatThrownBy(() -> GoalContribution.to(goal, day, Money.of("0", "GBP")))
                .isInstanceOf(IllegalArgumentException.class);
        GoalContribution withdrawal = GoalContribution.to(goal, day, Money.of("-500", "GBP"));
        assertThat(withdrawal.amount()).isEqualByComparingTo("-500");
        assertThatThrownBy(() -> GoalContribution.to(goal, day, Money.of("10", "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_contributions_month_is_the_calendar_month_of_its_date_and_moves_with_it() {
        Goal goal = Goal.of(HOUSEHOLD, "Japan", Money.of("3500", "GBP"));
        GoalContribution contribution =
                GoalContribution.to(goal, LocalDate.of(2026, 8, 31), Money.of("110", "GBP"));

        assertThat(contribution.period()).isEqualTo(YearMonth.of(2026, 8));
        contribution.on(LocalDate.of(2026, 9, 1));
        assertThat(contribution.period()).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    void a_blank_note_is_cleared_and_a_long_one_refused() {
        Goal goal = Goal.of(HOUSEHOLD, "Japan", Money.of("3500", "GBP"));
        GoalContribution contribution =
                GoalContribution.to(goal, LocalDate.of(2026, 8, 20), Money.of("110", "GBP"));

        contribution.annotate("  ");
        assertThat(contribution.note()).isEmpty();
        assertThatThrownBy(
                        () ->
                                contribution.annotate(
                                        "x".repeat(GoalContribution.NOTE_MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_plan_step_cannot_be_negative_or_in_another_currency() {
        Goal goal = Goal.of(HOUSEHOLD, "Japan", Money.of("3500", "GBP"));
        YearMonth august = YearMonth.of(2026, 8);

        assertThat(GoalContributionStep.from(goal, august, Money.of("0", "GBP")).monthlyAmount())
                .isEqualByComparingTo("0");
        assertThatThrownBy(() -> GoalContributionStep.from(goal, august, Money.of("-1", "GBP")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GoalContributionStep.from(goal, august, Money.of("1", "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void no_to_string_prints_a_name_an_amount_or_a_note() {
        Goal goal = Goal.of(HOUSEHOLD, "Divorce lawyer", Money.of("4321.98", "GBP"));
        goal.startFrom(Money.of("1234.56", "GBP"));
        GoalContribution contribution =
                GoalContribution.to(goal, LocalDate.of(2026, 8, 20), Money.of("765.43", "GBP"));
        contribution.annotate("from the solicitor's retainer");
        GoalContributionStep step =
                GoalContributionStep.from(goal, YearMonth.of(2026, 8), Money.of("246.80", "GBP"));

        for (String printed :
                new String[] {goal.toString(), contribution.toString(), step.toString()}) {
            assertThat(printed)
                    .doesNotContain("Divorce")
                    .doesNotContain("4321")
                    .doesNotContain("1234")
                    .doesNotContain("765")
                    .doesNotContain("solicitor")
                    .doesNotContain("246");
        }
    }
}
