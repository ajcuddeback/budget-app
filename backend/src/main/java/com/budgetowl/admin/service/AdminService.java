package com.budgetowl.admin.service;

import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.domain.UserSummary;
import com.budgetowl.auth.persistence.UserAccountRepository;
import com.budgetowl.common.Change;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.instance.domain.InstanceSettings;
import com.budgetowl.instance.persistence.InstanceSettingsRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The instance an operator runs: who may sign in, and how.
 *
 * <p><b>Administrator-only, on every method</b> (ADR-0029). That is a different axis from the
 * household roles that govern money (ADR-0017), so a household {@code OWNER} is refused here
 * exactly like a {@code VIEWER} — see {@link AdministratorAccess}.
 *
 * <p>Three rules are specific to this service:
 *
 * <ul>
 *   <li><b>No financial data leaves it.</b> Not a balance, not a transaction, not a count of them.
 *       ADR-0026 says the operator can read the database if they choose; this is the reason not to
 *       build the screen that does it for them.
 *   <li><b>The OIDC client secret is write-only.</b> It can be set, replaced and cleared, and it is
 *       never returned, logged or put in a {@code toString} — including to the administrator who
 *       typed it.
 *   <li><b>The lockout rule is explained, not merely hit.</b> {@code
 *       ck_instance_settings_password_login_lockout} refuses to leave an instance with no login
 *       route; this service answers {@code password-login-locked} so the console can say what has
 *       to happen first, rather than surfacing a constraint violation as a 500.
 * </ul>
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final AdministratorAccess administrators;
    private final InstanceSettingsRepository settings;
    private final UserAccountRepository users;
    private final String version;

    public AdminService(
            AdministratorAccess administrators,
            InstanceSettingsRepository settings,
            UserAccountRepository users,
            @Value("${budgetowl.version:unknown}") String version) {
        this.administrators = administrators;
        this.settings = settings;
        this.users = users;
        this.version = version;
    }

    /** Version, setup date, who the administrator is, and how many accounts exist. */
    @Transactional(readOnly = true)
    public InstanceView instance(UUID actorId) {
        UserAccount administrator = administrators.requireAdministrator(actorId);
        InstanceSettings current = require();
        return new InstanceView(
                version,
                current.setupCompletedAt(),
                administrator.id(),
                administrator.email(),
                administrator.displayName(),
                users.count());
    }

    @Transactional(readOnly = true)
    public SettingsView settings(UUID actorId) {
        administrators.requireAdministrator(actorId);
        return view(require());
    }

    /** Accounts that can sign in. <b>No financial data</b>, by construction and on purpose. */
    @Transactional(readOnly = true)
    public List<UserSummary> accounts(UUID actorId) {
        administrators.requireAdministrator(actorId);
        return users.findAllSummaries();
    }

    /**
     * Changes how people sign in.
     *
     * @throws ForbiddenException {@code not-instance-admin} for everybody but the administrator
     * @throws ConflictException {@code stale-version}, or {@code password-login-locked}
     */
    @Transactional
    public SettingsView updateSettings(UUID actorId, SettingsChanges changes) {
        UserAccount administrator = administrators.requireAdministrator(actorId);
        InstanceSettings current = require();

        if (current.version() != changes.version()) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the settings changed since they were read");
        }

        if (changes.registrationOpen() != null) {
            if (changes.registrationOpen()) {
                current.openRegistration();
            } else {
                current.closeRegistration();
            }
        }

        applyOidc(current, changes);

        // LAST, and deliberately: disabling password login is only legal once OIDC is on and an
        // OWNER has signed in through it, and a single request may be doing both. Applying it
        // after the OIDC changes means "configure the provider and hide passwords" is judged
        // against the configuration the request is leaving behind, not the one it arrived with.
        applyPasswordLogin(current, changes.passwordLoginEnabled());

        // Flushed, not merely saved: @Version is incremented at flush, and the console reads the
        // version out of this response to send with its next PATCH. A response assembled before
        // the flush hands back the version the caller already had, and their next save is refused
        // as stale — the control V16 added, broken in the one loop it was added for.
        //
        // The flush also moves a database refusal to HERE, ahead of the audit line below. Logged
        // first, a write that the database then rejects at commit would leave the trail asserting
        // a change that did not happen, which is the direction an audit trail must never fail in.
        InstanceSettings saved = settings.saveAndFlush(current);
        // The FIELD NAMES and the actor, never the values — a line carrying an OIDC client secret
        // would undo the rule this service exists to keep.
        log.info(
                "instance settings changed byUserId={} administratorId={} fields={}",
                actorId,
                administrator.id(),
                changedFields(changes));
        return view(saved);
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * Applies the OIDC half.
     *
     * <p>{@code configureOidc} takes all three values together because they only mean anything
     * together, so a change to one of them carries the stored values of the other two through. The
     * secret is the awkward case: it must be readable <em>here</em> to be re-saved, and nowhere
     * else, which is what the entity's deliberately unwieldy accessor name is for.
     */
    private static void applyOidc(InstanceSettings current, SettingsChanges changes) {
        boolean turningOff = Boolean.FALSE.equals(changes.oidcEnabled());
        if (turningOff) {
            current.disableOidc();
            return;
        }

        boolean touchingOidc =
                changes.oidcIssuerUri() != null
                        || changes.oidcClientId() != null
                        || !(changes.oidcClientSecret() instanceof Change.Unchanged<String>)
                        || Boolean.TRUE.equals(changes.oidcEnabled());

        if (touchingOidc) {
            // Blank counts as absent, everywhere here. The database agrees — every branch of
            // ck_instance_settings_oidc_configured tests btrim(...) <> '' — but a blank string
            // reaching the entity turns a validation error the administrator can act on into an
            // opaque 409 from the constraint at flush. The secret's own three-way change already
            // read a blank as "cleared"; the other two were the inconsistent pair.
            String issuer =
                    present(
                            changes.oidcIssuerUri() == null
                                    ? current.oidcIssuerUri()
                                    : changes.oidcIssuerUri());
            String clientId =
                    present(
                            changes.oidcClientId() == null
                                    ? current.oidcClientId()
                                    : changes.oidcClientId());
            String secret =
                    presentCredential(
                            switch (changes.oidcClientSecret()) {
                                case Change.Unchanged<String> ignored ->
                                        current.oidcClientSecretForClientConfiguration();
                                case Change.Cleared<String> ignored -> null;
                                case Change.SetTo<String> given -> given.value();
                            });
            if (issuer == null || clientId == null || secret == null) {
                // An OIDC configuration that is switched on but incomplete is a broken login
                // route, and ck_instance_settings_oidc_configured refuses it. Named here so the
                // administrator is told which field is missing rather than shown a 500.
                throw new InvalidRequestException(
                        ErrorCode.VALIDATION_FAILED,
                        "an OIDC provider needs an issuer, a client id and a client secret",
                        Map.of("field", missingOidcField(issuer, clientId)));
            }
            current.configureOidc(issuer, clientId, secret);
        }

        if (changes.oidcProvisioningEnabled() != null) {
            current.allowOidcProvisioning(changes.oidcProvisioningEnabled());
        }
    }

    /** Called only when one of the three is missing, so the secret is what is left. */
    private static String missingOidcField(String issuer, String clientId) {
        if (issuer == null) {
            return "oidcIssuerUri";
        }
        return clientId == null ? "oidcClientId" : "oidcClientSecret";
    }

    /**
     * {@code null} for absent and for present-but-blank, with surrounding whitespace removed.
     *
     * <p>The database draws the line in the same place — every branch of {@code
     * ck_instance_settings_oidc_configured} tests {@code btrim(...) <> ''} — so a blank that
     * reaches the entity becomes a constraint violation at flush, reported as an opaque {@code
     * 409}, instead of the {@code 400} that names the field.
     */
    private static String present(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * As {@link #present} for the blank test, but hands back the value <b>exactly as given</b>.
     *
     * <p>For the client secret only. An issuer with a stray trailing space is a typo worth
     * absorbing; a secret is an opaque credential the provider chose, and silently editing one
     * breaks the token exchange in a way that looks like the provider's fault. So: blank still
     * counts as missing, and anything else is stored byte for byte.
     */
    private static String presentCredential(String value) {
        return present(value) == null ? null : value;
    }

    /**
     * Applies the password-login switch, if the request carries one.
     *
     * <p>Shaped like {@link #applyOidc}: nothing asked for is an early return, and each value of
     * the switch has its own path. Written as one nested {@code if} inside the caller, CodeQL's
     * {@code java/user-controlled-bypass} read it as "a request field decides whether the
     * login-named methods run" (three high alerts on PR #7). That reading is a false positive: the
     * caller is the administrator, checked before anything else; asking for nothing is meant to
     * change nothing; turning password login on is the safe direction and needs no proof; turning
     * it off always passes {@link #requireAnotherRouteProven}, and the database's lockout CHECK
     * stands behind that. The value is read once, so there is one decision rather than two reads of
     * the same field.
     */
    private static void applyPasswordLogin(InstanceSettings current, Boolean requested) {
        if (requested == null) {
            return;
        }
        if (requested) {
            current.enablePasswordLogin();
        } else {
            requireAnotherRouteProven(current);
            current.disablePasswordLogin();
        }
    }

    /**
     * Refuses to leave the instance with no way in.
     *
     * <p>The database says the same thing at {@code COMMIT}. This says it first, and in words: an
     * administrator who meets the constraint violation learns that something went wrong, and an
     * administrator who meets this learns that an {@code OWNER} has to sign in through the provider
     * before the old route can be hidden.
     */
    private static void requireAnotherRouteProven(InstanceSettings current) {
        if (!current.isOidcEnabled() || current.oidcOwnerLoginAt() == null) {
            throw new ConflictException(
                    ErrorCode.PASSWORD_LOGIN_LOCKED,
                    "an owner must complete a sign-in through your provider before password"
                            + " login can be turned off");
        }
    }

    /** Field names only — see the logging note on {@link #updateSettings}. */
    private static String changedFields(SettingsChanges changes) {
        List<String> named = new java.util.ArrayList<>();
        if (changes.registrationOpen() != null) {
            named.add("registrationOpen");
        }
        if (changes.passwordLoginEnabled() != null) {
            named.add("passwordLoginEnabled");
        }
        if (changes.oidcEnabled() != null) {
            named.add("oidcEnabled");
        }
        if (changes.oidcIssuerUri() != null) {
            named.add("oidcIssuerUri");
        }
        if (changes.oidcClientId() != null) {
            named.add("oidcClientId");
        }
        if (!(changes.oidcClientSecret() instanceof Change.Unchanged<String>)) {
            named.add("oidcClientSecret");
        }
        if (changes.oidcProvisioningEnabled() != null) {
            named.add("oidcProvisioningEnabled");
        }
        return String.join(",", named);
    }

    private static SettingsView view(InstanceSettings current) {
        return new SettingsView(
                current.isRegistrationOpen(),
                current.isPasswordLoginEnabled(),
                // What the console needs to explain the switch rather than offer one that fails.
                !current.isOidcEnabled() || current.oidcOwnerLoginAt() == null,
                current.isOidcEnabled(),
                current.oidcIssuerUri(),
                current.oidcClientId(),
                current.hasOidcClientSecret(),
                current.isOidcProvisioningEnabled(),
                current.oidcOwnerLoginAt(),
                current.updatedAt(),
                current.version());
    }

    private InstanceSettings require() {
        return settings.findCurrent()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "instance_settings has no row; V6 inserts it"));
    }
}
