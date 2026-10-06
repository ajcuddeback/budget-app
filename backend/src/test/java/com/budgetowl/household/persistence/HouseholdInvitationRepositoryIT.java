package com.budgetowl.household.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.persistence.UserAccountRepository;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdInvitation;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.domain.HouseholdRole;
import com.budgetowl.persistence.PersistenceTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Invitations: found by hash, single-use, expiring, and scoped to their household. */
@SpringBootTest
class HouseholdInvitationRepositoryIT extends PersistenceTestBase {

    private static final String DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000001";

    @Autowired private UserAccountRepository users;
    @Autowired private HouseholdRepository households;
    @Autowired private HouseholdMemberRepository members;
    @Autowired private HouseholdInvitationRepository invitations;

    private Household household;
    private UserAccount owner;

    @BeforeEach
    void seedAHousehold() {
        transaction.executeWithoutResult(
                status -> {
                    household =
                            households.save(Household.named("Home", Currency.getInstance("GBP")));
                    owner = users.save(UserAccount.member("ada@example.com", "Ada"));
                    members.save(HouseholdMember.of(household, owner, HouseholdRole.OWNER));
                });
    }

    @Test
    void findsAnInvitationByTheHashOfItsToken() {
        create(DIGEST, "grace@example.com", Instant.now().plus(7, ChronoUnit.DAYS));

        assertThat(invitations.findByTokenHash(DIGEST))
                .hasValueSatisfying(
                        invitation -> {
                            assertThat(invitation.role()).isEqualTo(HouseholdRole.MEMBER);
                            assertThat(invitation.household().id()).isEqualTo(household.id());
                            assertThat(invitation.isUsableAt(Instant.now())).isTrue();
                        });
    }

    @Test
    void findsNothingForATokenThatWasNeverIssued() {
        assertThat(invitations.findByTokenHash(DIGEST.replace("1", "9"))).isEmpty();
    }

    @Test
    void refusesASecondAcceptanceOfTheSameInvitation() {
        UUID id = create(DIGEST, "grace@example.com", Instant.now().plus(7, ChronoUnit.DAYS));

        transaction.executeWithoutResult(
                status ->
                        invitations
                                .findByIdAndHouseholdId(id, household.id())
                                .orElseThrow()
                                .acceptAt(Instant.now()));

        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status ->
                                                invitations
                                                        .findByIdAndHouseholdId(id, household.id())
                                                        .orElseThrow()
                                                        .acceptAt(Instant.now())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void treatsExpiredRevokedAndUsedAlikeAsUnusable() {
        // The caller must collapse all three into one generic failure. They are distinguishable
        // here and must not be distinguishable at the edge, or a link's history is disclosed.
        Instant now = Instant.now();
        UUID expired = create(DIGEST, "a@example.com", now.plus(1, ChronoUnit.SECONDS));
        UUID revoked =
                create(DIGEST.replace("1", "2"), "b@example.com", now.plus(7, ChronoUnit.DAYS));
        UUID used = create(DIGEST.replace("1", "3"), "c@example.com", now.plus(7, ChronoUnit.DAYS));

        transaction.executeWithoutResult(
                status -> {
                    invitations
                            .findByIdAndHouseholdId(revoked, household.id())
                            .orElseThrow()
                            .revokeAt(now);
                    invitations
                            .findByIdAndHouseholdId(used, household.id())
                            .orElseThrow()
                            .acceptAt(now);
                });

        Instant later = now.plus(1, ChronoUnit.DAYS);
        for (UUID id : new UUID[] {expired, revoked, used}) {
            assertThat(
                            invitations
                                    .findByIdAndHouseholdId(id, household.id())
                                    .orElseThrow()
                                    .isUsableAt(later))
                    .isFalse();
        }
    }

    @Test
    void findsAnInvitationOnlyWithinItsOwnHousehold() {
        UUID id = create(DIGEST, "grace@example.com", Instant.now().plus(7, ChronoUnit.DAYS));

        assertThat(invitations.findByIdAndHouseholdId(id, household.id())).isPresent();
        assertThat(invitations.findByIdAndHouseholdId(id, UUID.randomUUID())).isEmpty();
    }

    @Test
    void listsOnlyLiveInvitationsAsPending() {
        Instant now = Instant.now();
        create(DIGEST, "live@example.com", now.plus(7, ChronoUnit.DAYS));
        UUID revoked =
                create(
                        DIGEST.replace("1", "2"),
                        "revoked@example.com",
                        now.plus(7, ChronoUnit.DAYS));
        transaction.executeWithoutResult(
                status ->
                        invitations
                                .findByIdAndHouseholdId(revoked, household.id())
                                .orElseThrow()
                                .revokeAt(now));

        assertThat(invitations.findSummariesByHouseholdId(household.id())).hasSize(2);
        assertThat(invitations.findPendingSummariesByHouseholdId(household.id(), now))
                .singleElement()
                .satisfies(summary -> assertThat(summary.email()).isEqualTo("live@example.com"));
    }

    @Test
    void recognisesAPendingInvitationForAnAddressTypedWithDifferentCapitalisation() {
        // Without the cast this returns false and the owner issues a second live invitation for
        // the same person, which is a second live credential in circulation.
        Instant now = Instant.now();
        create(DIGEST, "grace@example.com", now.plus(7, ChronoUnit.DAYS));

        assertThat(invitations.existsPendingForEmail(household.id(), "GRACE@Example.com", now))
                .isTrue();
        assertThat(invitations.existsPendingForEmail(household.id(), "other@example.com", now))
                .isFalse();
    }

    private UUID create(String tokenHash, String email, Instant expiresAt) {
        return transaction.execute(
                status ->
                        invitations
                                .save(
                                        HouseholdInvitation.create(
                                                households.findById(household.id()).orElseThrow(),
                                                email,
                                                HouseholdRole.MEMBER,
                                                tokenHash,
                                                expiresAt,
                                                users.findById(owner.id()).orElseThrow()))
                                .id());
    }
}
