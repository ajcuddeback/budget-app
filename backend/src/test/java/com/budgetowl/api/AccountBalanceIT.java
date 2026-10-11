package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.account.service.AccountTransactions;
import com.budgetowl.common.Money;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * <b>Balance is computed, and the two rules that wait on transactions are reachable.</b>
 *
 * <p>docs/features/accounts.md asks for exactly this and says why: "a test that passes because both
 * fields happen to be read from the same column proves nothing, and will keep passing after
 * transactions make it wrong". Asserting {@code balance == openingBalance} against the shipped
 * configuration is that useless test — it is true today whether the arithmetic exists or not.
 *
 * <p>So this class stands in for slice 5. It replaces {@link AccountTransactions} with an
 * implementation that reports real totals and real history, and then asserts through the HTTP API
 * that
 *
 * <ul>
 *   <li>{@code balance} is {@code openingBalance} <em>plus</em> that total, which is only true if
 *       the service is adding two terms rather than echoing a column,
 *   <li>a currency change on an account with history is {@code 409 account-currency-immutable} —
 *       the check is reached, not merely written,
 *   <li>deleting an account with history is {@code 409 account-not-empty}, and the row survives.
 * </ul>
 *
 * <p><b>This is not mocking something we own and could use for real.</b> There is no transactions
 * table until slice 5 — docs/guides/testing-style.md's rule is about substituting things that
 * exist. What is substituted here is the slice that does not exist yet, through the seam built for
 * it, and the production bean {@code NoTransactionsRecorded} is the truthful implementation for the
 * schema as it actually stands.
 */
class AccountBalanceIT extends ApiTestBase {

    @Autowired private PretendTransactions pretend;

    @BeforeEach
    void forgetWhatThePreviousTestPretended() {
        pretend.clear();
    }

    @Test
    void addsTheTransactionTotalToTheOpeningBalance() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");
        // The fixture opens at 100.0000.
        pretend.record(id, Money.of("-25.5000", "GBP"));

        ApiResponse response = household.owner().get("/api/accounts/" + id);

        assertThat(response.stringField("openingBalance")).isEqualTo("100.0000");
        assertThat(response.stringField("balance"))
                .as("opening plus the sum, not the column read twice")
                .isEqualTo("74.5000");
    }

    @Test
    void addsTheTransactionTotalOnEveryRowOfTheList() {
        // The list builds its views through a batched total, which is a second call site with a
        // second chance to echo the column instead.
        Household household = seedHousehold();
        UUID first = createAccount(household.owner(), "Apple", null);
        UUID second = createAccount(household.owner(), "Banana", null);
        pretend.record(first, Money.of("0.0001", "GBP"));

        String listed = household.owner().get("/api/accounts").body();

        assertThat(listed).contains("\"balance\":\"100.0001\"");
        assertThat(listed)
                .as("an account with no transactions keeps its opening balance")
                .contains("\"balance\":\"100.0000\"");
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void keepsAnArchivedAccountsBalanceCorrect() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Old card", null);
        pretend.record(id, Money.of("-400.0000", "GBP"));
        household.owner().post("/api/accounts/" + id + "/archive", null);

        ApiResponse response = household.owner().get("/api/accounts?includeArchived=true");

        assertThat(response.body()).contains("\"balance\":\"-300.0000\"");
    }

    @Test
    void answers409ToACurrencyChangeOnceTheAccountHasATransaction() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);
        pretend.record(id, Money.of("10.0000", "GBP"));

        ApiResponse response =
                household
                        .owner()
                        .patch("/api/accounts/" + id, "{\"version\":0,\"currency\":\"EUR\"}");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.stringField("code")).isEqualTo("account-currency-immutable");
        assertThat(household.owner().get("/api/accounts/" + id).stringField("currency"))
                .as("refused, not applied, and not a silent no-op")
                .isEqualTo("GBP");
    }

    @Test
    void stillAcceptsTheCurrencyTheAccountAlreadyHasOnceItHasATransaction() {
        // A client patching the whole form back sends the currency it just read. That is not a
        // change and must not be refused, or editing a name becomes impossible after slice 5.
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);
        pretend.record(id, Money.of("10.0000", "GBP"));

        ApiResponse response =
                household
                        .owner()
                        .patch(
                                "/api/accounts/" + id,
                                "{\"version\":0,\"currency\":\"GBP\",\"name\":\"Renamed\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("name")).isEqualTo("Renamed");
    }

    @Test
    void stillAcceptsEverythingElseOnceTheAccountHasATransaction() {
        // Type stays mutable and the opening balance stays correctable: nothing about stored money
        // depends on the type, and fixing a mistyped opening balance is routine bookkeeping.
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", "4412");
        pretend.record(id, Money.of("10.0000", "GBP"));

        ApiResponse response =
                household
                        .owner()
                        .patch(
                                "/api/accounts/" + id,
                                """
                                {"version":0,"type":"CREDIT_CARD","openingBalance":"-400.0000"}
                                """);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.stringField("type")).isEqualTo("CREDIT_CARD");
        assertThat(response.stringField("balance")).isEqualTo("-390.0000");
    }

    @Test
    void answers409ToADeleteOnceTheAccountHasATransaction() {
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);
        pretend.record(id, Money.of("10.0000", "GBP"));

        ApiResponse response = household.owner().delete("/api/accounts/" + id);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.stringField("code")).isEqualTo("account-not-empty");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class))
                .as("financial history is not destroyed by a refused request")
                .isEqualTo(1L);
    }

    @Test
    void refusesADeleteForAnAccountWhoseTransactionsSumToZero() {
        // A credit of 50 against a debit of 50 is an account with history. "The total is zero" and
        // "there are no transactions" are different facts, which is why the seam has two methods.
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);
        pretend.record(id, Money.zero(Currency.getInstance("GBP")));

        ApiResponse response = household.owner().delete("/api/accounts/" + id);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.stringField("code")).isEqualTo("account-not-empty");
    }

    @Test
    void stillArchivesAnAccountThatHasTransactions() {
        // Archiving is the alternative the 409 names, so it had better work.
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Old card", null);
        pretend.record(id, Money.of("10.0000", "GBP"));

        ApiResponse response = household.owner().post("/api/accounts/" + id + "/archive", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"archived\":true");
    }

    @Test
    void asksForTotalsOnlyWithinTheCallersOwnHousehold() {
        // The seam carries the household id for the same reason every repository method does: it
        // has to stay impossible to total an account by id alone (ADR-0017).
        Household household = seedHousehold();
        UUID id = createAccount(household.owner(), "Current", null);

        household.owner().get("/api/accounts/" + id);

        assertThat(pretend.householdsAsked())
                .containsExactly(jdbc.queryForObject("SELECT id FROM households", UUID.class));
    }

    /** Replaces the production bean for this context only. Detected automatically when nested. */
    @TestConfiguration
    static class SliceFiveStandIn {

        @Bean
        @Primary
        PretendTransactions pretendTransactions() {
            return new PretendTransactions();
        }
    }

    /**
     * What slice 5 will do, with the sums supplied by the test instead of by a table.
     *
     * <p>Recording a total also records that the account has history, because that is the only
     * combination a real transactions table can produce: a row exists, and it contributes.
     */
    static final class PretendTransactions implements AccountTransactions {

        private final Map<UUID, Money> totals = new ConcurrentHashMap<>();
        private final Set<UUID> withHistory = ConcurrentHashMap.newKeySet();
        private final Set<UUID> householdsAsked = ConcurrentHashMap.newKeySet();

        void clear() {
            totals.clear();
            withHistory.clear();
            householdsAsked.clear();
        }

        void record(UUID accountId, Money total) {
            totals.put(accountId, total);
            withHistory.add(accountId);
        }

        Set<UUID> householdsAsked() {
            return Set.copyOf(householdsAsked);
        }

        @Override
        public Map<UUID, Money> totalsByAccount(UUID householdId, Collection<UUID> accountIds) {
            householdsAsked.add(householdId);
            Map<UUID, Money> asked = new HashMap<>();
            for (UUID accountId : accountIds) {
                Money total = totals.get(accountId);
                if (total != null) {
                    asked.put(accountId, total);
                }
            }
            return Map.copyOf(asked);
        }

        @Override
        public boolean existForAccount(UUID householdId, UUID accountId) {
            householdsAsked.add(householdId);
            return withHistory.contains(accountId);
        }
    }
}
