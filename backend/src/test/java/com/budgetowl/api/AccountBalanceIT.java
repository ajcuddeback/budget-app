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
 * <p><b>Why a substitute now that transactions are real.</b> This class drives the balance
 * arithmetic and the two {@code 409}s through cases a real table makes laborious to set up — a
 * zero-sum account that still has history, an account asked about but absent from the totals map,
 * the batching assertion that the list endpoint asks once rather than once per row. {@code
 * TransactionIT} covers the same rules end to end against real rows — {@code
 * recording_a_transaction_changes_the_accounts_balance}, {@code
 * an_account_with_transactions_can_no_longer_be_deleted_or_re_denominated} and {@code
 * an_account_whose_transactions_net_to_zero_still_has_history} — which is what proves {@code
 * PostedTransactions} agrees with what is pretended here. Without those, this file would be
 * asserting a contract nothing implements.
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

    @Test
    void reportsAnAccountWithNoTransactionsAsEmpty() {
        Household household = seedHousehold();
        ApiClient owner = household.owner();
        UUID id = createAccount(owner, "Current", null);

        assertThat(owner.get("/api/accounts/" + id).body())
                .as("a fresh account can be deleted, and its currency is still free to change")
                .contains("\"empty\":true");
    }

    @Test
    void doesNotCallAnAccountEmptyJustBecauseItsTransactionsNetToZero() {
        Household household = seedHousehold();
        ApiClient owner = household.owner();
        UUID id = createAccount(owner, "Current", null);
        // A +50 and a -50. The balance is untouched; the history is not.
        pretend.recordOffsettingHistory(id, Money.zero(java.util.Currency.getInstance("GBP")));

        ApiResponse response = owner.get("/api/accounts/" + id);

        // This is the whole reason `empty` is its own field rather than a balance comparison.
        // Deriving it from the amounts would delete an account that has records in it.
        assertThat(response.body()).contains("\"empty\":false");
        assertThat(response.stringField("balance"))
                .as("and the balance is genuinely unchanged, so nothing else could have told us")
                .isEqualTo(response.stringField("openingBalance"));
    }

    @Test
    void asksAboutEveryAccountInTheListAtOnce() {
        Household household = seedHousehold();
        ApiClient owner = household.owner();
        createAccount(owner, "One", null);
        createAccount(owner, "Two", null);
        createAccount(owner, "Three", null);

        // Batched: the list must not ask per row, or it becomes an N+1 the day slice 5 supplies a
        // real implementation. Proven by the stand-in recording one call per household, not per id.
        pretend.clearCalls();
        owner.get("/api/accounts");

        assertThat(pretend.historyCallCount())
                .as("one history query for the whole page")
                .isEqualTo(1);
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
     * What {@code PostedTransactions} does, with the sums supplied by the test instead of a table.
     *
     * <p>Recording a total also records that the account has history, because that is the only
     * combination a real transactions table can produce: a row exists, and it contributes.
     */
    static final class PretendTransactions implements AccountTransactions {

        private final Map<UUID, Money> totals = new ConcurrentHashMap<>();
        private final Set<UUID> withHistory = ConcurrentHashMap.newKeySet();
        private final Set<UUID> householdsAsked = ConcurrentHashMap.newKeySet();
        private final java.util.concurrent.atomic.AtomicInteger historyCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        void clearCalls() {
            historyCalls.set(0);
        }

        int historyCallCount() {
            return historyCalls.get();
        }

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

        @Override
        public Set<UUID> withHistory(UUID householdId, Collection<UUID> accountIds) {
            householdsAsked.add(householdId);
            historyCalls.incrementAndGet();
            Set<UUID> asked = new java.util.HashSet<>(accountIds);
            asked.retainAll(withHistory);
            return Set.copyOf(asked);
        }

        /** History that nets to nothing: a +50 and a -50, which is still history. */
        void recordOffsettingHistory(UUID accountId, Money zero) {
            totals.put(accountId, zero);
            withHistory.add(accountId);
        }
    }
}
