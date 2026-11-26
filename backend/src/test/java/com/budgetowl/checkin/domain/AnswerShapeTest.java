package com.budgetowl.checkin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link AnswerShape#requireFits} is the Java statement of V20's per-kind {@code CHECK}s: each kind
 * of item may carry only the references its choice needs, so an answer can never smuggle a second
 * target, a category onto a bill, or an allocation onto a charge.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AnswerShapeTest {

    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID BILL = UUID.randomUUID();
    private static final UUID GOAL = UUID.randomUUID();
    private static final UUID DEBT = UUID.randomUUID();
    private static final LocalDate DUE = LocalDate.of(2026, 8, 24);
    private static final BigDecimal SURPLUS = new BigDecimal("63.00");

    static Stream<Arguments> fitting() {
        return Stream.of(
                Arguments.of(ItemKind.OVERDUE_BILL, AnswerChoice.MARK_PAID, AnswerShape.none()),
                Arguments.of(
                        ItemKind.OVERDUE_BILL,
                        AnswerChoice.MARK_PAID,
                        AnswerShape.debtPrompt(true)),
                Arguments.of(ItemKind.OVERDUE_BILL, AnswerChoice.LEAVE, AnswerShape.none()),
                Arguments.of(ItemKind.UNUSUAL_CHARGE, AnswerChoice.LEAVE, AnswerShape.none()),
                Arguments.of(
                        ItemKind.UNUSUAL_CHARGE,
                        AnswerChoice.PREPAID,
                        AnswerShape.target(BILL, DUE, false, false)),
                Arguments.of(
                        ItemKind.CATEGORIZE,
                        AnswerChoice.CATEGORY,
                        AnswerShape.category(CATEGORY, true)),
                Arguments.of(ItemKind.CATEGORIZE, AnswerChoice.LEAVE, AnswerShape.none()),
                Arguments.of(
                        ItemKind.BILL_CHARGE,
                        AnswerChoice.ATTACH,
                        AnswerShape.target(BILL, DUE, true, true)),
                Arguments.of(ItemKind.BILL_CHARGE, AnswerChoice.LEAVE, AnswerShape.none()),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.DEBT,
                        AnswerShape.allocation(null, DEBT, SURPLUS)),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.GOAL,
                        AnswerShape.allocation(GOAL, null, SURPLUS)),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.ROLL_OVER,
                        AnswerShape.allocation(null, null, SURPLUS)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("fitting")
    void a_shape_its_kind_and_choice_may_carry_is_accepted(
            ItemKind kind, AnswerChoice choice, AnswerShape shape) {
        assertThatCode(() -> shape.requireFits(kind, choice)).doesNotThrowAnyException();
    }

    static Stream<Arguments> misfitting() {
        AnswerShape aCategory = AnswerShape.category(CATEGORY, false);
        AnswerShape aTarget = AnswerShape.target(BILL, DUE, false, false);
        AnswerShape halfATarget = new AnswerShape(null, BILL, null, null, null, null, false, false);
        AnswerShape anAllocation = AnswerShape.allocation(GOAL, null, SURPLUS);
        AnswerShape remembered = new AnswerShape(null, null, null, null, null, null, true, false);
        AnswerShape debtPayment = AnswerShape.debtPrompt(true);
        return Stream.of(
                // An overdue bill carries nothing but, when marked paid, the debt prompt.
                Arguments.of(ItemKind.OVERDUE_BILL, AnswerChoice.MARK_PAID, aCategory),
                Arguments.of(ItemKind.OVERDUE_BILL, AnswerChoice.MARK_PAID, aTarget),
                Arguments.of(ItemKind.OVERDUE_BILL, AnswerChoice.MARK_PAID, anAllocation),
                Arguments.of(ItemKind.OVERDUE_BILL, AnswerChoice.MARK_PAID, remembered),
                Arguments.of(ItemKind.OVERDUE_BILL, AnswerChoice.LEAVE, debtPayment),
                // An unusual charge carries a target only when prepaid.
                Arguments.of(ItemKind.UNUSUAL_CHARGE, AnswerChoice.LEAVE, aTarget),
                Arguments.of(ItemKind.UNUSUAL_CHARGE, AnswerChoice.PREPAID, AnswerShape.none()),
                Arguments.of(ItemKind.UNUSUAL_CHARGE, AnswerChoice.PREPAID, halfATarget),
                Arguments.of(ItemKind.UNUSUAL_CHARGE, AnswerChoice.LEAVE, aCategory),
                Arguments.of(ItemKind.UNUSUAL_CHARGE, AnswerChoice.LEAVE, anAllocation),
                Arguments.of(ItemKind.UNUSUAL_CHARGE, AnswerChoice.LEAVE, remembered),
                Arguments.of(ItemKind.UNUSUAL_CHARGE, AnswerChoice.LEAVE, debtPayment),
                // A row to categorise carries a category only when one is chosen.
                Arguments.of(ItemKind.CATEGORIZE, AnswerChoice.CATEGORY, AnswerShape.none()),
                Arguments.of(ItemKind.CATEGORIZE, AnswerChoice.LEAVE, aCategory),
                Arguments.of(ItemKind.CATEGORIZE, AnswerChoice.LEAVE, remembered),
                Arguments.of(ItemKind.CATEGORIZE, AnswerChoice.CATEGORY, aTarget),
                Arguments.of(ItemKind.CATEGORIZE, AnswerChoice.CATEGORY, anAllocation),
                Arguments.of(ItemKind.CATEGORIZE, AnswerChoice.LEAVE, debtPayment),
                // A bill charge carries a whole target only when attached.
                Arguments.of(ItemKind.BILL_CHARGE, AnswerChoice.ATTACH, AnswerShape.none()),
                Arguments.of(ItemKind.BILL_CHARGE, AnswerChoice.ATTACH, halfATarget),
                Arguments.of(ItemKind.BILL_CHARGE, AnswerChoice.LEAVE, aTarget),
                Arguments.of(ItemKind.BILL_CHARGE, AnswerChoice.LEAVE, remembered),
                Arguments.of(ItemKind.BILL_CHARGE, AnswerChoice.LEAVE, debtPayment),
                Arguments.of(ItemKind.BILL_CHARGE, AnswerChoice.ATTACH, aCategory),
                Arguments.of(ItemKind.BILL_CHARGE, AnswerChoice.LEAVE, anAllocation),
                // An allocation carries a positive amount and exactly the target its choice names.
                Arguments.of(ItemKind.ALLOCATION, AnswerChoice.GOAL, AnswerShape.none()),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.GOAL,
                        AnswerShape.allocation(GOAL, null, BigDecimal.ZERO)),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.GOAL,
                        AnswerShape.allocation(GOAL, null, SURPLUS.negate())),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.GOAL,
                        AnswerShape.allocation(GOAL, DEBT, SURPLUS)),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.DEBT,
                        AnswerShape.allocation(GOAL, null, SURPLUS)),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.ROLL_OVER,
                        AnswerShape.allocation(null, DEBT, SURPLUS)),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.ROLL_OVER,
                        new AnswerShape(CATEGORY, null, null, null, null, SURPLUS, false, false)),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.ROLL_OVER,
                        new AnswerShape(null, BILL, DUE, null, null, SURPLUS, false, false)),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.ROLL_OVER,
                        new AnswerShape(null, null, null, null, null, SURPLUS, true, false)),
                Arguments.of(
                        ItemKind.ALLOCATION,
                        AnswerChoice.ROLL_OVER,
                        new AnswerShape(null, null, null, null, null, SURPLUS, false, true)));
    }

    @ParameterizedTest(name = "{0} {1} #{index}")
    @MethodSource("misfitting")
    void a_shape_its_kind_and_choice_may_not_carry_is_refused(
            ItemKind kind, AnswerChoice choice, AnswerShape shape) {
        assertThatThrownBy(() -> shape.requireFits(kind, choice))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void its_text_names_no_amount_and_no_id() {
        assertThat(AnswerShape.allocation(GOAL, null, SURPLUS).toString())
                .doesNotContain("63")
                .doesNotContain(GOAL.toString());
    }
}
