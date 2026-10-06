package com.budgetowl.household.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.persistence.UserAccountRepository;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.domain.HouseholdMemberSummary;
import com.budgetowl.household.domain.HouseholdRole;
import com.budgetowl.persistence.PersistenceTestBase;
import jakarta.persistence.EntityManagerFactory;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Membership reads: scoped, case-insensitive where the column says so, and one query per list.
 *
 * <p>{@code generate_statistics} is on so the N+1 assertions can count real JDBC statements. A unit
 * test cannot catch an N+1 — it is invisible until something counts the queries, and by then it is
 * in production (docs/guides/database-style.md).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class HouseholdMemberRepositoryIT extends PersistenceTestBase {

    @Autowired private UserAccountRepository users;
    @Autowired private HouseholdRepository households;
    @Autowired private HouseholdMemberRepository members;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private UUID householdId;

    @BeforeEach
    void seedAHouseholdWithFiveMembers() {
        transaction.executeWithoutResult(
                status -> {
                    // The household and its first OWNER must be committed together: a household
                    // with no OWNER does not survive COMMIT (V3__households.sql).
                    Household household =
                            households.save(Household.named("Home", Currency.getInstance("GBP")));
                    members.save(
                            HouseholdMember.of(
                                    household,
                                    users.save(UserAccount.member("ada@example.com", "Ada")),
                                    HouseholdRole.OWNER));
                    for (int i = 1; i <= 4; i++) {
                        members.save(
                                HouseholdMember.of(
                                        household,
                                        users.save(
                                                UserAccount.member(
                                                        "member" + i + "@example.com",
                                                        "Member " + i)),
                                        HouseholdRole.MEMBER));
                    }
                    householdId = household.id();
                });
    }

    @Test
    void listsEveryMemberWithTheirUserInASingleQuery() {
        Statistics statistics = resetStatistics();

        List<HouseholdMemberSummary> summaries = members.findSummariesByHouseholdId(householdId);

        assertThat(summaries).hasSize(5);
        assertThat(summaries).extracting(HouseholdMemberSummary::displayName).contains("Ada");
        assertThat(statistics.getPrepareStatementCount())
                .as("one join, not one query per member")
                .isEqualTo(1);
    }

    @Test
    void loadsMemberEntitiesWithTheirUsersInASingleQuery() {
        Statistics statistics = resetStatistics();

        List<HouseholdMember> loaded = members.findAllByHouseholdId(householdId);
        List<String> names = loaded.stream().map(member -> member.user().displayName()).toList();

        assertThat(names).hasSize(5);
        assertThat(statistics.getPrepareStatementCount())
                .as("@EntityGraph, not five lazy proxies resolved one at a time")
                .isEqualTo(1);
    }

    @Test
    void findsAMembershipOnlyWithinItsOwnHousehold() {
        HouseholdMember ada =
                members.findSummariesByHouseholdId(householdId).stream()
                        .filter(summary -> summary.email().equals("ada@example.com"))
                        .findFirst()
                        .map(
                                summary ->
                                        members.findByIdAndHouseholdId(summary.id(), householdId)
                                                .orElseThrow())
                        .orElseThrow();

        assertThat(members.findByIdAndHouseholdId(ada.id(), householdId)).isPresent();
        assertThat(members.findByIdAndHouseholdId(ada.id(), UUID.randomUUID()))
                .as("the scoping predicate is the whole point of the method")
                .isEmpty();
    }

    @Test
    void resolvesAMembershipFromTheUser() {
        UUID ada = users.findByEmail("ada@example.com").orElseThrow().id();

        assertThat(members.findByHouseholdIdAndUserId(householdId, ada))
                .hasValueSatisfying(
                        member -> assertThat(member.role()).isEqualTo(HouseholdRole.OWNER));
    }

    @Test
    void reportsNoMembershipForASignedInUserWhoHasNotBeenInvited() {
        // An OIDC-provisioned user. The caller turns this into a 403 — not a crash, and not an
        // empty success.
        UUID stranger =
                transaction.execute(
                        status ->
                                users.save(UserAccount.member("stranger@example.com", "Stranger"))
                                        .id());

        assertThat(members.findByHouseholdIdAndUserId(householdId, stranger)).isEmpty();
        assertThat(members.findByUserId(stranger)).isEmpty();
    }

    @Test
    void recognisesAMemberByAnAddressTypedWithDifferentCapitalisation() {
        // household_invitations.email and users.email are citext. Without the explicit cast in the
        // query this returns false and the owner is told the address is not a member when it is.
        assertThat(members.existsByHouseholdIdAndUserEmail(householdId, "ADA@EXAMPLE.COM"))
                .isTrue();
        assertThat(members.existsByHouseholdIdAndUserEmail(householdId, "nobody@example.com"))
                .isFalse();
    }

    @Test
    void countsOwnersForAFriendlyMessageBeforeTheAttempt() {
        assertThat(members.countByHouseholdIdAndRole(householdId, HouseholdRole.OWNER))
                .isEqualTo(1);
        assertThat(members.countByHouseholdIdAndRole(householdId, HouseholdRole.MEMBER))
                .isEqualTo(4);
        assertThat(members.countByHouseholdId(householdId)).isEqualTo(5);
    }

    private Statistics resetStatistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        return statistics;
    }
}
