package com.budgetowl.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

/**
 * V17's constraints, exercised against the database rather than against the service that is
 * supposed to respect them.
 *
 * <p>A separate class from {@link SchemaConstraintsIT}, which is organised entirely around the
 * membership and login tables and carries helpers for them. Debts need a different fixture — two
 * households, each with an account, a bill, an occurrence and a transaction — and the per-feature
 * shape is the one {@link TransferPairConstraintIT} already established in this package.
 *
 * <p>Every row below is written in <b>plain SQL</b>. Nothing here goes through a repository or a
 * service, which is the point: a case that only failed through the service would prove the service,
 * and the constraint is what still holds when a future importer, a hand-run {@code UPDATE} or a
 * caller nobody has written yet writes to these tables.
 *
 * <p>Two of these tests prove a constraint's deliberate <b>absence</b> — a zero or negative {@code
 * anchor_balance} — and they are the one kind here that cannot fail by a constraint being removed.
 * They fail when one is <i>added</i>, which is exactly the regression the feature doc asks to be
 * guarded against.
 */
@SpringBootTest
class DebtSchemaConstraintsIT extends PersistenceTestBase {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 1);

    private UUID household;
    private UUID account;
    private UUID bill;
    private UUID occurrence;
    private UUID ledgerRow;

    private UUID otherHousehold;
    private UUID otherAccount;
    private UUID otherBill;
    private UUID otherOccurrence;
    private UUID otherLedgerRow;

    @BeforeEach
    void seedTwoHouseholdsWithSomethingToPointAt() {
        // uq_households_singleton (V3) permits one household row (ADR-0026), and a scoping rule
        // cannot be proved against a world with only one household in it. Same shape, and the
        // same restoration in @AfterEach, as TransferPairConstraintIT and MoneyCrossHouseholdIT.
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");

        household = UUID.randomUUID();
        account = UUID.randomUUID();
        bill = UUID.randomUUID();
        occurrence = UUID.randomUUID();
        ledgerRow = UUID.randomUUID();

        otherHousehold = UUID.randomUUID();
        otherAccount = UUID.randomUUID();
        otherBill = UUID.randomUUID();
        otherOccurrence = UUID.randomUUID();
        otherLedgerRow = UUID.randomUUID();

        transaction.executeWithoutResult(
                status -> {
                    seedHousehold(household, "ada@example.com", "Ada and Grace");
                    seedHousehold(otherHousehold, "mallory@example.com", "Somebody else");

                    insertAccount(account, household, "Current");
                    insertAccount(otherAccount, otherHousehold, "Current");

                    insertBill(bill, household, "Car loan");
                    insertBill(otherBill, otherHousehold, "Their car loan");

                    insertOccurrence(occurrence, household, bill);
                    insertOccurrence(otherOccurrence, otherHousehold, otherBill);

                    insertTransaction(ledgerRow, household, account);
                    insertTransaction(otherLedgerRow, otherHousehold, otherAccount);
                });
    }

    @AfterEach
    void restoreTheSingletonIndex() {
        // Both halves matter. Leaving the index off would run every later test in this JVM without
        // ADR-0026's guarantee, and the index cannot be recreated while two households exist.
        jdbc.update("DELETE FROM households");
        jdbc.execute("DROP INDEX IF EXISTS uq_households_singleton");
        jdbc.execute("CREATE UNIQUE INDEX uq_households_singleton ON households ((true))");
    }

    // ------------------------------------------------------------- the degrees-of-freedom rule

    @Test
    void acceptsTwoOfTheThreeSolverSlots() {
        // A rate and a payment entered, the horizon solved. The ordinary case, and the half of the
        // rule that a constraint written too tightly would break.
        assertThatCode(() -> insertDebt(aDebt().apr("22.900").payment("445.0000")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAPaymentAndAHorizonWithNoRate() {
        // The installment loan: the statement gives a balance, a payment and a term and never
        // names the rate, so `apr` is NULL and solved.
        assertThatCode(() -> insertDebt(aDebt().payment("342.0000").monthsLeft(36)))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAllThreeSolverSlotsAtOnce() {
        // Three entered values over-determine one equation. The answer to that is
        // `422 terms-over-determined` at the edge; here it is simply unwritable, so no caller can
        // store a set the solver has no defined target for.
        assertThatThrownBy(
                        () -> insertDebt(aDebt().apr("22.900").payment("445.0000").monthsLeft(31)))
                .hasMessageContaining("ck_debts_degrees_of_freedom");
    }

    @Test
    void refusesAllThreeWhenTheHorizonIsAPayoffMonth() {
        // The same refusal through the horizon's other face, because `payoff_month` and
        // `months_left` are the same slot.
        assertThatThrownBy(
                        () ->
                                insertDebt(
                                        aDebt().apr("22.900")
                                                .payment("445.0000")
                                                .payoffMonth(LocalDate.of(2027, 4, 1))))
                .hasMessageContaining("ck_debts_degrees_of_freedom");
    }

    @Test
    void refusesMonthsLeftAndAPayoffMonthTogetherEvenThoughThatIsOnlyTwo() {
        // Counting columns would allow this: two non-null fields out of four. It is still refused,
        // because the two are ONE unknown asked in different units — and two stored answers to one
        // question can disagree, at which point the page and the chart contradict each other.
        assertThatThrownBy(
                        () ->
                                insertDebt(
                                        aDebt().monthsLeft(31)
                                                .payoffMonth(LocalDate.of(2027, 4, 1))))
                .hasMessageContaining("ck_debts_degrees_of_freedom");
    }

    @Test
    void refusesARateWithBothFacesOfTheHorizon() {
        assertThatThrownBy(
                        () ->
                                insertDebt(
                                        aDebt().apr("22.900")
                                                .monthsLeft(31)
                                                .payoffMonth(LocalDate.of(2027, 4, 1))))
                .hasMessageContaining("ck_debts_degrees_of_freedom");
    }

    @Test
    void acceptsAPlainDebtWithNothingButARate() {
        // What onboarding creates from a balance, an APR and a minimum: one entered solver field,
        // and the plan's allocation supplies the payment on read.
        assertThatCode(() -> insertDebt(aDebt().apr("0.000"))).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------------------- the rate

    @Test
    void acceptsZeroPercent() {
        // A real promotional and car-loan rate. Refusing it would make the commonest 0% case
        // unrecordable, and zero is not the same claim as "solved".
        assertThatCode(() -> insertDebt(aDebt().apr("0.000"))).doesNotThrowAnyException();
    }

    @Test
    void acceptsAPaydayRateAboveOneHundredPercent() {
        // A household that cannot record its worst debt is worse off than one looking at an
        // alarming number.
        assertThatCode(() -> insertDebt(aDebt().apr("664.000"))).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT max(apr) FROM debts", BigDecimal.class))
                .isEqualByComparingTo("664.000");
    }

    @Test
    void refusesANegativeRate() {
        // Negative interest is not a rate a lender charges, and it would make the recurrence run
        // backwards: a balance that falls on its own, with no payment.
        assertThatThrownBy(() -> insertDebt(aDebt().apr("-0.001")))
                .hasMessageContaining("ck_debts_apr_range");
    }

    @Test
    void acceptsTheHighestRateTheColumnCanHold() {
        // 999.999 is where numeric(6,3) stops, which is where the upper bound was taken from.
        assertThatCode(() -> insertDebt(aDebt().apr("999.999"))).doesNotThrowAnyException();
    }

    @Test
    void refusesARateOfAThousandPercentOrMore() {
        // Refused by numeric(6,3) itself — "a field with precision 6, scale 3 must round to an
        // absolute value less than 10^3" — which is why this asserts the refusal and not the
        // constraint name. The CHECK's upper half is a backstop for the day somebody widens the
        // column; the lower half, proved above, is the one the column cannot express.
        assertThatThrownBy(() -> insertDebt(aDebt().apr("1000.000")))
                .isInstanceOf(DataAccessException.class);
    }

    // --------------------------------------------------------------- minimums and the anchor

    @Test
    void refusesANegativeMinimumPayment() {
        assertThatThrownBy(() -> insertDebt(aDebt().minimumPayment("-1.0000")))
                .hasMessageContaining("ck_debts_minimum_payment_not_negative");
    }

    @Test
    void acceptsAZeroMinimumPayment() {
        // A promotional period with nothing due. Legal, and the plan allocates it nothing.
        assertThatCode(() -> insertDebt(aDebt().minimumPayment("0.0000")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAConfirmedBalanceOfZero() {
        // DELIBERATELY unconstrained. A confirmed zero is how a debt is paid off; a constraint
        // demanding a positive balance would make clearing a debt impossible to record. This test
        // fails if somebody "tidies up" by adding one.
        assertThatCode(() -> insertDebt(aDebt().anchorBalance("0.0000")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsANegativeBalanceBecauseThatIsWhatAnOverpaymentLooksLike() {
        // Also deliberately unconstrained, and for the sharper reason: clamping would hide a real
        // error in the household's favour. The screen says the debt is overpaid by that much.
        assertThatCode(() -> insertDebt(aDebt().anchorBalance("-118.4400")))
                .doesNotThrowAnyException();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM debts WHERE anchor_balance < 0", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void refusesAnOriginalBalanceOfZero() {
        // Progress is (original - balance) / original, so zero is a division by zero in the
        // headline figure. Absent is the honest value and the screen shows no progress at all.
        assertThatThrownBy(() -> insertDebt(aDebt().originalBalance("0.0000")))
                .hasMessageContaining("ck_debts_original_balance_positive");
    }

    @Test
    void refusesAnEnteredPaymentOfZero() {
        assertThatThrownBy(() -> insertDebt(aDebt().payment("0.0000")))
                .hasMessageContaining("ck_debts_payment_positive");
    }

    @Test
    void refusesAHorizonOfZeroMonths() {
        assertThatThrownBy(() -> insertDebt(aDebt().monthsLeft(0)))
                .hasMessageContaining("ck_debts_months_left_positive");
    }

    @Test
    void refusesAPayoffMonthThatIsNotTheFirstOfAMonth() {
        // `payoff_month` is a YearMonth in a `date` column, and that only holds while every row
        // agrees which day stands for the month.
        assertThatThrownBy(() -> insertDebt(aDebt().payoffMonth(LocalDate.of(2027, 4, 12))))
                .hasMessageContaining("ck_debts_payoff_month_is_first_of_month");
    }

    // ----------------------------------------------------------------- the small closed sets

    @Test
    void refusesAKindOutsideTheTwo() {
        assertThatThrownBy(() -> insertDebt(aDebt().kind("MORTGAGE")))
                .hasMessageContaining("ck_debts_kind");
    }

    @Test
    void refusesABalanceSourceOutsideTheThree() {
        assertThatThrownBy(() -> insertDebt(aDebt().balanceSource("GUESSED")))
                .hasMessageContaining("ck_debts_balance_source");
    }

    @Test
    void acceptsEachBalanceSourceIncludingTheOneSliceTenCannotReach() {
        // LINKED_STATEMENT needs a connected account (slice 16). The value is legal now so that
        // nothing has to be backfilled when it arrives.
        for (String source : new String[] {"CONFIRMED", "ESTIMATED", "LINKED_STATEMENT"}) {
            assertThatCode(() -> insertDebt(aDebt().balanceSource(source)))
                    .as(source)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void refusesANegativeCustomOrder() {
        // The CUSTOM strategy's ladder position. Negative would sort a debt above rung 1, which
        // is not a rung.
        assertThatThrownBy(() -> insertDebt(aDebt().customOrder(-1)))
                .hasMessageContaining("ck_debts_custom_order_not_negative");
    }

    @Test
    void refusesANoteThatIsAnEmptyStringRatherThanAbsent() {
        UUID debt = insertDebt(aDebt());

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO debt_payments (id, household_id, debt_id,
                                            paid_date, amount, interest, principal, balance_after,
                                            provenance, note)
                                        VALUES (?, ?, ?, DATE '2026-10-01', 100.0000, 0.0000,
                                                100.0000, 0.0000, 'USER_LOGGED', '   ')
                                        """,
                                        UUID.randomUUID(),
                                        household,
                                        debt))
                .hasMessageContaining("ck_debt_payments_note");
    }

    @Test
    void refusesACurrencyThatIsNotAnIso4217Code() {
        assertThatThrownBy(() -> insertDebt(aDebt().currency("gbp")))
                .hasMessageContaining("ck_debts_currency");
    }

    @Test
    void refusesABlankName() {
        assertThatThrownBy(() -> insertDebt(aDebt().name("   ")))
                .hasMessageContaining("ck_debts_name_length");
    }

    @Test
    void refusesAServicerThatIsAnEmptyStringRatherThanAbsent() {
        assertThatThrownBy(() -> insertDebt(aDebt().servicer("")))
                .hasMessageContaining("ck_debts_servicer");
    }

    @Test
    void allowsTwoDebtsWithTheSameName() {
        // Asserted rather than assumed: the plan's tie-break is "APR, then balance, then name,
        // then id", which only has a reason to exist because names may repeat. A unique index on
        // (household_id, lower(name)) — which bills and income sources do have — would make a
        // household with two Visa cards unable to record the second.
        insertDebt(aDebt().name("Visa").apr("22.900"));

        assertThatCode(() -> insertDebt(aDebt().name("Visa").apr("18.400")))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------- household scoping, declaratively

    @Test
    void refusesADebtBoundToAnotherHouseholdsAccount() {
        // The threat model's first live attack: a POST carrying somebody else's accountId, which
        // would leak that account's existence and currency through the attacker's own debt screen.
        // The service resolves the id through the membership query; this is the layer underneath
        // it, which a future caller cannot forget (ADR-0017).
        assertThatThrownBy(() -> insertDebt(aDebt().account(otherAccount)))
                .hasMessageContaining("fk_debts_account");
    }

    @Test
    void refusesADebtLinkedToAnotherHouseholdsBill() {
        assertThatThrownBy(() -> insertDebt(aDebt().bill(otherBill)))
                .hasMessageContaining("fk_debts_bill");
    }

    @Test
    void acceptsADebtBoundToItsOwnHouseholdsAccountAndBill() {
        assertThatCode(() -> insertDebt(aDebt().account(account).bill(bill)))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAPaymentAgainstAnotherHouseholdsDebt() {
        UUID theirDebt = insertDebt(aDebt().household(otherHousehold));

        assertThatThrownBy(() -> insertPayment(theirDebt, household, "118.4400", null, null))
                .hasMessageContaining("fk_debt_payments_debt");
    }

    @Test
    void refusesAPaymentLinkedToAnotherHouseholdsTransaction() {
        // The threat model's second attack: a payment row carrying a foreign transactionId
        // discloses that transaction's amount and date.
        UUID debt = insertDebt(aDebt());

        assertThatThrownBy(() -> insertPayment(debt, household, "118.4400", otherLedgerRow, null))
                .hasMessageContaining("fk_debt_payments_transaction");
    }

    @Test
    void refusesAPaymentLinkedToAnotherHouseholdsBillOccurrence() {
        UUID debt = insertDebt(aDebt());

        assertThatThrownBy(() -> insertPayment(debt, household, "118.4400", null, otherOccurrence))
                .hasMessageContaining("fk_debt_payments_bill_occurrence");
    }

    // ------------------------------------------------------------------- the split reconciles

    @Test
    void refusesASplitThatDoesNotAddUpToTheAmount() {
        // The defect nobody would see: both figures look right on screen and only their sum is
        // wrong, so a cent appears or vanishes from the balance chain with nothing to point at.
        UUID debt = insertDebt(aDebt());

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO debt_payments (id, household_id, debt_id,
                                            paid_date, amount, interest, principal, balance_after,
                                            provenance)
                                        VALUES (?, ?, ?, DATE '2026-10-01', 118.4400, 117.9400,
                                                1.0000, 6179.0000, 'USER_LOGGED')
                                        """,
                                        UUID.randomUUID(),
                                        household,
                                        debt))
                .hasMessageContaining("ck_debt_payments_split_reconciles");
    }

    @Test
    void acceptsASplitThatReconcilesToTheCent() {
        UUID debt = insertDebt(aDebt());

        assertThatCode(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO debt_payments (id, household_id, debt_id,
                                            paid_date, amount, interest, principal, balance_after,
                                            provenance)
                                        VALUES (?, ?, ?, DATE '2026-10-01', 118.4400, 117.9350,
                                                0.5050, 6179.4950, 'USER_LOGGED')
                                        """,
                                        UUID.randomUUID(),
                                        household,
                                        debt))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsANegativeStatementCorrectionAndAnOverpaidBalance() {
        // Both directions the sign checks would have forbidden: a correction that RAISES the
        // balance ("Statement corrected +$4.10"), and a balance_after below zero because the
        // payment overpaid. Neither is clamped anywhere in the product.
        UUID debt = insertDebt(aDebt());

        assertThatCode(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO debt_payments (id, household_id, debt_id,
                                            paid_date, amount, interest, principal, balance_after,
                                            provenance)
                                        VALUES (?, ?, ?, DATE '2026-10-01', -4.1000, 0.0000,
                                                -4.1000, -12.5000, 'STATEMENT_CORRECTION')
                                        """,
                                        UUID.randomUUID(),
                                        household,
                                        debt))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAProvenanceOutsideTheThree() {
        UUID debt = insertDebt(aDebt());

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO debt_payments (id, household_id, debt_id,
                                            paid_date, amount, interest, principal, balance_after,
                                            provenance)
                                        VALUES (?, ?, ?, DATE '2026-10-01', 100.0000, 0.0000,
                                                100.0000, 0.0000, 'GUESSED')
                                        """,
                                        UUID.randomUUID(),
                                        household,
                                        debt))
                .hasMessageContaining("ck_debt_payments_provenance");
    }

    // ---------------------------------------------------------------------------- uniqueness

    @Test
    void refusesTwoPaymentsSettledByTheSameTransaction() {
        // ADR-0031's argument about one transaction and one envelope entry. One charge counted
        // against two debts would understate both balances — plausibly, which is why nobody would
        // notice.
        UUID debt = insertDebt(aDebt());
        UUID second = insertDebt(aDebt());
        insertPayment(debt, household, "118.4400", ledgerRow, null);

        assertThatThrownBy(() -> insertPayment(second, household, "118.4400", ledgerRow, null))
                .hasMessageContaining("ux_debt_payments_transaction_id");
    }

    @Test
    void allowsManyPaymentsWithNoTransactionAtAll() {
        // The partial predicate. Most payments are logged by hand and carry no link; a plain
        // unique index would have allowed exactly one of them per instance.
        UUID debt = insertDebt(aDebt());
        insertPayment(debt, household, "118.4400", null, null);

        assertThatCode(() -> insertPayment(debt, household, "118.4400", null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesTwoPaymentsAgainstTheSameBillOccurrence() {
        // The prompt on marking a bill paid must not be able to record the same month's car
        // payment twice.
        UUID debt = insertDebt(aDebt());
        insertPayment(debt, household, "342.0000", null, occurrence);

        assertThatThrownBy(() -> insertPayment(debt, household, "342.0000", null, occurrence))
                .hasMessageContaining("ux_debt_payments_bill_occurrence_id");
    }

    @Test
    void refusesTwoDebtsLinkedToTheSameBill() {
        // What makes "each minimum counted once" an invariant rather than a convention: two debts
        // on one bill row would subtract the same minimum twice from what's left.
        insertDebt(aDebt().bill(bill));

        assertThatThrownBy(() -> insertDebt(aDebt().bill(bill)))
                .hasMessageContaining("ux_debts_bill_id");
    }

    @Test
    void allowsManyDebtsWithNoBill() {
        insertDebt(aDebt());

        assertThatCode(() -> insertDebt(aDebt())).doesNotThrowAnyException();
    }

    @Test
    void refusesASecondPayoffPlanForTheSameHousehold() {
        // ADR-0026, and why /api/debt-plan is singular. A second row is a second answer to "what
        // is my strategy", which the endpoint would then have to pick between silently.
        insertPlan(household, "AVALANCHE", "560.0000");

        assertThatThrownBy(() -> insertPlan(household, "SNOWBALL", "560.0000"))
                .hasMessageContaining("uq_payoff_plans_household");
    }

    @Test
    void acceptsOnePlanPerHousehold() {
        insertPlan(household, "AVALANCHE", "560.0000");

        assertThatCode(() -> insertPlan(otherHousehold, "SNOWBALL", "100.0000"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAMonthlyAmountBelowTheMinimumsAndRefusesANegativeOne() {
        // A household that genuinely cannot meet its minimums exists, and refusing their plan here
        // or at the edge tells somebody in trouble that their situation is invalid input. Negative
        // is not the same claim: it is not an amount.
        assertThatCode(() -> insertPlan(household, "AVALANCHE", "1.0000"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> insertPlan(otherHousehold, "AVALANCHE", "-1.0000"))
                .hasMessageContaining("ck_payoff_plans_monthly_amount_not_negative");
    }

    @Test
    void refusesAStrategyOutsideTheThree() {
        assertThatThrownBy(() -> insertPlan(household, "HIGHEST_BALANCE_FIRST", "560.0000"))
                .hasMessageContaining("ck_payoff_plans_strategy");
    }

    @Test
    void refusesASnapshotDebtFreeMonthThatIsNotTheFirstOfAMonth() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO payoff_plan_snapshots (id, household_id,
                                            strategy, monthly_amount, currency, debt_free_month,
                                            total_interest)
                                        VALUES (?, ?, 'AVALANCHE', 560.0000, 'GBP',
                                                DATE '2029-04-12', 4210.0000)
                                        """,
                                        UUID.randomUUID(),
                                        household))
                .hasMessageContaining("ck_payoff_plan_snapshots_debt_free_month_is_first_of_month");
    }

    @Test
    void acceptsASnapshotWithNoDebtFreeMonthAtAll() {
        // A plan whose monthly amount is below the minimums, or that contains a debt which never
        // clears, has no debt-free month. NULL is the honest answer; a zero or a date of today
        // would read as "debt-free, this month".
        assertThatCode(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO payoff_plan_snapshots (id, household_id,
                                            strategy, monthly_amount, currency)
                                        VALUES (?, ?, 'SNOWBALL', 100.0000, 'GBP')
                                        """,
                                        UUID.randomUUID(),
                                        household))
                .doesNotThrowAnyException();
    }

    // ----------------------------------------------------- what deleting things does, and does not

    @Test
    void deletingTheLinkedTransactionLeavesThePaymentStandingWithItsLinkCleared() {
        // ADR-0035, and the whole reason the foreign key names its column. With a bare
        // ON DELETE SET NULL this DELETE would fail instead — PostgreSQL would try to null
        // household_id too, which is NOT NULL — so the assertion that the delete SUCCEEDS is as
        // much a part of the rule as the row surviving it.
        UUID debt = insertDebt(aDebt());
        UUID payment = insertPayment(debt, household, "118.4400", ledgerRow, null);

        assertThatCode(() -> jdbc.update("DELETE FROM transactions WHERE id = ?", ledgerRow))
                .doesNotThrowAnyException();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM debt_payments WHERE id = ?",
                                Long.class,
                                payment))
                .isEqualTo(1L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT transaction_id FROM debt_payments WHERE id = ?",
                                UUID.class,
                                payment))
                .isNull();
        // And the split is untouched: the debt balance does not move because a ledger row was
        // tidied up.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT amount FROM debt_payments WHERE id = ?",
                                BigDecimal.class,
                                payment))
                .isEqualByComparingTo("118.4400");
    }

    @Test
    void deletingTheLinkedBillLeavesTheDebtWithItsMinimumAndNoLink() {
        // The surprising-but-correct consequence: that minimum starts counting into the Bills &
        // Income committed figure, so the committed total RISES when a bill is deleted.
        UUID debt = insertDebt(aDebt().bill(bill).minimumPayment("342.0000"));

        assertThatCode(() -> jdbc.update("DELETE FROM bills WHERE id = ?", bill))
                .doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT bill_id FROM debts WHERE id = ?", UUID.class, debt))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT minimum_payment FROM debts WHERE id = ?",
                                BigDecimal.class,
                                debt))
                .isEqualByComparingTo("342.0000");
    }

    @Test
    void deletingADebtTakesItsPaymentsAndNoTransactions() {
        UUID debt = insertDebt(aDebt());
        insertPayment(debt, household, "118.4400", ledgerRow, null);

        jdbc.update("DELETE FROM debts WHERE id = ?", debt);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM debt_payments", Long.class)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM transactions WHERE id = ?",
                                Long.class,
                                ledgerRow))
                .isEqualTo(1L);
    }

    @Test
    void deletingAnAccountDetachesTheDebtRatherThanRefusing() {
        // An account can be deleted while it is empty (accounts.md), and that must not require
        // unpicking a debt first. The debt keeps everything except the link.
        UUID debt = insertDebt(aDebt().account(account));
        // Empty first: fk_transactions_account is RESTRICT, which is how accounts.md's
        // "deletion only while the account has no transactions" rule is expressed in the schema.
        jdbc.update("DELETE FROM transactions WHERE id = ?", ledgerRow);

        assertThatCode(() -> jdbc.update("DELETE FROM accounts WHERE id = ?", account))
                .doesNotThrowAnyException();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT account_id FROM debts WHERE id = ?", UUID.class, debt))
                .isNull();
    }

    @Test
    void deletingAHouseholdTakesItsDebtsPaymentsPlanAndSnapshots() {
        // docs/guides/database-style.md: deleting a household removes all of its financial data,
        // with no orphaned rows.
        UUID debt = insertDebt(aDebt());
        insertPayment(debt, household, "118.4400", null, null);
        insertPlan(household, "AVALANCHE", "560.0000");
        jdbc.update(
                """
                INSERT INTO payoff_plan_snapshots (id, household_id, strategy, monthly_amount,
                    currency, debt_free_month, total_interest)
                VALUES (?, ?, 'AVALANCHE', 560.0000, 'GBP', DATE '2029-04-01', 4210.0000)
                """,
                UUID.randomUUID(),
                household);

        jdbc.update("DELETE FROM households WHERE id = ?", household);

        assertThat(countIn("debts")).isZero();
        assertThat(countIn("debt_payments")).isZero();
        assertThat(countIn("payoff_plans")).isZero();
        assertThat(countIn("payoff_plan_snapshots")).isZero();
    }

    // ------------------------------------------------------------------------------ the types

    @Test
    void everyMoneyColumnIsNumericAtTheScaleAdr0006Requires() {
        // ADR-0006, checked against the database rather than against the entities that will be
        // written on top of it. A `double precision` balance is a defect regardless of what was
        // asked for, and NUMERIC(19,2) would silently truncate the fourth decimal that the
        // interest step needs.
        List<String> wrong =
                jdbc.queryForList(
                        """
                        SELECT table_name || '.' || column_name || ' is ' || data_type
                               || '(' || coalesce(numeric_precision, 0) || ','
                               || coalesce(numeric_scale, 0) || ')'
                          FROM information_schema.columns
                         WHERE table_schema = 'public'
                           AND table_name IN ('debts', 'debt_payments', 'payoff_plans',
                                              'payoff_plan_snapshots')
                           AND column_name IN ('minimum_payment', 'original_balance',
                                               'anchor_balance', 'payment', 'amount', 'interest',
                                               'principal', 'balance_after', 'monthly_amount',
                                               'total_interest')
                           AND NOT (data_type = 'numeric'
                                    AND numeric_precision = 19 AND numeric_scale = 4)
                        """,
                        String.class);

        assertThat(wrong).isEmpty();
    }

    @Test
    void theRateIsNumericWithThreeDecimalPlaces() {
        // 22.900 is what a statement prints, and the solver recovers a rate to 0.001 percentage
        // points — a narrower scale would make a solved APR unstorable at its stated tolerance.
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT data_type || '(' || numeric_precision || ','
                                       || numeric_scale || ')'
                                  FROM information_schema.columns
                                 WHERE table_schema = 'public' AND table_name = 'debts'
                                   AND column_name = 'apr'
                                """,
                                String.class))
                .isEqualTo("numeric(6,3)");
    }

    // ------------------------------------------------------------------------------- internals

    private Long countIn(String table) {
        // The table name is a literal from this file and never from input; every value below is a
        // bound parameter (docs/guides/database-style.md).
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void seedHousehold(UUID id, String email, String name) {
        UUID owner = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, display_name) VALUES (?, ?, 'Someone')",
                owner,
                email);
        jdbc.update(
                "INSERT INTO households (id, name, base_currency) VALUES (?, ?, 'GBP')", id, name);
        jdbc.update(
                """
                INSERT INTO household_members (id, household_id, user_id, role)
                VALUES (?, ?, ?, 'OWNER')
                """,
                UUID.randomUUID(),
                id,
                owner);
    }

    private void insertAccount(UUID id, UUID inHousehold, String name) {
        jdbc.update(
                """
                INSERT INTO accounts (id, household_id, name, type, currency, opening_balance)
                VALUES (?, ?, ?, 'CREDIT_CARD', 'GBP', 0.0000)
                """,
                id,
                inHousehold,
                name);
    }

    private void insertBill(UUID id, UUID inHousehold, String name) {
        jdbc.update(
                """
                INSERT INTO bills (id, household_id, name, expected_amount, schedule,
                                   day_of_month)
                VALUES (?, ?, ?, 342.0000, 'MONTHLY', 12)
                """,
                id,
                inHousehold,
                name);
    }

    private void insertOccurrence(UUID id, UUID inHousehold, UUID forBill) {
        jdbc.update(
                """
                INSERT INTO bill_occurrences (id, household_id, bill_id, period, due_date,
                                              expected_amount, status, actual_amount, paid_date)
                VALUES (?, ?, ?, DATE '2026-10-01', DATE '2026-10-12', 342.0000, 'PAID',
                        342.0000, DATE '2026-10-12')
                """,
                id,
                inHousehold,
                forBill);
    }

    private void insertTransaction(UUID id, UUID inHousehold, UUID inAccount) {
        jdbc.update(
                """
                INSERT INTO transactions (id, household_id, account_id, date, amount)
                VALUES (?, ?, ?, DATE '2026-10-12', -118.4400)
                """,
                id,
                inHousehold,
                inAccount);
    }

    private UUID insertPayment(
            UUID debt, UUID inHousehold, String amount, UUID transactionId, UUID occurrenceId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO debt_payments (id, household_id, debt_id, paid_date, amount, interest,
                    principal, balance_after, provenance, transaction_id, bill_occurrence_id)
                VALUES (?, ?, ?, DATE '2026-10-12', CAST(? AS numeric), 0.0000,
                        CAST(? AS numeric), 0.0000, 'USER_LOGGED', ?, ?)
                """,
                id,
                inHousehold,
                debt,
                amount,
                amount,
                transactionId,
                occurrenceId);
        return id;
    }

    private void insertPlan(UUID inHousehold, String strategy, String monthlyAmount) {
        jdbc.update(
                """
                INSERT INTO payoff_plans (id, household_id, strategy, monthly_amount)
                VALUES (?, ?, ?, CAST(? AS numeric))
                """,
                UUID.randomUUID(),
                inHousehold,
                strategy,
                monthlyAmount);
    }

    /** A debt that satisfies every constraint, so each test can break exactly one thing. */
    private DebtRow aDebt() {
        return new DebtRow(household);
    }

    private UUID insertDebt(DebtRow row) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO debts (id, household_id, name, kind, servicer, currency, apr,
                    minimum_payment, original_balance, anchor_balance, anchor_date, balance_source,
                    payment, months_left, payoff_month, custom_order, account_id, bill_id)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS numeric), CAST(? AS numeric),
                        CAST(? AS numeric), CAST(? AS numeric), ?, ?, CAST(? AS numeric), ?, ?,
                        ?, ?, ?)
                """,
                id,
                row.household,
                row.name,
                row.kind,
                row.servicer,
                row.currency,
                row.apr,
                row.minimumPayment,
                row.originalBalance,
                row.anchorBalance,
                ANCHOR,
                row.balanceSource,
                row.payment,
                row.monthsLeft,
                row.payoffMonth,
                row.customOrder,
                row.account,
                row.bill);
        return id;
    }

    /**
     * The column values for one {@code debts} row. Mutable and fluent on purpose: a test reads as
     * "a valid debt, except for this one thing", which is the only shape that proves one constraint
     * at a time.
     */
    private static final class DebtRow {
        private UUID household;
        private String name = "Visa";
        private String kind = "CREDIT_CARD";
        private String servicer = "Chase";
        private String currency = "GBP";
        private String apr;
        private String minimumPayment = "118.0000";
        private String originalBalance;
        private String anchorBalance = "6180.0000";
        private String balanceSource = "CONFIRMED";
        private String payment;
        private Integer monthsLeft;
        private LocalDate payoffMonth;
        private Integer customOrder;
        private UUID account;
        private UUID bill;

        private DebtRow(UUID household) {
            this.household = household;
        }

        private DebtRow household(UUID value) {
            this.household = value;
            return this;
        }

        private DebtRow name(String value) {
            this.name = value;
            return this;
        }

        private DebtRow kind(String value) {
            this.kind = value;
            return this;
        }

        private DebtRow servicer(String value) {
            this.servicer = value;
            return this;
        }

        private DebtRow currency(String value) {
            this.currency = value;
            return this;
        }

        private DebtRow apr(String value) {
            this.apr = value;
            return this;
        }

        private DebtRow minimumPayment(String value) {
            this.minimumPayment = value;
            return this;
        }

        private DebtRow originalBalance(String value) {
            this.originalBalance = value;
            return this;
        }

        private DebtRow anchorBalance(String value) {
            this.anchorBalance = value;
            return this;
        }

        private DebtRow balanceSource(String value) {
            this.balanceSource = value;
            return this;
        }

        private DebtRow payment(String value) {
            this.payment = value;
            return this;
        }

        private DebtRow monthsLeft(Integer value) {
            this.monthsLeft = value;
            return this;
        }

        private DebtRow customOrder(Integer value) {
            this.customOrder = value;
            return this;
        }

        private DebtRow payoffMonth(LocalDate value) {
            this.payoffMonth = value;
            return this;
        }

        private DebtRow account(UUID value) {
            this.account = value;
            return this;
        }

        private DebtRow bill(UUID value) {
            this.bill = value;
            return this;
        }
    }
}
