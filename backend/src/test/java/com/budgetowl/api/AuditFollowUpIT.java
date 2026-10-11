package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Three defects a security audit of slice 3 found, each pinned where it was reachable.
 *
 * <p>They have nothing in common except their origin, which is why they are here rather than
 * scattered: a reader asking "what did that audit actually change" gets one file.
 */
class AuditFollowUpIT extends ApiTestBase {

    // U+3000 IDEOGRAPHIC SPACE, U+2028 LINE SEPARATOR, U+205F MEDIUM MATHEMATICAL SPACE. Each is
    // whitespace to Character.isWhitespace and was NOT whitespace to a non-Unicode regex \\S.
    @ParameterizedTest
    @ValueSource(strings = {"　", " ", " ", " ", " "})
    void refusesAUnicodeBlankNameOnPatchInsteadOfFailing(String blank) {
        seedHousehold();
        ApiClient owner = signIn(OWNER_EMAIL, OWNER_SECRET);
        java.util.UUID id = createAccount(owner, "Current", null);

        ApiResponse response =
                owner.patch("/api/accounts/" + id, "{\"version\":0,\"name\":\"" + blank + "\"}");

        // Was a 500 with a ~130-frame stack trace at ERROR: the pattern let it through and
        // Account's Unicode-aware strip() then emptied it. POST was always a 400 because
        // @NotBlank is Unicode-aware; only PATCH hand-rolls the check.
        assertThat(response.status())
                .as("a blank name is the caller's mistake, not ours")
                .isEqualTo(400);
        assertThat(response.stringField("code")).isEqualTo("validation-failed");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/accounts/zzz",
                "/api/accounts?size=abc",
                "/api/accounts?includeArchived=maybe"
            })
    void answers400RatherThan500WhenAPathOrQueryScalarWillNotConvert(String path) {
        seedHousehold();

        ApiResponse response = signIn(OWNER_EMAIL, OWNER_SECRET).get(path);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.stringField("code")).isEqualTo("validation-failed");
    }

    @Test
    void doesNotEchoAnUnconvertableParameterBackIntoTheResponse() {
        seedHousehold();

        ApiResponse response =
                signIn(OWNER_EMAIL, OWNER_SECRET).get("/api/accounts?size=INJECTED-MARKER");

        // The 500 path logged the raw value at ERROR with a stack trace, which is a disk-fill and
        // log injection from any authenticated caller including a VIEWER.
        assertThat(response.body()).doesNotContain("INJECTED-MARKER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "2"})
    void refusesAnEnumSentAsItsOrdinal(String ordinal) {
        seedHousehold();
        ApiClient owner = signIn(OWNER_EMAIL, OWNER_SECRET);

        ApiResponse response =
                owner.post(
                        "/api/households/current/invitations",
                        "{\"email\":\"chris@example.com\",\"role\":" + ordinal + "}");

        // {"role":0} bound to OWNER by ordinal. Both routes are OWNER-only so this was never
        // escalation by an outsider — it is a client sending a select's index or a
        // default-initialised int and silently creating an OWNER invitation.
        assertThat(response.status()).as("a role must be named, never numbered").isEqualTo(400);
        assertThat(response.body()).doesNotContain("\"role\":\"OWNER\"");
    }
}
