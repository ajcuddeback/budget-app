package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The figure the Bills &amp; Income page leads with: what is left to spend this month.
 *
 * <p>Income, minus the month's bills, minus what the envelopes set aside. The cases here are the
 * ones where getting it wrong would be worse than not having it: that paying a bill does not make
 * the household look richer, that the figure goes negative, and that the headline agrees with the
 * three blocks printed under it.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PeriodSummaryIT extends ApiTestBase {

    private static final String MARCH = "2099-03";

    @Test
    void what_is_left_is_income_minus_bills_minus_envelopes() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", 28);
        createBill(household.owner(), "Rent", "1200.00", 15);
        createEnvelope(household.owner(), "Groceries", "400.00");

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");

        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.stringField("income")).isEqualTo("2400.0000");
        assertThat(summary.stringField("bills")).isEqualTo("1200.0000");
        assertThat(summary.stringField("envelopesAllocated")).isEqualTo("400.0000");
        // One subtraction, and the page leads with it.
        assertThat(summary.stringField("left")).isEqualTo("800.0000");
        assertThat(summary.stringField("currency")).isEqualTo("GBP");
    }

    @Test
    void paying_a_bill_does_not_make_the_household_look_richer() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", 28);
        UUID rent = createBill(household.owner(), "Rent", "1200.00", 15);

        String before =
                household.owner().get("/api/periods/" + MARCH + "/summary").stringField("left");
        household
                .owner()
                .put("/api/bills/" + rent + "/occurrences/2099-03-15", "{\"status\":\"PAID\"}");
        ApiResponse after = household.owner().get("/api/periods/" + MARCH + "/summary");

        // Rent that has gone is not money still available. Counting only the unpaid bills would
        // make the figure most optimistic on the day the household had least left.
        assertThat(after.stringField("left")).isEqualTo(before).isEqualTo("1200.0000");
        // What moves is the secondary line, which is the one that should.
        assertThat(after.stringField("billsOutstanding")).isEqualTo("0.0000");
        assertThat(after.stringField("bills")).isEqualTo("1200.0000");
    }

    @Test
    void paying_more_than_was_budgeted_is_what_the_month_actually_cost() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", 28);
        UUID rent = createBill(household.owner(), "Rent", "1200.00", 15);

        household
                .owner()
                .put(
                        "/api/bills/" + rent + "/occurrences/2099-03-15",
                        "{\"status\":\"PAID\",\"actualAmount\":\"1260.00\"}");

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");
        assertThat(summary.stringField("bills")).isEqualTo("1260.0000");
        assertThat(summary.stringField("left")).isEqualTo("1140.0000");
    }

    @Test
    void skipping_a_bill_takes_it_out_of_the_month_entirely() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", 28);
        UUID rent = createBill(household.owner(), "Rent", "1200.00", 15);

        household
                .owner()
                .put("/api/bills/" + rent + "/occurrences/2099-03-15", "{\"status\":\"SKIPPED\"}");

        // It did not apply. Not an unpaid bill, and not a debt carried forward.
        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");
        assertThat(summary.stringField("bills")).isEqualTo("0.0000");
        assertThat(summary.stringField("left")).isEqualTo("2400.0000");
    }

    @Test
    void the_figure_goes_negative_rather_than_being_clamped() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "1000.00", 28);
        createBill(household.owner(), "Rent", "1200.00", 15);

        // "You are £200 short this month" is the information. Clamping to zero would hide exactly
        // the thing somebody needs to know, in the month they most need to know it.
        assertThat(household.owner().get("/api/periods/" + MARCH + "/summary").stringField("left"))
                .isEqualTo("-200.0000");
    }

    @Test
    void an_overdue_bill_is_reported_without_changing_what_is_left() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", 28);
        createBill(household.owner(), "Rent", "1200.00", 15);

        ApiResponse past = household.owner().get("/api/periods/2020-01/summary");

        // Overdue is a fact about the date, not a different amount of money.
        assertThat(past.stringField("billsOverdue")).isEqualTo("1200.0000");
        assertThat(past.stringField("billsOutstanding")).isEqualTo("1200.0000");
        assertThat(past.stringField("left")).isEqualTo("1200.0000");
    }

    @Test
    void an_archived_envelope_is_not_money_set_aside() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", 28);
        UUID holiday = createEnvelope(household.owner(), "Holiday", "500.00");

        assertThat(household.owner().get("/api/periods/" + MARCH + "/summary").stringField("left"))
                .isEqualTo("1900.0000");

        assertThat(household.owner().post("/api/envelopes/" + holiday + "/archive", "").status())
                .isEqualTo(200);

        // A retired envelope is not part of the plan. Counting one would understate what is left
        // by a figure that would look perfectly reasonable.
        assertThat(household.owner().get("/api/periods/" + MARCH + "/summary").stringField("left"))
                .isEqualTo("2400.0000");
    }

    @Test
    void the_headline_agrees_with_the_blocks_printed_under_it() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", 28);
        createBill(household.owner(), "Rent", "1200.00", 15);
        createEnvelope(household.owner(), "Groceries", "400.00");

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");
        ApiResponse income = household.owner().get("/api/income?period=" + MARCH);
        ApiResponse bills = household.owner().get("/api/bills?period=" + MARCH);
        ApiResponse envelopes =
                household.owner().get("/api/envelopes?period=" + MARCH + "&includeArchived=true");

        // The summary is composed from the same three services, so a page cannot show a headline
        // its own blocks disagree with. This is the assertion that keeps that true.
        assertThat(summary.stringField("income")).isEqualTo(income.stringField("total"));
        assertThat(summary.stringField("bills")).isEqualTo(bills.stringField("total"));
        assertThat(summary.stringField("envelopesAllocated"))
                .isEqualTo(envelopes.stringField("totalAllocated"));
    }

    @Test
    void an_empty_month_is_nothing_rather_than_an_error() {
        Household household = seedHousehold();

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");

        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.stringField("left")).isEqualTo("0.0000");
        assertThat(summary.stringField("currency"))
                .as("a household with no plan still has a currency")
                .isEqualTo("GBP");
    }

    @Test
    void a_viewer_may_read_the_summary() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", 28);

        assertThat(household.viewer().get("/api/periods/" + MARCH + "/summary").stringField("left"))
                .isEqualTo("2400.0000");
    }

    @Test
    void a_malformed_month_is_refused_before_it_reaches_a_query() {
        Household household = seedHousehold();

        assertThat(household.owner().get("/api/periods/not-a-month/summary").status())
                .isEqualTo(400);
        assertThat(household.owner().get("/api/periods/2099-13/summary").status()).isEqualTo(400);
    }

    @Test
    void an_outsider_gets_nothing() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00", 28);

        assertThat(anonymous().get("/api/periods/" + MARCH + "/summary").status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------------------- internals

    private UUID createIncome(ApiClient client, String name, String amount, int dayOfMonth) {
        ApiResponse response =
                client.post(
                        "/api/income",
                        """
                        {"name":"%s","amount":"%s",
                         "schedule":{"kind":"MONTHLY","dayOfMonth":%d}}
                        """
                                .formatted(name, amount, dayOfMonth));
        if (response.status() != 201) {
            throw new AssertionError("could not create an income source: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    private UUID createBill(ApiClient client, String name, String amount, int dayOfMonth) {
        ApiResponse response =
                client.post(
                        "/api/bills",
                        """
                        {"name":"%s","expectedAmount":"%s",
                         "schedule":{"kind":"MONTHLY","dayOfMonth":%d}}
                        """
                                .formatted(name, amount, dayOfMonth));
        if (response.status() != 201) {
            throw new AssertionError("could not create a bill: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    private UUID createEnvelope(ApiClient client, String name, String amount) {
        ApiResponse response =
                client.post(
                        "/api/envelopes",
                        """
                        {"name":"%s","period":"%s","amount":"%s"}
                        """
                                .formatted(name, MARCH, amount));
        if (response.status() != 201) {
            throw new AssertionError("could not create an envelope: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }
}
