package com.budgetowl.checkin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.common.InvalidRequestException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** The day a completion records things on (weekly-check-in.md, Departures; ADR-0043). */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HouseholdDayTest {

    /** 23:00 UTC on Aug 31: Aug 31 in UTC−12, Sep 1 in UTC+14. */
    private static final Instant NOW = Instant.parse("2026-08-31T23:00:00Z");

    private static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);
    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);

    @Test
    void the_clients_own_date_is_the_day_a_bill_is_marked_paid_and_the_latest_a_payment_may_be() {
        HouseholdDay sydney = HouseholdDay.of(SEP_1, NOW, AUG_31);

        assertThat(sydney.markedPaidOn()).isEqualTo(SEP_1);
        assertThat(sydney.noLaterThanToday(SEP_1)).isEqualTo(SEP_1);
        assertThat(sydney.noLaterThanToday(LocalDate.of(2026, 9, 5))).isEqualTo(SEP_1);
        assertThat(HouseholdDay.of(AUG_31, NOW, AUG_31).noLaterThanToday(SEP_1)).isEqualTo(AUG_31);
    }

    @Test
    void without_a_date_the_server_marks_paid_and_no_true_date_is_moved_into_the_month_before() {
        HouseholdDay unknown = HouseholdDay.of(null, NOW, AUG_31);

        assertThat(unknown.markedPaidOn()).as("as before the field existed").isEqualTo(AUG_31);
        assertThat(unknown.noLaterThanToday(SEP_1))
                .as("a charge the household dated Sep 1 stays in September")
                .isEqualTo(SEP_1);
        assertThat(unknown.noLaterThanToday(LocalDate.of(2026, 9, 2))).isEqualTo(SEP_1);
        assertThat(unknown.noLaterThanToday(LocalDate.of(2026, 8, 20)))
                .isEqualTo(LocalDate.of(2026, 8, 20));
    }

    @Test
    void a_date_nobody_on_earth_is_living_in_is_refused() {
        for (LocalDate nowhere :
                new LocalDate[] {
                    LocalDate.of(2026, 8, 30), LocalDate.of(2026, 9, 2), LocalDate.of(1899, 12, 31)
                }) {
            assertThatThrownBy(() -> HouseholdDay.of(nowhere, NOW, AUG_31))
                    .as(nowhere.toString())
                    .isInstanceOf(InvalidRequestException.class);
        }
    }
}
