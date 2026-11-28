package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The counted-once arithmetic, with the designs' own shape.
 *
 * <p>A debt's minimum is frequently <b>also</b> a bill — <em>"the car payment is its bill row"</em>
 * — and counting it in both places is the obvious bug in this slice. The designs' figures show the
 * rule working: three minimums of 185, 342 and 118 total 645, and only <b>303</b> of them is new,
 * because the car loan's 342 is already one of the bill rows.
 *
 * <p>This is also the test that guards a figure which <b>had already shipped</b>: slice 7's {@code
 * left} was income minus bills minus envelopes, and slice 10 subtracts a fourth term (the owner's
 * decision, recorded in {@code docs/features/debt-plan.md}). If that subtraction ever quietly
 * switched to {@code debtMinimums}, a household would be told it had £342 less than it has — a
 * figure that looks entirely reasonable, which is what makes it worth a test of its own.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DebtPeriodSummaryIT extends ApiTestBase {

    private static final String MARCH = "2099-03";

    @Test
    void a_minimum_that_is_also_a_bill_is_counted_once() {
        Household household = seedHousehold();
        UUID carPayment = createBill(household.owner(), "Car payment", "342.00");
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", carPayment);
        createDebt(household.owner(), "Nelnet", "9650.00", "4.500", "118.00", null);

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");

        assertThat(summary.status()).isEqualTo(200);
        assertThat(summary.numberField("debtCount")).isEqualTo(3L);
        assertThat(summary.stringField("owed")).isEqualTo("26480.0000");
        assertThat(summary.stringField("debtMinimums")).isEqualTo("645.0000");
        // 185 + 118. The car loan's 342 is already a bill row, so it is not counted again.
        assertThat(summary.stringField("debtMinimumsNotInBills")).isEqualTo("303.0000");
    }

    @Test
    void what_is_left_subtracts_only_the_minimums_that_are_not_already_bills() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00");
        UUID carPayment = createBill(household.owner(), "Car payment", "342.00");
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", carPayment);
        createDebt(household.owner(), "Nelnet", "9650.00", "4.500", "118.00", null);

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");

        // 2400 income − 342 bills − 0 envelopes − 303 minimums not in bills = 1755.
        assertThat(summary.stringField("income")).isEqualTo("2400.0000");
        assertThat(summary.stringField("bills")).isEqualTo("342.0000");
        assertThat(summary.stringField("envelopesAllocated")).isEqualTo("0.0000");
        assertThat(summary.stringField("debtMinimumsNotInBills")).isEqualTo("303.0000");
        assertThat(summary.stringField("left")).isEqualTo("1755.0000");
        // Subtracting debtMinimums instead would give 1413 — the car payment counted twice, and a
        // figure a household would have no way to tell apart from the right one.
        assertThat(summary.stringField("left")).isNotEqualTo("1413.0000");
    }

    @Test
    void deleting_the_bill_makes_that_minimum_count_into_what_is_left() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00");
        UUID carPayment = createBill(household.owner(), "Car payment", "342.00");
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", carPayment);
        createDebt(household.owner(), "Nelnet", "9650.00", "4.500", "118.00", null);

        assertThat(household.owner().delete("/api/bills/" + carPayment).status()).isEqualTo(204);

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");
        // fk_debts_bill is ON DELETE SET NULL (bill_id), in the column-list form: the link is
        // nulled and the debt is untouched. So the minimum starts counting here instead — and the
        // committed total RISES when a bill is deleted, which is correct and surprising.
        assertThat(summary.stringField("debtMinimums")).isEqualTo("645.0000");
        assertThat(summary.stringField("debtMinimumsNotInBills")).isEqualTo("645.0000");
        assertThat(summary.stringField("bills")).isEqualTo("0.0000");
        // 2400 − 0 − 0 − 645.
        assertThat(summary.stringField("left")).isEqualTo("1755.0000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM debts WHERE bill_id IS NOT NULL", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debts", Long.class))
                .as("deleting a bill does not delete the debt it paid for")
                .isEqualTo(3L);
    }

    @Test
    void retiring_the_bill_makes_that_minimum_count_into_what_is_left() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00");
        UUID carPayment = createBill(household.owner(), "Car payment", "342.00");
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", carPayment);
        createDebt(household.owner(), "Nelnet", "9650.00", "4.500", "118.00", null);

        ApiResponse retired =
                household
                        .owner()
                        .patch(
                                "/api/bills/" + carPayment,
                                "{\"active\":false,\"version\":"
                                        + billVersion(household, carPayment)
                                        + "}");
        assertThat(retired.status()).isEqualTo(200);

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");

        // Retiring is NOT deleting: the link survives, so `bill_id` is still set and the obvious
        // implementation of the counted-once rule — "has a bill_id, so a bill carries it" — would
        // leave this minimum counted in NEITHER place. A retired bill produces no occurrences, so
        // `bills` is 0; if `debtMinimumsNotInBills` were 303, the 342 would have vanished and
        // `left` would read 2097: the household would be told it has £342 more than it has.
        assertThat(summary.stringField("bills")).isEqualTo("0.0000");
        assertThat(summary.stringField("debtMinimums")).isEqualTo("645.0000");
        assertThat(summary.stringField("debtMinimumsNotInBills")).isEqualTo("645.0000");
        assertThat(summary.stringField("left")).isEqualTo("1755.0000");
        assertThat(summary.stringField("left"))
                .as("the minimum of a debt whose bill was retired must not fall between the two")
                .isNotEqualTo("2097.0000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM debts WHERE bill_id IS NOT NULL", Long.class))
                .as("retiring a bill leaves the debt's link in place — unlike deleting it")
                .isEqualTo(1L);
    }

    @Test
    void two_debts_cannot_point_at_the_same_bill() {
        Household household = seedHousehold();
        UUID carPayment = createBill(household.owner(), "Car payment", "342.00");
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", carPayment);

        ApiResponse second =
                household
                        .owner()
                        .post(
                                "/api/debts",
                                """
                                {"name":"Second car","kind":"INSTALLMENT","balance":"5000.0000",
                                 "balanceDate":"%s","apr":"6.400","minimumPayment":"342.0000",
                                 "billId":"%s"}
                                """
                                        .formatted(
                                                java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                                                carPayment));

        // ux_debts_bill_id. Two debts on one bill row would subtract the same minimum twice from
        // what's left, and both figures would look plausible.
        assertThat(second.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debts", Long.class)).isEqualTo(1L);
    }

    @Test
    void an_archived_or_settled_debt_is_not_a_monthly_commitment() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00");
        UUID visa = createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        UUID store = createDebt(household.owner(), "Store card", "100.00", "0.000", "40.00", null);

        household.owner().post("/api/debts/" + visa + "/archive", null);
        household
                .owner()
                .post(
                        "/api/debts/" + store + "/payments",
                        """
                        {"paidDate":"%s","amount":"100.0000"}
                        """
                                .formatted(java.time.LocalDate.now(java.time.ZoneOffset.UTC)));

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");
        // A retired debt is not a commitment and a settled one is not either. Counting either
        // would understate what is left by a figure nobody is paying.
        assertThat(summary.numberField("debtCount")).isZero();
        assertThat(summary.stringField("debtMinimums")).isEqualTo("0.0000");
        assertThat(summary.stringField("left")).isEqualTo("2400.0000");
    }

    @Test
    void the_headline_agrees_with_the_debt_list_printed_beside_it() {
        Household household = seedHousehold();
        UUID carPayment = createBill(household.owner(), "Car payment", "342.00");
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", carPayment);

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");
        ApiResponse debts = household.owner().get("/api/debts");

        // The summary is composed from DebtService rather than querying the tables again, so the
        // Owed card and the debt page cannot disagree. This is the assertion that keeps that true.
        assertThat(summary.stringField("owed")).isEqualTo(debts.stringField("owed"));
        assertThat(summary.stringField("debtMinimums"))
                .isEqualTo(debts.stringField("debtMinimums"));
        assertThat(summary.numberField("debtCount")).isEqualTo(debts.numberField("debtCount"));
        // The SPLIT of the minimums is deliberately not on /api/debts: it depends on which bills
        // a given month's bills term carries, and that request names no month. One producer, so
        // there is nothing to disagree with.
        assertThat(debts.body()).doesNotContain("debtMinimumsNotInBills");
    }

    @Test
    void a_minimum_whose_bill_is_not_due_this_month_counts_into_what_is_left() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00");
        // A YEARLY bill due in June. In March its occurrence list is empty, so it adds nothing to
        // the bills term — and the debt's link to it survives, which is the trap.
        UUID yearly = createYearlyBill(household.owner(), "Car payment", "342.00", 6);
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", yearly);
        createDebt(household.owner(), "Nelnet", "9650.00", "4.500", "118.00", null);

        ApiResponse march = household.owner().get("/api/periods/" + MARCH + "/summary");

        // Eleven months in twelve, the bills term does not carry it. Treating the debt as covered
        // because it names a bill would drop 342 from both terms and read `left` as 2097.
        assertThat(march.stringField("bills")).isEqualTo("0.0000");
        assertThat(march.stringField("debtMinimumsNotInBills")).isEqualTo("645.0000");
        assertThat(march.stringField("left")).isEqualTo("1755.0000");
        assertThat(march.stringField("left")).isNotEqualTo("2097.0000");

        // In June the bill IS due, so the same 342 moves from the debt term to the bills term and
        // the headline does not move. That is the counted-once rule doing its job in both
        // directions, which is what makes it a rule rather than a subtraction that happens to
        // work this month.
        ApiResponse june = household.owner().get("/api/periods/2099-06/summary");
        assertThat(june.stringField("bills")).isEqualTo("342.0000");
        assertThat(june.stringField("debtMinimumsNotInBills")).isEqualTo("303.0000");
        assertThat(june.stringField("left")).isEqualTo("1755.0000");
    }

    @Test
    void skipping_the_bill_moves_that_minimum_back_into_what_is_left() {
        Household household = seedHousehold();
        createIncome(household.owner(), "Salary", "2400.00");
        UUID carPayment = createBill(household.owner(), "Car payment", "342.00");
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);
        createDebt(household.owner(), "Car loan", "10650.00", "6.400", "342.00", carPayment);
        createDebt(household.owner(), "Nelnet", "9650.00", "4.500", "118.00", null);

        ApiResponse skipped =
                household
                        .owner()
                        .put(
                                "/api/bills/" + carPayment + "/occurrences/" + MARCH + "-15",
                                "{\"status\":\"SKIPPED\"}");
        assertThat(skipped.status()).isEqualTo(200);

        ApiResponse summary = household.owner().get("/api/periods/" + MARCH + "/summary");

        // A skipped occurrence is excluded from the bills total by name, so the debt's minimum has
        // to count or it is counted nowhere. The household said it is not paying that bill this
        // month, so the money is free — and `left` does not move, because the same 342 simply
        // changed which term carries it.
        assertThat(summary.stringField("bills")).isEqualTo("0.0000");
        assertThat(summary.stringField("debtMinimumsNotInBills")).isEqualTo("645.0000");
        assertThat(summary.stringField("left")).isEqualTo("1755.0000");
    }

    @Test
    void a_viewer_may_read_the_owed_card() {
        Household household = seedHousehold();
        createDebt(household.owner(), "Visa", "6180.00", "22.900", "185.00", null);

        assertThat(household.viewer().get("/api/periods/" + MARCH + "/summary").stringField("owed"))
                .isEqualTo("6180.0000");
    }

    // ------------------------------------------------------------------------------- internals

    private UUID createIncome(ApiClient client, String name, String amount) {
        ApiResponse response =
                client.post(
                        "/api/income",
                        """
                        {"name":"%s","amount":"%s",
                         "schedule":{"kind":"MONTHLY","dayOfMonth":28}}
                        """
                                .formatted(name, amount));
        if (response.status() != 201) {
            throw new AssertionError("could not create an income source: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    private UUID createBill(ApiClient client, String name, String amount) {
        ApiResponse response =
                client.post(
                        "/api/bills",
                        """
                        {"name":"%s","expectedAmount":"%s",
                         "schedule":{"kind":"MONTHLY","dayOfMonth":15}}
                        """
                                .formatted(name, amount));
        if (response.status() != 201) {
            throw new AssertionError("could not create a bill: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    /** A bill due once a year, in {@code month}. Eleven months in twelve it projects nothing. */
    private UUID createYearlyBill(ApiClient client, String name, String amount, int month) {
        ApiResponse response =
                client.post(
                        "/api/bills",
                        """
                        {"name":"%s","expectedAmount":"%s",
                         "schedule":{"kind":"YEARLY","dayOfMonth":15,"month":%d}}
                        """
                                .formatted(name, amount, month));
        if (response.status() != 201) {
            throw new AssertionError("could not create a yearly bill: " + response.body());
        }
        return UUID.fromString(response.stringField("id"));
    }

    private long billVersion(Household household, UUID billId) {
        return household
                .owner()
                .get("/api/bills/" + billId + "?period=" + MARCH)
                .numberField("version");
    }
}
