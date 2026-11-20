package com.budgetowl.account.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.domain.AccountType;
import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.persistence.UserAccountRepository;
import com.budgetowl.common.Money;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.domain.HouseholdRole;
import com.budgetowl.household.persistence.HouseholdMemberRepository;
import com.budgetowl.household.persistence.HouseholdRepository;
import com.budgetowl.persistence.PersistenceTestBase;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Reads and writes through {@link AccountRepository}: scoped, ordered as the feature promises,
 * paged in the query, and exact about money.
 *
 * <p>{@code generate_statistics} is on so the N+1 and "paged in the query" assertions can count
 * real JDBC statements and real entity loads. Neither is visible to a unit test — a list endpoint
 * that issues one query per row looks perfectly correct until something counts
 * (docs/guides/database-style.md).
 *
 * <p>The cross-household case lives in {@link AccountHouseholdScopingIT}, which needs a second
 * household and therefore needs more explaining than it belongs here.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class AccountRepositoryIT extends PersistenceTestBase {

    private static final Currency GBP = Currency.getInstance("GBP");

    @Autowired private UserAccountRepository users;
    @Autowired private HouseholdRepository households;
    @Autowired private HouseholdMemberRepository members;
    @Autowired private AccountRepository accounts;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private UUID householdId;

    @BeforeEach
    void seedAHousehold() {
        householdId =
                Objects.requireNonNull(
                        transaction.execute(
                                status -> {
                                    // The household and its first OWNER must be committed
                                    // together: a household with no OWNER does not survive COMMIT
                                    // (V3__households.sql).
                                    Household household =
                                            households.save(Household.named("Home", GBP));
                                    members.save(
                                            HouseholdMember.of(
                                                    household,
                                                    users.save(
                                                            UserAccount.member(
                                                                    "ada@example.com", "Ada")),
                                                    HouseholdRole.OWNER));
                                    return household.id();
                                }));
    }

    // -------------------------------------------------------------------------------- one account

    @Test
    void savesAnAccountAndReadsItBackWithinItsOwnHousehold() {
        UUID id = create("Current", AccountType.CHECKING, "250.0000", "4412");

        Account found = accounts.findByIdAndHouseholdId(id, householdId).orElseThrow();

        assertThat(found.name()).isEqualTo("Current");
        assertThat(found.type()).isEqualTo(AccountType.CHECKING);
        assertThat(found.lastFour()).contains("4412");
        assertThat(found.householdId()).isEqualTo(householdId);
        assertThat(found.isArchived()).isFalse();
        assertThat(found.version()).isZero();
    }

    @Test
    void doesNotFindAnAccountWhenTheHouseholdDoesNotMatch() {
        UUID id = create("Current", AccountType.CHECKING, "0.0000", null);

        assertThat(accounts.findByIdAndHouseholdId(id, UUID.randomUUID()))
                .as("the scoping predicate is the whole point of the method")
                .isEmpty();
    }

    @Test
    void doesNotFindAnAccountThatDoesNotExist() {
        // The id of a row that belongs to nothing, which since ADR-0026 is the common shape of
        // the 404 case (docs/guides/testing-style.md).
        assertThat(accounts.findByIdAndHouseholdId(UUID.randomUUID(), householdId)).isEmpty();
    }

    @Test
    void deletesAnAccount() {
        UUID id = create("Mistake", AccountType.CASH, "0.0000", null);

        transaction.executeWithoutResult(
                status ->
                        accounts.delete(
                                accounts.findByIdAndHouseholdId(id, householdId).orElseThrow()));

        assertThat(accounts.findByIdAndHouseholdId(id, householdId)).isEmpty();
        assertThat(accounts.countByHouseholdId(householdId)).isZero();
    }

    @Test
    void countsTheHouseholdsAccountsArchivedIncluded() {
        create("Current", AccountType.CHECKING, "0.0000", null);
        archive(create("Old", AccountType.SAVINGS, "0.0000", null));

        assertThat(accounts.countByHouseholdId(householdId)).isEqualTo(2);
        assertThat(accounts.countByHouseholdId(UUID.randomUUID())).isZero();
    }

    // ------------------------------------------------------------------------------------ money

    @Test
    void roundTripsAnExactOpeningBalanceThroughJpa() {
        // -1234.5678 is not representable as a double. If anything on this path were floating
        // point — the entity, the converter, the column — this assertion would fail.
        UUID id = create("Current", AccountType.CHECKING, "-1234.5678", null);

        Account found = accounts.findByIdAndHouseholdId(id, householdId).orElseThrow();

        assertThat(found.openingBalance()).isEqualTo(Money.of("-1234.5678", "GBP"));
        assertThat(found.openingBalance().amount())
                .isEqualByComparingTo(new BigDecimal("-1234.5678"));
        assertThat(found.openingBalance().amount().scale()).isEqualTo(4);
    }

    @Test
    void keepsEachAccountInItsOwnCurrency() {
        // ADR-0022: nothing is converted behind the user's back, and two currencies in one
        // household is an ordinary case rather than an error.
        UUID sterling = create("Current", AccountType.CHECKING, "100.0000", null);
        UUID euros =
                Objects.requireNonNull(
                        transaction.execute(
                                status ->
                                        accounts.save(
                                                        Account.of(
                                                                households
                                                                        .findById(householdId)
                                                                        .orElseThrow(),
                                                                "Urlaub",
                                                                AccountType.SAVINGS,
                                                                Money.of("100.0000", "EUR")))
                                                .id()));

        assertThat(
                        accounts.findByIdAndHouseholdId(sterling, householdId)
                                .orElseThrow()
                                .openingBalance())
                .isEqualTo(Money.of("100.00", "GBP"))
                .isNotEqualTo(Money.of("100.00", "EUR"));
        assertThat(accounts.findByIdAndHouseholdId(euros, householdId).orElseThrow().currency())
                .isEqualTo(Currency.getInstance("EUR"));
    }

    // ---------------------------------------------------------------------------------- listing

    @Test
    void listsUnarchivedAccountsOrderedByNameCaseInsensitively() {
        // A case-sensitive sort would put every capital letter before every lowercase one, so
        // "apple" would land after "Zebra" and the list would look shuffled to its owner.
        create("zebra", AccountType.CASH, "0.0000", null);
        create("Apple", AccountType.CHECKING, "0.0000", null);
        create("banana", AccountType.SAVINGS, "0.0000", null);
        create("Cherry", AccountType.INVESTMENT, "0.0000", null);

        Page<Account> page = accounts.findUnarchivedByHouseholdId(householdId, unpaged());

        assertThat(page.getContent())
                .extracting(Account::name)
                .containsExactly("Apple", "banana", "Cherry", "zebra");
    }

    @Test
    void leavesArchivedAccountsOutOfTheDefaultListing() {
        create("Current", AccountType.CHECKING, "0.0000", null);
        archive(create("Closed", AccountType.SAVINGS, "0.0000", null));

        assertThat(accounts.findUnarchivedByHouseholdId(householdId, unpaged()).getContent())
                .extracting(Account::name)
                .containsExactly("Current");
    }

    @Test
    void returnsArchivedAndUnarchivedTogetherWhenAsked() {
        // ?includeArchived=true. Each row carries `archived` so the client can group them, and an
        // archived account's balance is still correct — archiving hides, it does not zero.
        create("Current", AccountType.CHECKING, "0.0000", null);
        archive(create("Closed", AccountType.SAVINGS, "12.3400", null));

        Page<Account> page = accounts.findAllByHouseholdId(householdId, unpaged());

        assertThat(page.getContent())
                .extracting(Account::name)
                .containsExactly("Closed", "Current");
        assertThat(page.getContent())
                .filteredOn(Account::isArchived)
                .singleElement()
                .satisfies(
                        archived ->
                                assertThat(archived.openingBalance())
                                        .isEqualTo(Money.of("12.34", "GBP")));
    }

    @Test
    void pagesTwoAccountsWithTheSameNameWithoutRepeatingOrSkippingEither() {
        // Names are not unique, so without the tiebreak on id the order of two rows called
        // "Savings" is whatever PostgreSQL feels like per query — and a reader paging through
        // them can see one twice and the other never.
        create("Savings", AccountType.SAVINGS, "0.0000", null);
        create("Savings", AccountType.SAVINGS, "0.0000", null);

        List<UUID> firstPage =
                accounts
                        .findUnarchivedByHouseholdId(householdId, PageRequest.of(0, 1))
                        .getContent()
                        .stream()
                        .map(Account::id)
                        .toList();
        List<UUID> secondPage =
                accounts
                        .findUnarchivedByHouseholdId(householdId, PageRequest.of(1, 1))
                        .getContent()
                        .stream()
                        .map(Account::id)
                        .toList();

        assertThat(firstPage).hasSize(1);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
        // Deliberately not asserting WHICH id comes first: PostgreSQL orders uuid by its bytes
        // and Java's UUID.compareTo by signed longs, so the two disagree. What the tiebreak has
        // to guarantee is that the order is total and stable, which disjoint pages covering
        // every row is exactly the evidence for.
        assertThat(
                        accounts.findUnarchivedByHouseholdId(householdId, PageRequest.of(0, 1))
                                .getContent())
                .extracting(Account::id)
                .containsExactlyElementsOf(firstPage);
    }

    @Test
    void pagesInTheQueryRatherThanInMemory() {
        // "A financial collection is not allowed to [page in memory] — it pages in the query"
        // (PageResponse.of). The entity load count is the honest test of that: a repository that
        // fetched everything and sliced it would load 20 and return 5.
        for (int i = 0; i < 20; i++) {
            create("Account %02d".formatted(i), AccountType.CHECKING, "0.0000", null);
        }
        Statistics statistics = resetStatistics();

        Page<Account> page =
                accounts.findUnarchivedByHouseholdId(householdId, PageRequest.of(1, 5));

        assertThat(page.getContent()).hasSize(5);
        assertThat(page.getTotalElements()).isEqualTo(20);
        assertThat(page.getContent()).extracting(Account::name).first().isEqualTo("Account 05");
        assertThat(statistics.getEntityLoadCount())
                .as("five rows fetched, not twenty fetched and fifteen thrown away")
                .isEqualTo(5);
        assertThat(statistics.getPrepareStatementCount())
                .as("the page and its count, and nothing per row")
                .isEqualTo(2);
    }

    @Test
    void readsAWholeListWithoutAQueryPerRow() {
        // The N+1 assertion. Mapping every row's household, name and balance must not resolve a
        // lazy anything — which is why Account maps household_id as a plain UUID rather than as a
        // @ManyToOne that a mapper would innocently dereference once per row.
        for (int i = 0; i < 10; i++) {
            create("Account %02d".formatted(i), AccountType.CHECKING, "1.0000", "000" + (i % 10));
        }
        Statistics statistics = resetStatistics();

        List<String> rendered =
                accounts.findAllByHouseholdId(householdId, unpaged()).getContent().stream()
                        .map(
                                account ->
                                        account.householdId()
                                                + account.name()
                                                + account.openingBalance()
                                                + account.lastFour().orElse(""))
                        .toList();

        assertThat(rendered).hasSize(10);
        assertThat(statistics.getPrepareStatementCount())
                .as(
                        "one query, not one per row — and no count query, because the first page"
                                + " holds everything and Spring Data skips it")
                .isEqualTo(1);
    }

    // ----------------------------------------------------------------------- optimistic locking

    @Test
    void refusesTheSecondOfTwoConcurrentEditsToTheSameAccount() {
        // Two people in one household editing one account. The loser must be told — overwriting
        // the winner silently is how a household discovers its own data is unreliable.
        UUID id = create("Current", AccountType.CHECKING, "0.0000", null);
        Account asSeenByAda = load(id);
        Account asSeenByGrace = load(id);
        assertThat(asSeenByAda.version()).isEqualTo(asSeenByGrace.version());

        transaction.executeWithoutResult(
                status -> {
                    asSeenByAda.rename("Everyday");
                    accounts.save(asSeenByAda);
                });

        asSeenByGrace.rename("Main");
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> accounts.save(asSeenByGrace)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(load(id).name()).as("the winner's write stands").isEqualTo("Everyday");
    }

    @Test
    void movesTheVersionOnWithEveryCommittedEdit() {
        UUID id = create("Current", AccountType.CHECKING, "0.0000", null);
        assertThat(load(id).version()).isZero();

        transaction.executeWithoutResult(
                status -> {
                    Account account =
                            accounts.findByIdAndHouseholdId(id, householdId).orElseThrow();
                    account.archive();
                });

        assertThat(load(id).version()).isEqualTo(1);
        assertThat(load(id).isArchived()).isTrue();
    }

    // ---------------------------------------------------------------------------------- helpers

    private UUID create(String name, AccountType type, String openingBalance, String lastFour) {
        return Objects.requireNonNull(
                transaction.execute(
                        status -> {
                            Account account =
                                    Account.of(
                                            households.findById(householdId).orElseThrow(),
                                            name,
                                            type,
                                            Money.of(openingBalance, "GBP"));
                            account.identifyBy(lastFour);
                            return accounts.save(account).id();
                        }));
    }

    private void archive(UUID id) {
        transaction.executeWithoutResult(
                status -> accounts.findByIdAndHouseholdId(id, householdId).orElseThrow().archive());
    }

    private Account load(UUID id) {
        return Objects.requireNonNull(
                transaction.execute(
                        status -> accounts.findByIdAndHouseholdId(id, householdId).orElseThrow()));
    }

    /**
     * Unsorted on purpose. The order is fixed in the query — an {@code ORDER BY} assembled from
     * caller input is SQL injection with extra steps — so a {@code Sort} here would only ever be
     * appended after a unique key and could not change the result.
     */
    private static Pageable unpaged() {
        return PageRequest.of(0, 200);
    }

    private Statistics resetStatistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        return statistics;
    }
}
