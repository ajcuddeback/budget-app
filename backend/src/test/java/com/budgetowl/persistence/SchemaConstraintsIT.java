package com.budgetowl.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The constraints, exercised against the database rather than against the code that is supposed to
 * respect them.
 *
 * <p>"The application validates for good error messages; the database enforces truth"
 * (docs/guides/database-style.md). Every assertion here would still hold if the whole service layer
 * were replaced tomorrow, which is the only reason any of it is worth writing down.
 */
@SpringBootTest
class SchemaConstraintsIT extends PersistenceTestBase {

    private static final String SHA256_A =
            "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String SHA256_B =
            "0000000000000000000000000000000000000000000000000000000000000002";

    private UUID owner;
    private UUID household;

    @BeforeEach
    void seedAHouseholdWithAnOwner() {
        owner = UUID.randomUUID();
        household = UUID.randomUUID();
        transaction.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                            owner,
                            "ada@example.com",
                            "Ada");
                    jdbc.update(
                            "INSERT INTO households (id, name, base_currency) VALUES (?, ?, ?)",
                            household,
                            "Home",
                            "GBP");
                    jdbc.update(
                            """
                            INSERT INTO household_members (id, household_id, user_id, role)
                            VALUES (?, ?, ?, 'OWNER')
                            """,
                            UUID.randomUUID(),
                            household,
                            owner);
                });
    }

    // -------------------------------------------------------------------------------- users

    @Test
    void refusesADuplicateEmail() {
        assertThatThrownBy(() -> insertUser("ada@example.com"))
                .hasMessageContaining("uq_users_email");
    }

    @Test
    void refusesAnEmailThatDiffersOnlyInCase() {
        // citext. "Ada@Example.COM" is the same address, and letting it register alongside the
        // original would hand someone a second login onto the same person's identity.
        assertThatThrownBy(() -> insertUser("Ada@Example.COM"))
                .hasMessageContaining("uq_users_email");
    }

    @Test
    void refusesAStatusThatIsNotOneOfTheTwo() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE users SET status = 'SUPERUSER' WHERE id = ?",
                                        owner))
                .hasMessageContaining("ck_users_status");
    }

    @Test
    void refusesAPlaintextPassword() {
        // The worst defect this table could have. A PasswordEncoder's output starts "{bcrypt}$..."
        // or "$2a$..."; a password somebody typed does not.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE users SET password_hash = ? WHERE id = ?",
                                        "correct horse battery staple",
                                        owner))
                .hasMessageContaining("ck_users_password_hash_encoded");
    }

    @Test
    void acceptsAnEncodedPassword() {
        assertThatCode(
                        () ->
                                jdbc.update(
                                        "UPDATE users SET password_hash = ? WHERE id = ?",
                                        "{bcrypt}$2a$12$abcdefghijklmnopqrstuv",
                                        owner))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsAUserWithNoPasswordAtAll() {
        // An OIDC-provisioned user. NULL is the honest representation, and it must stay legal.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM users WHERE password_hash IS NULL",
                                Long.class))
                .isEqualTo(1L);
    }

    @Test
    void refusesABlankDisplayName() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                                        UUID.randomUUID(),
                                        "blank@example.com",
                                        "   "))
                .hasMessageContaining("ck_users_display_name_present");
    }

    // ---------------------------------------------------------------------- household_members

    @Test
    void refusesADuplicateMembershipForTheSameUser() {
        assertThatThrownBy(() -> insertMembership(household, owner, "VIEWER"))
                .hasMessageContaining("uq_household_members_household_id_user_id");
    }

    @Test
    void refusesARoleOutsideTheThree() {
        UUID other = insertUser("grace@example.com");

        assertThatThrownBy(() -> insertMembership(household, other, "SUPERUSER"))
                .hasMessageContaining("ck_household_members_role");
    }

    @Test
    void acceptsEachOfTheThreeRoles() {
        for (String role : new String[] {"OWNER", "MEMBER", "VIEWER"}) {
            UUID user = insertUser(role.toLowerCase(java.util.Locale.ROOT) + "@example.com");

            assertThatCode(() -> insertMembership(household, user, role))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void refusesAMembershipForAUserThatDoesNotExist() {
        assertThatThrownBy(() -> insertMembership(household, UUID.randomUUID(), "MEMBER"))
                .hasMessageContaining("fk_household_members_user");
    }

    @Test
    void refusesADisplayCurrencyThatIsNotAnIso4217Code() {
        // Lowercase, not merely the wrong length — the length alone would let "gbp" through and
        // then two members would disagree about what "GBP" means.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE household_members SET display_currency = ? WHERE user_id = ?",
                                        "gbp",
                                        owner))
                .hasMessageContaining("ck_household_members_display_currency");
    }

    @Test
    void acceptsAPerMemberDisplayCurrencyAndLocale() {
        // ADR-0022 and ADR-0023: one household, two people, two languages is a normal case.
        assertThatCode(
                        () ->
                                jdbc.update(
                                        """
                                        UPDATE household_members
                                           SET display_currency = 'EUR', locale = 'fr-CA'
                                         WHERE user_id = ?
                                        """,
                                        owner))
                .doesNotThrowAnyException();
    }

    // ----------------------------------------------------------------- household_invitations

    @Test
    void refusesAnInvitationTokenThatIsNotASha256() {
        // A plaintext invitation link in the table is a live credential in a database dump.
        assertThatThrownBy(() -> insertInvitation("grace@example.com", "the-actual-token"))
                .hasMessageContaining("ck_household_invitations_token_hash_sha256");
    }

    @Test
    void refusesTwoInvitationsWithTheSameTokenHash() {
        insertInvitation("grace@example.com", SHA256_A);

        assertThatThrownBy(() -> insertInvitation("kathleen@example.com", SHA256_A))
                .hasMessageContaining("uq_household_invitations_token_hash");
    }

    @Test
    void refusesAnInvitationThatIsBothAcceptedAndRevoked() {
        UUID invitation = insertInvitation("grace@example.com", SHA256_A);
        jdbc.update(
                "UPDATE household_invitations SET accepted_at = now() WHERE id = ?", invitation);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE household_invitations SET revoked_at = now() WHERE id = ?",
                                        invitation))
                .hasMessageContaining("ck_household_invitations_not_both_accepted_and_revoked");
    }

    @Test
    void refusesAnInvitationThatHasAlreadyExpiredWhenItIsCreated() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO household_invitations
                                            (id, household_id, email, role, token_hash, expires_at, created_by)
                                        VALUES (?, ?, ?, 'MEMBER', ?, now() - interval '1 day', ?)
                                        """,
                                        UUID.randomUUID(),
                                        household,
                                        "grace@example.com",
                                        SHA256_B,
                                        owner))
                .hasMessageContaining("ck_household_invitations_expires_after_creation");
    }

    @Test
    void keepsTheInvitationAuditTrailWhenTheIssuerIsDeleted() {
        // ON DELETE RESTRICT. Who invited whom is part of the audit trail; deleting a user must
        // not quietly erase it.
        UUID issuer = insertUser("issuer@example.com");
        insertMembership(household, issuer, "OWNER");
        insertInvitationBy(issuer, "grace@example.com", SHA256_A);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", issuer))
                .hasMessageContaining("fk_household_invitations_created_by");
    }

    // ------------------------------------------------------------------------- auth_tokens

    @Test
    void refusesAPlaintextBearerToken() {
        assertThatThrownBy(() -> insertToken("not-a-hash", "Ada's phone"))
                .hasMessageContaining("ck_auth_tokens_token_hash_sha256");
    }

    @Test
    void refusesTwoTokensWithTheSameHash() {
        insertToken(SHA256_A, "Ada's phone");

        assertThatThrownBy(() -> insertToken(SHA256_A, "Ada's tablet"))
                .hasMessageContaining("uq_auth_tokens_token_hash");
    }

    @Test
    void takesAUsersTokensWithThemWhenTheUserIsDeleted() {
        UUID other = insertUser("grace@example.com");
        jdbc.update(
                """
                INSERT INTO auth_tokens (id, user_id, token_hash, device_label, expires_at)
                VALUES (?, ?, ?, ?, now() + interval '30 days')
                """,
                UUID.randomUUID(),
                other,
                SHA256_B,
                "Grace's phone");

        jdbc.update("DELETE FROM users WHERE id = ?", other);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_tokens", Long.class)).isZero();
    }

    // -------------------------------------------------------------------- instance_settings

    @Test
    void refusesASecondInstanceSettingsRow() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO instance_settings (id) VALUES (2)"))
                .hasMessageContaining("ck_instance_settings_single_row");
    }

    @Test
    void refusesToDisablePasswordLoginBeforeAnOwnerHasSignedInThroughOidc() {
        // security-model.md makes this a mechanism rather than a warning, precisely so that
        // "disable password login, then discover OIDC is misconfigured" cannot lock a self-hoster
        // out of their own server.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE instance_settings SET password_login_enabled = false WHERE id = 1"))
                .hasMessageContaining("ck_instance_settings_password_login_lockout");
    }

    @Test
    void allowsDisablingPasswordLoginOnceAnOwnerHasSignedInThroughOidc() {
        jdbc.update("UPDATE instance_settings SET oidc_owner_login_at = now() WHERE id = 1");

        assertThatCode(
                        () ->
                                jdbc.update(
                                        "UPDATE instance_settings SET password_login_enabled = false WHERE id = 1"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesOidcThatIsSwitchedOnButNotConfigured() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE instance_settings SET oidc_enabled = true WHERE id = 1"))
                .hasMessageContaining("ck_instance_settings_oidc_configured");
    }

    @Test
    void refusesOidcProvisioningWhileOidcIsOff() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE instance_settings SET oidc_provisioning_enabled = true WHERE id = 1"))
                .hasMessageContaining("ck_instance_settings_provisioning_requires_oidc");
    }

    // ---------------------------------------------------------------------------------- helpers

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                id,
                email,
                "Someone");
        return id;
    }

    private void insertMembership(UUID inHousehold, UUID user, String role) {
        jdbc.update(
                "INSERT INTO household_members (id, household_id, user_id, role) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                inHousehold,
                user,
                role);
    }

    private UUID insertInvitation(String email, String tokenHash) {
        return insertInvitationBy(owner, email, tokenHash);
    }

    private UUID insertInvitationBy(UUID issuer, String email, String tokenHash) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO household_invitations
                    (id, household_id, email, role, token_hash, expires_at, created_by)
                VALUES (?, ?, ?, 'MEMBER', ?, ?, ?)
                """,
                id,
                household,
                email,
                tokenHash,
                OffsetDateTime.now().plusDays(7),
                issuer);
        return id;
    }

    private void insertToken(String tokenHash, String deviceLabel) {
        jdbc.update(
                """
                INSERT INTO auth_tokens (id, user_id, token_hash, device_label, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                owner,
                tokenHash,
                deviceLabel,
                OffsetDateTime.now().plusDays(30));
    }
}
