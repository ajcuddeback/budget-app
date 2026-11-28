package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Bills over real HTTP against real PostgreSQL.
 *
 * <p>The cases chosen are the ones that would make the feature useless or wrong: that a period
 * nobody has touched reads as every occurrence still due, that a weekly bill holds one record per
 * payment, that recording the same status twice is the same statement, that a bill never writes a
 * transaction, and that overdue is computed rather than swept.
 *
 * <p><b>The months are deliberately far from today.</b> {@code PAST} is a month everything in which
 * is overdue and {@code FUTURE} is one where nothing is, whatever day the suite runs on — the clock
 * is real and the alternative is a test that passes until the end of the month.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BillIT extends ApiTestBase {

    private static final String PAST = "2020-01";
    private static final String FUTURE = "2099-03";

    @Test
    void a_period_nobody_has_touched_reads_as_every_occurrence_still_due() {
        Household household = seedHousehold();
        createBill(household.owner(), "Rent", "1200.00", monthly(15));

        ApiResponse august = household.owner().get("/api/bills?period=" + FUTURE);

        assertThat(august.status()).isEqualTo(200);
        // The property that makes a scheduler unnecessary (ADR-0032): nothing is written, and the
        // month reads exactly as it would if every row existed and said DUE.
        assertThat(august.body()).contains("\"status\":\"DUE\"").contains("\"recorded\":false");
        assertThat(august.body()).contains("\"dueDate\":\"2099-03-15\"");
        assertThat(august.stringField("outstanding")).isEqualTo("1200.0000");
        assertThat(countOccurrenceRows()).as("a read writes nothing at all").isZero();
    }

    @Test
    void a_monthly_bill_is_clamped_to_the_months_last_day() {
        Household household = seedHousehold();
        createBill(household.owner(), "Rent", "1200.00", monthly(31));

        // February 2099 is not a leap year, so the 31st is the 28th.
        assertThat(household.owner().get("/api/bills?period=2099-02").body())
                .contains("\"dueDate\":\"2099-02-28\"");
        assertThat(household.owner().get("/api/bills?period=2099-04").body())
                .contains("\"dueDate\":\"2099-04-30\"");
    }

    @Test
    void a_yearly_bill_appears_in_its_own_month_and_in_no_other() {
        Household household = seedHousehold();
        createBill(
                household.owner(),
                "Car insurance",
                "480.00",
                "{\"kind\":\"YEARLY\",\"month\":4,\"dayOfMonth\":30}");

        assertThat(household.owner().get("/api/bills?period=2099-04").body())
                .contains("\"dueDate\":\"2099-04-30\"");
        // Listed, with no occurrences. Not hidden: it is still a bill the household has.
        ApiResponse march = household.owner().get("/api/bills?period=2099-03");
        assertThat(march.body()).contains("Car insurance").contains("\"occurrences\":[]");
        assertThat(march.stringField("total")).isEqualTo("0.0000");
    }

    @Test
    void a_weekly_bill_holds_one_record_per_payment_and_not_one_per_month() {
        Household household = seedHousehold();
        UUID cleaner =
                createBill(
                        household.owner(),
                        "Cleaner",
                        "40.00",
                        "{\"kind\":\"WEEKLY\",\"dayOfWeek\":\"FRIDAY\"}");

        // March 2099 has four Fridays: the 6th, 13th, 20th and 27th.
        ApiResponse march = household.owner().get("/api/bills?period=" + FUTURE);
        assertThat(march.stringField("outstanding")).isEqualTo("160.0000");

        markPaid(household.owner(), cleaner, "2099-03-06");
        markPaid(household.owner(), cleaner, "2099-03-20");

        // The defect a month-keyed occurrence would have caused: the second record refused, with
        // no way to say which Friday was which.
        ApiResponse after = household.owner().get("/api/bills?period=" + FUTURE);
        assertThat(after.stringField("outstanding")).isEqualTo("80.0000");
        assertThat(after.stringField("total")).isEqualTo("160.0000");
        assertThat(countOccurrenceRows()).isEqualTo(2);
    }

    @Test
    void a_fortnightly_bill_steps_in_whole_weeks_from_its_anchor() {
        Household household = seedHousehold();
        createBill(
                household.owner(),
                "Childcare",
                "90.00",
                """
                {"kind":"FORTNIGHTLY","dayOfWeek":"FRIDAY","anchorDate":"2099-03-06"}
                """);

        ApiResponse march = household.owner().get("/api/bills?period=" + FUTURE);

        // The 6th and the 20th, not every Friday — and counted from the anchor rather than from
        // the start of the month, so it does not drift in a five-Friday month.
        assertThat(march.body()).contains("\"dueDate\":\"2099-03-06\"");
        assertThat(march.body()).contains("\"dueDate\":\"2099-03-20\"");
        assertThat(march.body()).doesNotContain("\"dueDate\":\"2099-03-13\"");
        assertThat(march.stringField("outstanding")).isEqualTo("180.0000");
    }

    @Test
    void an_irregular_bill_is_listed_but_projects_nothing_until_something_is_recorded() {
        Household household = seedHousehold();
        UUID vet = createBill(household.owner(), "Vet", "0.00", "{\"kind\":\"IRREGULAR\"}");

        assertThat(household.owner().get("/api/bills?period=" + FUTURE).body())
                .contains("Vet")
                .contains("\"occurrences\":[]");

        // Recording against a date is what brings the occurrence into being — the only schedule
        // where that is true, and the reason IRREGULAR exists at all.
        ApiResponse recorded =
                household
                        .owner()
                        .put(
                                "/api/bills/" + vet + "/occurrences/2099-03-11",
                                "{\"status\":\"PAID\",\"actualAmount\":\"96.50\"}");
        assertThat(recorded.status()).isEqualTo(200);
        assertThat(household.owner().get("/api/bills?period=" + FUTURE).stringField("total"))
                .isEqualTo("96.5000");
    }

    @Test
    void marking_a_bill_paid_takes_one_request_and_means_what_was_budgeted() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        ApiResponse paid =
                household
                        .owner()
                        .put(
                                "/api/bills/" + rent + "/occurrences/2099-03-15",
                                "{\"status\":\"PAID\"}");

        // "I paid it" is the whole action, and it means what the bill says, today.
        assertThat(paid.status()).isEqualTo(200);
        assertThat(paid.stringField("actualAmount")).isEqualTo("1200.0000");
        assertThat(paid.stringField("paidDate")).isNotBlank();
        assertThat(paid.stringField("statusSource")).isEqualTo("MANUAL");
        assertThat(paid.body()).contains("\"recorded\":true").contains("\"surprising\":false");
    }

    @Test
    void recording_the_same_status_twice_is_the_same_statement_twice() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        markPaid(household.owner(), rent, "2099-03-15");
        ApiResponse again = markPaid(household.owner(), rent, "2099-03-15");

        // PUT, not POST: the URL is valid before anything exists at it, and a second tap on a
        // button somebody is not sure registered must not be an error (ADR-0032).
        assertThat(again.status()).isEqualTo(200);
        assertThat(countOccurrenceRows()).isEqualTo(1);
    }

    @Test
    void both_what_was_budgeted_and_what_was_paid_are_kept() {
        Household household = seedHousehold();
        UUID phone = createBill(household.owner(), "Phone", "120.00", monthly(14), true, "22.00");

        ApiResponse within =
                household
                        .owner()
                        .put(
                                "/api/bills/" + phone + "/occurrences/2099-03-14",
                                "{\"status\":\"PAID\",\"actualAmount\":\"134.10\"}");

        // The difference is the useful number, which is unrepresentable without keeping both.
        assertThat(within.stringField("expectedAmount")).isEqualTo("120.0000");
        assertThat(within.stringField("actualAmount")).isEqualTo("134.1000");
        assertThat(within.body())
                .as("£14.10 over on a bill that says £22 of variation is normal")
                .contains("\"surprising\":false");

        ApiResponse beyond =
                household
                        .owner()
                        .put(
                                "/api/bills/" + phone + "/occurrences/2099-03-14",
                                "{\"status\":\"PAID\",\"actualAmount\":\"300.00\"}");
        assertThat(beyond.body()).contains("\"surprising\":true");
    }

    @Test
    void a_fixed_bill_flags_any_difference_at_all() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        ApiResponse paid =
                household
                        .owner()
                        .put(
                                "/api/bills/" + rent + "/occurrences/2099-03-15",
                                "{\"status\":\"PAID\",\"actualAmount\":\"1200.01\"}");

        // A bill with no tolerance said what it would be. A penny out is worth showing.
        assertThat(paid.body()).contains("\"surprising\":true");
    }

    @Test
    void skipping_a_bill_is_not_the_same_as_not_paying_it() {
        Household household = seedHousehold();
        UUID cleaner =
                createBill(
                        household.owner(),
                        "Cleaner",
                        "40.00",
                        "{\"kind\":\"WEEKLY\",\"dayOfWeek\":\"FRIDAY\"}");

        household
                .owner()
                .put(
                        "/api/bills/" + cleaner + "/occurrences/2099-03-13",
                        "{\"status\":\"SKIPPED\"}");

        ApiResponse march = household.owner().get("/api/bills?period=" + FUTURE);
        // Three Fridays left to pay out of four, and the skipped one owes nothing.
        assertThat(march.stringField("outstanding")).isEqualTo("120.0000");
        assertThat(march.stringField("total")).isEqualTo("120.0000");
        assertThat(march.body()).contains("\"status\":\"SKIPPED\"");
    }

    @Test
    void overdue_is_computed_from_the_date_and_not_stored_anywhere() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        ApiResponse old = household.owner().get("/api/bills?period=" + PAST);
        // No job has ever run on this instance, and January 2020 is still overdue.
        assertThat(old.body()).contains("\"overdue\":true");
        assertThat(old.stringField("overdue")).isEqualTo("1200.0000");

        assertThat(household.owner().get("/api/bills?period=" + FUTURE).body())
                .contains("\"overdue\":false");

        markPaid(household.owner(), rent, "2020-01-15");
        ApiResponse settled = household.owner().get("/api/bills?period=" + PAST);
        assertThat(settled.stringField("overdue")).isEqualTo("0.0000");
    }

    @Test
    void forgetting_a_record_is_not_the_same_as_recording_that_it_is_due() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));
        markPaid(household.owner(), rent, "2099-03-15");

        assertThat(
                        household
                                .owner()
                                .delete("/api/bills/" + rent + "/occurrences/2099-03-15")
                                .status())
                .isEqualTo(204);

        // Back to the projection, with no row: an assertion removed, not replaced by another one.
        assertThat(countOccurrenceRows()).isZero();
        assertThat(household.owner().get("/api/bills?period=" + FUTURE).body())
                .contains("\"recorded\":false");
        assertThat(
                        household
                                .owner()
                                .delete("/api/bills/" + rent + "/occurrences/2099-03-15")
                                .status())
                .as("nothing is recorded there any more")
                .isEqualTo(404);
    }

    @Test
    void a_date_the_schedule_does_not_imply_is_not_an_occurrence() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        ApiResponse refused =
                household
                        .owner()
                        .put(
                                "/api/bills/" + rent + "/occurrences/2099-03-16",
                                "{\"status\":\"PAID\"}");

        // The URL names an occurrence that does not and cannot exist. A row written here would be
        // a payment against a plan that never said that date.
        assertThat(refused.status()).isEqualTo(404);
        assertThat(countOccurrenceRows()).isZero();
    }

    @Test
    void a_malformed_date_is_refused_before_it_reaches_anything() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        assertThat(
                        household
                                .owner()
                                .put(
                                        "/api/bills/" + rent + "/occurrences/not-a-date",
                                        "{\"status\":\"PAID\"}")
                                .status())
                .isEqualTo(400);
    }

    @Test
    void a_retired_bill_keeps_what_was_recorded_and_takes_nothing_new() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));
        markPaid(household.owner(), rent, "2020-01-15");

        ApiResponse retired =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + rent,
                                "{\"active\":false,\"version\":"
                                        + versionOf(household, rent)
                                        + "}");
        assertThat(retired.status()).isEqualTo(200);

        // Retired, not erased: January 2020 still reads as paid.
        ApiResponse past =
                household.owner().get("/api/bills?period=" + PAST + "&includeInactive=true");
        assertThat(past.body()).contains("\"status\":\"PAID\"");
        // And it projects nothing going forward, so there is nothing new to tick off.
        assertThat(
                        household
                                .owner()
                                .get("/api/bills?period=" + FUTURE + "&includeInactive=true")
                                .body())
                .contains("\"occurrences\":[]");
        assertThat(
                        household
                                .owner()
                                .put(
                                        "/api/bills/" + rent + "/occurrences/2099-03-15",
                                        "{\"status\":\"PAID\"}")
                                .status())
                .isEqualTo(409);
    }

    @Test
    void a_retired_bill_is_out_of_the_list_unless_it_is_asked_for() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));
        household
                .owner()
                .patch(
                        "/api/bills/" + rent,
                        "{\"active\":false,\"version\":" + versionOf(household, rent) + "}");

        assertThat(household.owner().get("/api/bills?period=" + FUTURE).body())
                .doesNotContain("Rent");
        assertThat(
                        household
                                .owner()
                                .get("/api/bills?period=" + FUTURE + "&includeInactive=true")
                                .body())
                .contains("Rent");
    }

    @Test
    void a_viewer_sees_every_figure_and_records_nothing() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        assertThat(household.viewer().get("/api/bills?period=" + FUTURE).stringField("outstanding"))
                .isEqualTo("1200.0000");
        assertThat(
                        household
                                .viewer()
                                .put(
                                        "/api/bills/" + rent + "/occurrences/2099-03-15",
                                        "{\"status\":\"PAID\"}")
                                .status())
                .isEqualTo(403);
        assertThat(household.viewer().delete("/api/bills/" + rent).status()).isEqualTo(403);
        assertThat(countOccurrenceRows()).isZero();
    }

    @Test
    void a_bill_never_writes_a_transaction() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        markPaid(household.owner(), rent, "2099-03-15");

        // ADR-0025. The app is not the authority on whether money moved, and anything it invents
        // and writes into the ledger is a claim it made up.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isZero();
    }

    @Test
    void deleting_a_bill_takes_its_records_and_leaves_the_ledger_alone() {
        Household household = seedHousehold();
        UUID current = createAccount(household.owner(), "Current", "4321");
        createTransaction(household.owner(), current, "2099-03-15", "-1200.00", null);
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));
        markPaid(household.owner(), rent, "2099-03-15");

        assertThat(household.owner().delete("/api/bills/" + rent).status()).isEqualTo(204);

        assertThat(countOccurrenceRows()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .as("a transaction outlives any plan it was filed under")
                .isEqualTo(1);
    }

    @Test
    void a_schedule_carrying_a_parameter_it_cannot_use_is_refused() {
        Household household = seedHousehold();

        // A MONTHLY bill with a weekday and no day of month projects nothing, silently, and looks
        // like a bill nobody set up. Refused at the edge and again by ck_bills_schedule_parameters.
        assertThat(
                        createBillResponse(
                                        household.owner(),
                                        "Rent",
                                        "1200.00",
                                        "{\"kind\":\"MONTHLY\",\"dayOfWeek\":\"FRIDAY\"}")
                                .status())
                .isEqualTo(400);
        assertThat(
                        createBillResponse(
                                        household.owner(),
                                        "Rent",
                                        "1200.00",
                                        "{\"kind\":\"MONTHLY\",\"dayOfMonth\":15,\"month\":4}")
                                .status())
                .as("a parameter the kind has no use for is refused, not ignored")
                .isEqualTo(400);
        assertThat(
                        createBillResponse(
                                        household.owner(),
                                        "Childcare",
                                        "90.00",
                                        "{\"kind\":\"FORTNIGHTLY\",\"dayOfWeek\":\"FRIDAY\"}")
                                .status())
                .as("every other Friday from when?")
                .isEqualTo(400);
    }

    @Test
    void a_tolerance_belongs_to_a_bill_that_varies_and_to_no_other() {
        Household household = seedHousehold();

        assertThat(
                        household
                                .owner()
                                .post(
                                        "/api/bills",
                                        """
                                        {"name":"Phone","expectedAmount":"120.00",
                                         "schedule":{"kind":"MONTHLY","dayOfMonth":14},
                                         "amountVaries":true}
                                        """)
                                .status())
                .isEqualTo(400);
        assertThat(
                        household
                                .owner()
                                .post(
                                        "/api/bills",
                                        """
                                        {"name":"Rent","expectedAmount":"1200.00",
                                         "schedule":{"kind":"MONTHLY","dayOfMonth":1},
                                         "amountVaries":false,"tolerance":"22.00"}
                                        """)
                                .status())
                .as("a tolerance on a fixed bill is a number nothing would ever read")
                .isEqualTo(400);
    }

    @Test
    void a_bill_needs_nothing_but_a_name_an_amount_and_a_schedule() {
        Household household = seedHousehold();

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/bills",
                                """
                                {"name":"Rent","expectedAmount":"1200.00",
                                 "schedule":{"kind":"MONTHLY","dayOfMonth":15}}
                                """);

        // Most bills have a fixed amount and no category, so most requests leave those fields out.
        // A boxed `amountVaries` is what makes that work — see the field's own note.
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body())
                .contains("\"amountVaries\":false")
                .contains("\"tolerance\":null");
    }

    @Test
    void two_bills_cannot_share_a_name() {
        Household household = seedHousehold();
        createBill(household.owner(), "Rent", "1200.00", monthly(15));

        ApiResponse again = createBillResponse(household.owner(), "rent", "1200.00", monthly(15));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body()).contains("bill-name-taken");
    }

    // ------------------------------------------------------------------------------------ edits

    @Test
    void editing_one_field_leaves_the_others_as_they_were() {
        Household household = seedHousehold();
        UUID groceries = seededCategoryId("Groceries");
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));
        household
                .owner()
                .patch(
                        "/api/bills/" + rent,
                        """
                        {"categoryId":"%s","version":%d}
                        """
                                .formatted(groceries, versionOf(household, rent)));

        ApiResponse renamed =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + rent,
                                "{\"name\":\"Rent and service charge\",\"version\":"
                                        + versionOf(household, rent)
                                        + "}");

        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.stringField("name")).isEqualTo("Rent and service charge");
        // A patch that silently cleared the fields it did not mention would be the worst kind of
        // bug here: the category and the schedule are what the bill is for.
        assertThat(renamed.stringField("expectedAmount")).isEqualTo("1200.0000");
        assertThat(renamed.stringField("categoryName")).isEqualTo("Groceries");
        assertThat(renamed.body()).contains("\"dayOfMonth\":15");
    }

    @Test
    void a_category_can_be_cleared_and_its_name_comes_back_with_the_bill() {
        Household household = seedHousehold();
        UUID housing = createCategory(household.owner(), "Housing", "EXPENSE", null);
        UUID rentCategory = createCategory(household.owner(), "Rent", "EXPENSE", housing);
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        ApiResponse filed =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + rent,
                                """
                                {"categoryId":"%s","version":%d}
                                """
                                        .formatted(rentCategory, versionOf(household, rent)));
        assertThat(filed.stringField("categoryName")).isEqualTo("Rent");
        assertThat(filed.stringField("categoryParentName")).isEqualTo("Housing");

        // An explicit null clears it; an absent field does not. That distinction is the whole
        // reason the request is a class with setters rather than a record.
        ApiResponse cleared =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + rent,
                                "{\"categoryId\":null,\"version\":"
                                        + versionOf(household, rent)
                                        + "}");
        assertThat(cleared.body())
                .contains("\"categoryId\":null")
                .contains("\"categoryName\":null");
    }

    @Test
    void changing_the_schedule_corrects_the_future_and_keeps_a_settled_month_settled() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));
        markPaid(household.owner(), rent, "2020-01-15");

        ApiResponse moved =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + rent,
                                """
                                {"schedule":{"kind":"MONTHLY","dayOfMonth":20},"version":%d}
                                """
                                        .formatted(versionOf(household, rent)));
        assertThat(moved.status()).isEqualTo(200);

        // The future is corrected instantly, because nothing was written down to contradict it.
        assertThat(household.owner().get("/api/bills?period=" + FUTURE).body())
                .contains("\"dueDate\":\"2099-03-20\"");

        // And January 2020 still shows ONE rent, paid — not a paid 15th beside an unpaid 20th.
        // This is ADR-0032's step 2, and it is the reason editing a bill is safe for a household
        // that has been keeping records.
        ApiResponse past = household.owner().get("/api/bills?period=" + PAST);
        assertThat(past.stringField("outstanding")).isEqualTo("0.0000");
        assertThat(past.stringField("total")).isEqualTo("1200.0000");
        assertThat(past.body()).contains("\"status\":\"PAID\"");
        // On the date it was settled against, not the bill's new one: that is the date the record
        // is addressed at, so a client shown the 20th could not correct what it is looking at.
        assertThat(past.body()).contains("\"dueDate\":\"2020-01-15\"");
        assertThat(
                        household
                                .owner()
                                .delete("/api/bills/" + rent + "/occurrences/2020-01-15")
                                .status())
                .as("and that date still addresses it")
                .isEqualTo(204);
    }

    @Test
    void a_schedule_can_change_kind_entirely() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        ApiResponse weekly =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + rent,
                                """
                                {"schedule":{"kind":"WEEKLY","dayOfWeek":"FRIDAY"},"version":%d}
                                """
                                        .formatted(versionOf(household, rent)));

        // The parameters move wholesale: a leftover day-of-month on a weekly bill would pass every
        // CHECK and then project something nobody asked for.
        assertThat(weekly.status()).isEqualTo(200);
        assertThat(weekly.body())
                .contains("\"dayOfMonth\":null")
                .contains("\"dayOfWeek\":\"FRIDAY\"");
        assertThat(household.owner().get("/api/bills?period=" + FUTURE).stringField("outstanding"))
                .isEqualTo("4800.0000");
    }

    @Test
    void a_bill_can_start_varying_and_stop_varying() {
        Household household = seedHousehold();
        UUID phone = createBill(household.owner(), "Phone", "120.00", monthly(14));

        ApiResponse varies =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + phone,
                                """
                                {"amountVaries":true,"tolerance":"22.00","version":%d}
                                """
                                        .formatted(versionOf(household, phone)));
        assertThat(varies.stringField("tolerance")).isEqualTo("22.0000");

        ApiResponse fixed =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + phone,
                                "{\"amountVaries\":false,\"version\":"
                                        + versionOf(household, phone)
                                        + "}");
        assertThat(fixed.body()).contains("\"amountVaries\":false").contains("\"tolerance\":null");
    }

    @Test
    void a_tolerance_without_saying_whether_the_amount_varies_is_refused() {
        Household household = seedHousehold();
        UUID phone = createBill(household.owner(), "Phone", "120.00", monthly(14));

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + phone,
                                "{\"tolerance\":\"22.00\",\"version\":"
                                        + versionOf(household, phone)
                                        + "}");

        // It would set a number that means nothing on a fixed bill. Refused rather than guessed at.
        assertThat(refused.status()).isEqualTo(400);
    }

    @Test
    void an_edit_from_a_stale_copy_is_refused_rather_than_applied() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));
        long stale = versionOf(household, rent);
        household
                .owner()
                .patch(
                        "/api/bills/" + rent,
                        "{\"expectedAmount\":\"1250.00\",\"version\":" + stale + "}");

        ApiResponse second =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + rent,
                                "{\"expectedAmount\":\"1300.00\",\"version\":" + stale + "}");

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body()).contains("stale-version");
        assertThat(
                        household
                                .owner()
                                .get("/api/bills/" + rent + "?period=" + FUTURE)
                                .stringField("expectedAmount"))
                .as("the second member's figure must not have landed")
                .isEqualTo("1250.0000");
    }

    @Test
    void an_edit_without_a_version_is_refused() {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        // Optimistic locking only works if the caller says which version it read.
        assertThat(household.owner().patch("/api/bills/" + rent, "{\"name\":\"Rent\"}").status())
                .isEqualTo(400);
    }

    @Test
    void a_member_may_do_everything_an_owner_may_do_here() {
        Household household = seedHousehold();
        UUID rent = createBill(household.member(), "Rent", "1200.00", monthly(15));

        // Bills are household data, not administration: a MEMBER is not a lesser owner of them.
        assertThat(markPaid(household.member(), rent, "2099-03-15").status()).isEqualTo(200);
        assertThat(household.member().delete("/api/bills/" + rent).status()).isEqualTo(204);
    }

    // ----------------------------------------------------------------------------------- roles

    /**
     * Method, path and body for each route, with {@code {id}} to be substituted.
     *
     * <p>A list rather than a sample, for the reason {@code AuthorizationIT} gives: adding an
     * endpoint without adding it here is the failure mode this exists to catch.
     */
    static List<Endpoint> everyRoute() {
        return List.of(
                new Endpoint("GET", "/api/bills?period=" + FUTURE, null),
                new Endpoint(
                        "POST",
                        "/api/bills",
                        """
                        {"name":"New","expectedAmount":"10.00",
                         "schedule":{"kind":"MONTHLY","dayOfMonth":1}}
                        """),
                new Endpoint("GET", "/api/bills/{id}?period=" + FUTURE, null),
                new Endpoint("PATCH", "/api/bills/{id}", "{\"version\":0,\"name\":\"Renamed\"}"),
                new Endpoint("DELETE", "/api/bills/{id}", null),
                new Endpoint(
                        "PUT", "/api/bills/{id}/occurrences/2099-03-15", "{\"status\":\"PAID\"}"),
                new Endpoint("DELETE", "/api/bills/{id}/occurrences/2099-03-15", null));
    }

    /** The four that change something. A {@code VIEWER} gets {@code 403} from every one. */
    static List<Endpoint> everyWrite() {
        return everyRoute().stream().filter(endpoint -> !"GET".equals(endpoint.method())).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403ToAViewerOnEveryWrite(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));

        ApiResponse response = endpoint.callWith(household.viewer(), rent);

        // The single highest-value authorization test for this feature: a VIEWER reads a
        // household's whole financial plan and must not be able to change one figure of it.
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("read-only-role");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void the_bearer_transport_is_turned_away_from_no_route(Endpoint endpoint) {
        Household household = seedHousehold();
        UUID rent = createBill(household.owner(), "Rent", "1200.00", monthly(15));
        ApiClient phone = mobile(OWNER_EMAIL, OWNER_SECRET, "Ada's phone");

        ApiResponse response = endpoint.callWith(phone, rent);

        // Neither transport may skip a check the other applies, and neither may be refused a
        // check the other passes (ADR-0018). Asserted as "not turned away" rather than by
        // comparing the two transports' statuses: the same call made twice is not the same call,
        // so a comparison would fail on the second one for reasons that have nothing to do with
        // the transport. A route demanding a session cookie, or a CSRF token a phone cannot
        // supply, lands here as the 401 or 403 it really is.
        assertThat(response.status()).isNotIn(401, 403);
        assertThat(response.status())
                .as("and the route must not simply fall over for a phone")
                .isLessThan(500);
    }

    record Endpoint(String method, String path, String body) {

        ApiResponse callWith(ApiClient client, UUID billId) {
            return client.send(method, path.replace("{id}", billId.toString()), body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    // ------------------------------------------------------------------------------- internals

    private static String monthly(int dayOfMonth) {
        return "{\"kind\":\"MONTHLY\",\"dayOfMonth\":" + dayOfMonth + "}";
    }

    private UUID createBill(ApiClient client, String name, String amount, String schedule) {
        return createBill(client, name, amount, schedule, false, null);
    }

    private UUID createBill(
            ApiClient client,
            String name,
            String amount,
            String schedule,
            boolean varies,
            String tolerance) {
        ApiResponse response =
                client.post(
                        "/api/bills",
                        """
                        {"name":"%s","expectedAmount":"%s","schedule":%s,
                         "amountVaries":%s,"tolerance":%s}
                        """
                                .formatted(
                                        name,
                                        amount,
                                        schedule,
                                        varies,
                                        tolerance == null ? "null" : "\"" + tolerance + "\""));
        if (response.status() != 201) {
            throw new AssertionError("could not create a bill: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    private ApiResponse createBillResponse(
            ApiClient client, String name, String amount, String schedule) {
        return client.post(
                "/api/bills",
                """
                {"name":"%s","expectedAmount":"%s","schedule":%s}
                """
                        .formatted(name, amount, schedule));
    }

    private ApiResponse markPaid(ApiClient client, UUID billId, String dueDate) {
        return client.put(
                "/api/bills/" + billId + "/occurrences/" + dueDate, "{\"status\":\"PAID\"}");
    }

    private long versionOf(Household household, UUID billId) {
        return household
                .owner()
                .get("/api/bills/" + billId + "?period=" + FUTURE)
                .numberField("version");
    }

    private int countOccurrenceRows() {
        return jdbc.queryForObject("SELECT count(*) FROM bill_occurrences", Integer.class);
    }
}
