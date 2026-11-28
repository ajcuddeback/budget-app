package com.budgetowl.checkin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** The two edge parsers: weeks and item keys (threat model, attack 10). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class IsoWeekTest {

    @Test
    void the_canvas_week_is_monday_17_to_sunday_23_august() {
        IsoWeek week = IsoWeek.parse("2026-W34").orElseThrow();

        assertThat(week.monday()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(week.sunday()).isEqualTo(LocalDate.of(2026, 8, 23));
        assertThat(week.toString()).isEqualTo("2026-W34");
        assertThat(IsoWeek.containing(LocalDate.of(2026, 8, 23))).isEqualTo(week);
    }

    @Test
    void week_53_exists_only_in_a_long_year() {
        assertThat(IsoWeek.parse("2026-W53")).isPresent();
        assertThat(IsoWeek.parse("2025-W53")).isEmpty();
        assertThat(IsoWeek.parse("2026-W00")).isEmpty();
    }

    @Test
    void anything_but_the_exact_shape_is_not_a_week() {
        for (String text :
                new String[] {
                    "+10000-W01",
                    "2026-W1",
                    "2026W34",
                    "2026-w34",
                    " 2026-W34",
                    "x".repeat(5000),
                    ""
                }) {
            assertThat(IsoWeek.parse(text)).as(text.length() > 20 ? "long" : text).isEmpty();
        }
        assertThat(IsoWeek.parse(null)).isEmpty();
    }

    @Test
    void the_week_based_year_differs_from_the_calendar_year_at_the_edges() {
        assertThat(IsoWeek.containing(LocalDate.of(2027, 1, 1)).toString()).isEqualTo("2026-W53");
        assertThat(IsoWeek.containing(LocalDate.of(2026, 1, 1)).toString()).isEqualTo("2026-W01");
    }

    @Test
    void previous_and_next_cross_a_year() {
        assertThat(IsoWeek.parse("2027-W01").orElseThrow().previous().toString())
                .isEqualTo("2026-W53");
        assertThat(IsoWeek.parse("2026-W53").orElseThrow().next().toString()).isEqualTo("2027-W01");
    }

    @Test
    void an_item_key_parses_only_in_its_five_shapes() {
        UUID id = UUID.fromString("0b0e7c8e-1f3a-4d2b-9c55-1a2b3c4d5e6f");

        assertThat(ItemKeys.parse("cat." + id).orElseThrow().kind()).isEqualTo(ItemKind.CATEGORIZE);
        assertThat(ItemKeys.parse("odd." + id).orElseThrow().kind())
                .isEqualTo(ItemKind.UNUSUAL_CHARGE);
        assertThat(ItemKeys.parse("chg." + id).orElseThrow().kind())
                .isEqualTo(ItemKind.BILL_CHARGE);
        assertThat(ItemKeys.parse("bill." + id + ".2026-08-25").orElseThrow().dueDate())
                .isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(ItemKeys.parse("alloc").orElseThrow().kind()).isEqualTo(ItemKind.ALLOCATION);
        for (String bad :
                new String[] {
                    "cat." + id.toString().toUpperCase(java.util.Locale.ROOT),
                    "cat." + id + "x",
                    "bill." + id + ".2026-02-30",
                    "bill." + id,
                    "dog." + id,
                    "alloc ",
                    "a".repeat(101),
                    ""
                }) {
            assertThat(ItemKeys.parse(bad)).as(bad.length() > 60 ? "long" : bad).isEmpty();
        }
    }

    @Test
    void a_key_round_trips_from_its_references() {
        UUID id = UUID.randomUUID();

        assertThat(ItemKeys.parse(ItemKeys.categorize(id)).orElseThrow().transactionId())
                .isEqualTo(id);
        assertThat(
                        ItemKeys.parse(ItemKeys.occurrence(id, LocalDate.of(2026, 8, 1)))
                                .orElseThrow()
                                .billId())
                .isEqualTo(id);
    }

    @Test
    void a_year_outside_the_range_is_not_a_week_whichever_way_it_arrives() {
        assertThat(IsoWeek.parse("1899-W10")).isEmpty();
        assertThat(IsoWeek.parse("9999-W10")).isEmpty();
        assertThat(IsoWeek.parse(null)).isEmpty();
        assertThatThrownBy(() -> new IsoWeek(1899, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IsoWeek(9999, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IsoWeek(2025, 53))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IsoWeek(2026, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_week_starts_only_on_a_monday() {
        assertThat(IsoWeek.startingOn(LocalDate.of(2026, 8, 17)).toString()).isEqualTo("2026-W34");
        assertThatThrownBy(() -> IsoWeek.startingOn(LocalDate.of(2026, 8, 18)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_week_contains_monday_to_sunday_and_orders_by_its_monday() {
        IsoWeek week = IsoWeek.parse("2026-W34").orElseThrow();

        assertThat(week.contains(LocalDate.of(2026, 8, 16))).isFalse();
        assertThat(week.contains(LocalDate.of(2026, 8, 17))).isTrue();
        assertThat(week.contains(LocalDate.of(2026, 8, 23))).isTrue();
        assertThat(week.contains(LocalDate.of(2026, 8, 24))).isFalse();
        assertThat(week.compareTo(week.next())).isNegative();
        assertThat(week.compareTo(week.previous())).isPositive();
        assertThat(week.compareTo(IsoWeek.parse("2026-W34").orElseThrow())).isZero();
    }
}
