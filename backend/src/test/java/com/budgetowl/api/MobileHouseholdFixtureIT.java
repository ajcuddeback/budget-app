package com.budgetowl.api;

import com.budgetowl.api.DemoFixtureIT.Caller;
import com.budgetowl.api.DemoFixtureIT.MobileRecorder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The phone's Household, Preferences and Admin screens, recorded <b>from the real stack</b> into
 * {@code mobile/test/fixtures/household.generated.json} (slice 15, phase 2).
 *
 * <p>The same machinery as {@link DemoFixtureIT}'s mobile fixtures, in a class of its own so that
 * the areas of phase 2 can be generated in parallel: {@link MobileRecorder} records each exchange
 * with the state it belongs to and the state a write moves the server to, and the mobile fake
 * answers only what was recorded here.
 *
 * <p><b>Credentials are placeholders by construction.</b> Every bearer token and the one invitation
 * token this story creates are registered with {@link MobileRecorder#secret}, so the file holds
 * {@code fixture-token-owner} and {@code fixture-invitation-edsger} where the real values were —
 * in the invitation's {@code token} and in its {@code acceptPath}. The OIDC client secret the
 * console sends is a value this class makes up, and says so in its name.
 *
 * <p>Some states are recorded out of the order a phone meets them, deliberately: {@code
 * grace-demoted} is what Grace's phone read while she was an owner, followed by what the server
 * said to her write after she had been demoted on the web — the case "the client offered it, the
 * server refused it" exists only across two moments. Each response is still the application's own.
 *
 * <p>Regenerate: {@code mvn -f backend -B verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
 * -Dit.test='MobileHouseholdFixtureIT*' -Dfixtures.write=true}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MobileHouseholdFixtureIT extends ApiTestBase {

    private static final String PHONE_LABEL = "Phone · Android";

    private static final String ME = "/api/auth/me";
    private static final String LEDGER = "/api/transactions?page=0&size=50";
    private static final String ACCOUNTS = "/api/accounts?includeArchived=true&page=0&size=200";
    private static final String CATEGORIES = "/api/categories?includeArchived=true&page=0&size=200";

    private static final String HOUSEHOLD = "/api/households/current";
    private static final String MEMBERS = "/api/households/current/members?page=0&size=200";
    private static final String MEMBER = "/api/households/current/members/";
    private static final String OWN = "/api/households/current/members/me";
    private static final String INVITATIONS = "/api/households/current/invitations";

    private static final String ADMIN_INSTANCE = "/api/admin/instance";
    private static final String ADMIN_SETTINGS = "/api/admin/settings";
    private static final String ADMIN_USERS = "/api/admin/users";

    private static final String ISSUER = "https://id.example.org";
    private static final String CLIENT_ID = "budget-owl-example";

    /** Made up for this fixture, and named so nobody mistakes it for one that works anywhere. */
    private static final String CLIENT_SECRET_PLACEHOLDER = "placeholder-oidc-client-secret";

    @Test
    void the_committed_mobile_household_fixtures_are_what_the_application_returns()
            throws IOException {
        seedTheHousehold();
        MobileRecorder record = new MobileRecorder();
        Caller stranger = Caller.stranger(anonymous());
        Caller owner = phone(record, "owner", OWNER_EMAIL, OWNER_SECRET);
        Caller member = phone(record, "member", MEMBER_EMAIL, MEMBER_SECRET);
        Caller viewer = phone(record, "viewer", VIEWER_EMAIL, VIEWER_SECRET);

        Map<UUID, String> stable = new LinkedHashMap<>();
        stable.put(householdId(), "hh-0001");
        String ada = name(stable, OWNER_EMAIL, "ada");
        String grace = name(stable, MEMBER_EMAIL, "grace");
        String alan = name(stable, VIEWER_EMAIL, "alan");

        // Any state: who the server is, on every launch, and whether password sign-in is on, which
        // the sign-in screen asks after a session ends.
        record.in("*");
        record.send(stranger, "GET", "/api/meta", null);
        record.send(stranger, "GET", "/api/setup/status", null);
        stranger.client()
                .cookie("XSRF-TOKEN")
                .ifPresent(csrf -> record.secret(csrf, "fixture-csrf"));

        // ---- household: what each role reads, and what each is refused.
        record.in("household");
        for (Caller who : new Caller[] {owner, member, viewer}) {
            readTheHousehold(record, who);
        }
        readTheConsole(record, owner);
        // Forced writes: a VIEWER and a MEMBER are offered none of these, and the API refuses
        // every one regardless. The phone must show the translated 403.
        record.send(viewer, "PATCH", MEMBER + grace, "{\"role\":\"VIEWER\"}");
        record.send(viewer, "DELETE", MEMBER + grace, null);
        record.send(viewer, "PUT", HOUSEHOLD, rename("Rivera Household", "USD"));
        record.send(viewer, "POST", INVITATIONS, invitation(OUTSIDER_EMAIL, "MEMBER"));
        record.send(member, "POST", INVITATIONS, invitation(OUTSIDER_EMAIL, "MEMBER"));
        record.send(member, "PATCH", ADMIN_SETTINGS, "{\"registrationOpen\":true,\"version\":0}");
        // An owner's own row: no control is offered, and the server says why if one is sent.
        record.send(owner, "PATCH", MEMBER + ada, "{\"role\":\"MEMBER\"}");
        record.send(owner, "DELETE", MEMBER + ada, null);

        // ---- an invitation: made, shown once, revoked. The token is a placeholder in the file.
        ApiResponse invited =
                record.send(owner, "POST", INVITATIONS, invitation(OUTSIDER_EMAIL, "VIEWER"));
        if (invited.status() != 201) {
            throw new AssertionError("could not create the invitation: " + invited.body());
        }
        record.secret(invited.stringField("token"), "fixture-invitation-edsger");
        record.send(owner, "DELETE", INVITATIONS + "/" + invited.stringField("id"), null);
        // An address that is already a member: refused without confirming that it is.
        record.send(owner, "POST", INVITATIONS, invitation(MEMBER_EMAIL, "MEMBER"));

        // ---- renamed, and in euros; then back, so every later state starts from the same place.
        record.sendThen("renamed", owner, "PUT", HOUSEHOLD, rename("Rivera Family", "EUR"));
        record.in("renamed");
        readTheHousehold(record, owner);
        record.sendThen("household", owner, "PUT", HOUSEHOLD, rename("Rivera Household", "USD"));

        // ---- preferences: a viewer may set their own (self-scoped, no role needed).
        record.in("household");
        record.sendThen(
                "prefs-saved", viewer, "PATCH", OWN, "{\"displayCurrency\":\"EUR\",\"locale\":\"de-DE\"}");
        record.in("prefs-saved");
        readTheShell(record, viewer);
        // "Follow the default" is null, which is a meaning rather than a gap.
        record.sendThen(
                "household", viewer, "PATCH", OWN, "{\"displayCurrency\":null,\"locale\":null}");

        // ---- Grace made an owner. She is not the administrator: the console refuses her.
        record.in("household");
        record.sendThen("grace-owner", owner, "PATCH", MEMBER + grace, "{\"role\":\"OWNER\"}");
        record.in("grace-owner");
        record.send(owner, "GET", MEMBERS, null);
        readTheHousehold(record, member);
        record.send(member, "GET", ADMIN_INSTANCE, null);
        record.send(member, "GET", ADMIN_SETTINGS, null);
        record.send(member, "GET", ADMIN_USERS, null);
        record.send(member, "PATCH", ADMIN_SETTINGS, "{\"registrationOpen\":true,\"version\":0}");
        // What her phone read while she was an owner...
        record.in("grace-demoted");
        readTheHousehold(record, member);
        record.in("grace-owner");
        record.sendThen("household", owner, "PATCH", MEMBER + grace, "{\"role\":\"MEMBER\"}");
        // ...and what the server says to the owner's controls it still offers, once she is not.
        record.in("grace-demoted");
        record.send(member, "PATCH", MEMBER + alan, "{\"role\":\"MEMBER\"}");
        record.send(member, "DELETE", MEMBER + alan, null);

        // ---- the console: open registration, then a provider, then turn the provider off.
        record.in("household");
        record.sendThen(
                "registration-open",
                owner,
                "PATCH",
                ADMIN_SETTINGS,
                "{\"registrationOpen\":true,\"version\":0}");
        record.in("registration-open");
        afterASave(record, owner);
        record.sendThen("oidc-on", owner, "PATCH", ADMIN_SETTINGS, provider(1));
        record.in("oidc-on");
        readTheShell(record, owner);
        readTheConsole(record, owner);
        record.sendThen(
                "oidc-off", owner, "PATCH", ADMIN_SETTINGS, "{\"oidcEnabled\":false,\"version\":2}");
        record.in("oidc-off");
        afterASave(record, owner);

        // ---- somebody else saves first: a 409, the server's copy re-read, then "save mine".
        record.in("admin-stale");
        readTheShell(record, owner);
        readTheConsole(record, owner);
        ok(owner.client().patch(ADMIN_SETTINGS, "{\"registrationOpen\":false,\"version\":3}"));
        // Switching the provider off kept its issuer and client id, so switching it back on sends
        // only the switch and the secret typed — the diff against what was read, as the web's.
        record.sendThen(
                "admin-conflict",
                owner,
                "PATCH",
                ADMIN_SETTINGS,
                """
                {"oidcEnabled":true,"oidcClientSecret":"%s","version":3}
                """
                        .formatted(CLIENT_SECRET_PLACEHOLDER));
        record.in("admin-conflict");
        record.send(owner, "GET", ADMIN_SETTINGS, null);
        // "Save my changes anyway" saves the form as it stands against the copy just re-read —
        // registration as the form still shows it included, exactly as the web console does.
        record.sendThen(
                "conflict-saved",
                owner,
                "PATCH",
                ADMIN_SETTINGS,
                """
                {"registrationOpen":true,"oidcEnabled":true,"oidcClientSecret":"%s","version":4}
                """
                        .formatted(CLIENT_SECRET_PLACEHOLDER));
        record.in("conflict-saved");
        afterASave(record, owner);

        // ---- an owner has proved the provider works, so password sign-in may be turned off.
        // Recorded twice at one moment: once to succeed, and once for the phone whose proof is
        // withdrawn before it saves, which the server refuses with its own code.
        proveTheProvider(true);
        for (String state : new String[] {"oidc-proven", "proof-withdrawn"}) {
            record.in(state);
            readTheShell(record, owner);
            readTheConsole(record, owner);
        }
        proveTheProvider(false);
        record.in("proof-withdrawn");
        record.sendThen(
                "locked-refused",
                owner,
                "PATCH",
                ADMIN_SETTINGS,
                "{\"passwordLoginEnabled\":false,\"version\":5}");
        record.in("locked-refused");
        record.send(owner, "GET", ADMIN_SETTINGS, null);
        proveTheProvider(true);
        record.in("oidc-proven");
        record.sendThen(
                "password-off",
                owner,
                "PATCH",
                ADMIN_SETTINGS,
                "{\"passwordLoginEnabled\":false,\"version\":5}");
        record.in("password-off");
        afterASave(record, owner);

        // ---- removal and leaving, last: both end a membership, and its id with it.
        record.in("household");
        record.sendThen("grace-removed", owner, "DELETE", MEMBER + grace, null);
        record.in("grace-removed");
        record.send(owner, "GET", MEMBERS, null);
        // Her tokens went with her membership: her phone's next request is a 401.
        record.send(member, "GET", ME, null);
        record.in("household");
        record.sendThen("alan-left", viewer, "DELETE", MEMBER + alan, null);
        record.in("alan-left");
        record.send(viewer, "GET", ME, null);

        String document =
                record.render("household, preferences and admin — MobileHouseholdFixtureIT")
                        .replaceAll(
                                "\"(setupCompletedAt|oidcOwnerLoginAt)\"(\\s*):(\\s*)\"[^\"]+\"",
                                "\"$1\"$2:$3\"" + DemoFixtureIT.DEMO_TODAY + "\"");
        DemoFixtureIT.compareOrWrite(
                DemoFixtureIT.canonicaliseMobile(document, stable),
                DemoFixtureIT.MOBILE_FIXTURES.resolve("household.generated.json"),
                Path.of("target", "mobile-household.generated.json"));
    }

    /** The demo household's name and currency, with an owner who is also the administrator. */
    private void seedTheHousehold() {
        ApiResponse setup =
                browser()
                        .post(
                                "/api/setup/first-user",
                                """
                                {"email":"%s","displayName":"Ada","password":"%s",
                                 "householdName":"Rivera Household","baseCurrency":"USD"}
                                """
                                        .formatted(OWNER_EMAIL, OWNER_SECRET));
        if (setup.status() != 201) {
            throw new AssertionError("could not set up the household: " + setup.body());
        }
        ApiClient owner = signIn(OWNER_EMAIL, OWNER_SECRET);
        acceptInvitation(invite(owner, MEMBER_EMAIL, "MEMBER"), "Grace", MEMBER_SECRET);
        acceptInvitation(invite(owner, VIEWER_EMAIL, "VIEWER"), "Alan", VIEWER_SECRET);
    }

    /** A phone's token, issued off the record and named in the file. */
    private Caller phone(MobileRecorder record, String who, String email, String secret) {
        String token = issueToken(email, secret, PHONE_LABEL);
        record.secret(token, "fixture-token-" + who);
        return new Caller(who, anonymous().withBearerToken(token), Map.of());
    }

    /** Stable names for a person's user and membership ids; returns the membership id. */
    private String name(Map<UUID, String> stable, String email, String person) {
        stable.put(userIdOf(email), "usr-" + person);
        UUID membership = membershipIdOf(email);
        stable.put(membership, "mem-" + person);
        return membership.toString();
    }

    private UUID householdId() {
        return jdbc.queryForObject("SELECT id FROM households", UUID.class);
    }

    /** What any signed-in phone reads on arrival: who it is, and the Transactions tab. */
    private static void readTheShell(MobileRecorder record, Caller who) {
        record.send(who, "GET", ME, null);
        record.send(who, "GET", ACCOUNTS, null);
        record.send(who, "GET", CATEGORIES, null);
        record.send(who, "GET", LEDGER, null);
    }

    private static void readTheHousehold(MobileRecorder record, Caller who) {
        readTheShell(record, who);
        record.send(who, "GET", HOUSEHOLD, null);
        record.send(who, "GET", MEMBERS, null);
    }

    private static void readTheConsole(MobileRecorder record, Caller who) {
        record.send(who, "GET", ADMIN_INSTANCE, null);
        record.send(who, "GET", ADMIN_SETTINGS, null);
        record.send(who, "GET", ADMIN_USERS, null);
    }

    /** The console re-reads its settings and the instance block after a save, as the web does. */
    private static void afterASave(MobileRecorder record, Caller who) {
        record.send(who, "GET", ADMIN_SETTINGS, null);
        record.send(who, "GET", ADMIN_INSTANCE, null);
    }

    private void proveTheProvider(boolean proven) {
        jdbc.update(
                "UPDATE instance_settings SET oidc_owner_login_at = "
                        + (proven ? "now()" : "NULL")
                        + " WHERE id = 1");
    }

    private static String rename(String name, String currency) {
        return """
               {"name":"%s","baseCurrency":"%s"}
               """
                .formatted(name, currency);
    }

    private static String invitation(String email, String role) {
        return """
               {"email":"%s","role":"%s"}
               """
                .formatted(email, role);
    }

    /** Switching the provider on, as the console sends it against [version]. */
    private static String provider(int version) {
        return """
               {"oidcEnabled":true,"oidcIssuerUri":"%s","oidcClientId":"%s",
                "oidcClientSecret":"%s","version":%d}
               """
                .formatted(ISSUER, CLIENT_ID, CLIENT_SECRET_PLACEHOLDER, version);
    }

    private static void ok(ApiResponse response) {
        if (response.status() != 200) {
            throw new AssertionError("a setup step failed: " + response.body());
        }
    }
}
