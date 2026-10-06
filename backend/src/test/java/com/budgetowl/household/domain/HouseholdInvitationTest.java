package com.budgetowl.household.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.domain.Ids;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class HouseholdInvitationTest {

    private static final String DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000001";
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private final Household household = Household.named("Home", Currency.getInstance("GBP"));
    private final UserAccount owner = UserAccount.member("ada@example.com", "Ada");

    @Test
    void aFreshInvitationIsUsable() {
        HouseholdInvitation invitation = created(NOW.plus(7, ChronoUnit.DAYS));

        assertThat(invitation.isUsableAt(NOW)).isTrue();
        assertThat(invitation.email()).isEqualTo("grace@example.com");
        assertThat(invitation.role()).isEqualTo(HouseholdRole.MEMBER);
        assertThat(invitation.household()).isSameAs(household);
        assertThat(invitation.createdBy()).isSameAs(owner);
        assertThat(invitation.acceptedAt()).isNull();
        assertThat(invitation.revokedAt()).isNull();
        assertThat(invitation.createdAt()).isNull();
        assertThat(invitation.expiresAt()).isEqualTo(NOW.plus(7, ChronoUnit.DAYS));
    }

    @Test
    void anExpiredInvitationIsNotUsable() {
        assertThat(created(NOW.minusSeconds(1)).isUsableAt(NOW)).isFalse();
        assertThat(created(NOW).isUsableAt(NOW)).as("expiry is not inclusive").isFalse();
    }

    @Test
    void anInvitationIsSingleUse() {
        HouseholdInvitation invitation = created(NOW.plus(7, ChronoUnit.DAYS));

        invitation.acceptAt(NOW);

        assertThat(invitation.isUsableAt(NOW)).isFalse();
        assertThat(invitation.acceptedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> invitation.acceptAt(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer usable");
    }

    @Test
    void arevokedInvitationCannotThenBeAccepted() {
        HouseholdInvitation invitation = created(NOW.plus(7, ChronoUnit.DAYS));

        invitation.revokeAt(NOW);

        assertThat(invitation.isUsableAt(NOW)).isFalse();
        assertThatThrownBy(() -> invitation.acceptAt(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAcceptedInvitationCannotThenBeRevoked() {
        // It is already spent, and pretending otherwise would put the row in a state
        // ck_household_invitations_not_both_accepted_and_revoked refuses anyway.
        HouseholdInvitation invitation = created(NOW.plus(7, ChronoUnit.DAYS));
        invitation.acceptAt(NOW);

        assertThatThrownBy(() -> invitation.revokeAt(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been accepted");
    }

    @Test
    void revokingTwiceKeepsTheFirstInstant() {
        HouseholdInvitation invitation = created(NOW.plus(7, ChronoUnit.DAYS));

        invitation.revokeAt(NOW);
        invitation.revokeAt(NOW.plus(1, ChronoUnit.HOURS));

        assertThat(invitation.revokedAt()).isEqualTo(NOW);
    }

    @Test
    void refusesToExistWithAnyOfItsPartsMissing() {
        Instant expiry = NOW.plus(7, ChronoUnit.DAYS);
        HouseholdRole role = HouseholdRole.MEMBER;

        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                HouseholdInvitation.create(
                                        null, "grace@example.com", role, DIGEST, expiry, owner));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                HouseholdInvitation.create(
                                        household, null, role, DIGEST, expiry, owner));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                HouseholdInvitation.create(
                                        household,
                                        "grace@example.com",
                                        null,
                                        DIGEST,
                                        expiry,
                                        owner));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                HouseholdInvitation.create(
                                        household, "grace@example.com", role, null, expiry, owner));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                HouseholdInvitation.create(
                                        household, "grace@example.com", role, DIGEST, null, owner));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                HouseholdInvitation.create(
                                        household,
                                        "grace@example.com",
                                        role,
                                        DIGEST,
                                        expiry,
                                        null));
        assertThatNullPointerException()
                .isThrownBy(() -> created(NOW.plusSeconds(60)).acceptAt(null));
        assertThatNullPointerException()
                .isThrownBy(() -> created(NOW.plusSeconds(60)).revokeAt(null));
    }

    @Test
    void identityComesFromTheRowAndTheStringFormNamesNeitherTheTokenNorTheInvitee() {
        HouseholdInvitation saved = Ids.withId(created(NOW.plusSeconds(60)));
        HouseholdInvitation sameRow = Ids.withId(created(NOW.plusSeconds(60)), Ids.idOf(saved));
        HouseholdInvitation unsaved = created(NOW.plusSeconds(60));

        assertThat(saved).isEqualTo(sameRow).isNotEqualTo("not an invitation");
        assertThat(saved.hashCode()).isEqualTo(sameRow.hashCode());
        assertThat(unsaved).isEqualTo(unsaved).isNotEqualTo(saved);
        assertThat(saved.toString()).doesNotContain(DIGEST).doesNotContain("grace@example.com");
    }

    private HouseholdInvitation created(Instant expiresAt) {
        return HouseholdInvitation.create(
                household, "grace@example.com", HouseholdRole.MEMBER, DIGEST, expiresAt, owner);
    }
}
