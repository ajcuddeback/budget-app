package com.budgetowl.auth.service;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * What counts as an acceptable password: <b>length beats complexity</b>
 * (docs/architecture/security-model.md, and NIST agrees).
 *
 * <ul>
 *   <li>At least 12 characters. No composition rules — they push people towards {@code Passw0rd!}
 *       and nowhere else.
 *   <li>At most 72 <em>bytes</em>. Not a policy choice: BCrypt ignores everything past 72 bytes,
 *       and Spring Security's encoder throws rather than silently truncating. Rejecting it at the
 *       edge turns a 500 into a 400 that says which field.
 *   <li>Not on the bundled breached list. Shipped as a file rather than fetched, because nothing in
 *       the core may require a service we operate or an internet connection (ADR-0016).
 * </ul>
 *
 * <p>The failure never echoes the password back in its message or params.
 */
@Component
public class PasswordPolicy {

    public static final int MINIMUM_LENGTH = 12;
    public static final int MAXIMUM_BYTES = 72;

    private static final String BREACHED_LIST = "security/breached-passwords.txt";

    private final Set<String> breached;

    public PasswordPolicy() {
        this.breached = loadBreachedList();
    }

    /**
     * @throws InvalidRequestException when the password may not be used
     */
    public void requireAcceptable(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MINIMUM_LENGTH) {
            throw refusal("too-short");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
            throw refusal("too-long");
        }
        if (breached.contains(rawPassword.toLowerCase(Locale.ROOT))) {
            throw refusal("breached");
        }
    }

    private InvalidRequestException refusal(String reason) {
        return new InvalidRequestException(
                ErrorCode.PASSWORD_UNACCEPTABLE,
                "password refused: " + reason,
                Map.of(
                        "reason", reason,
                        "minimumLength", MINIMUM_LENGTH,
                        "maximumBytes", MAXIMUM_BYTES));
    }

    private static Set<String> loadBreachedList() {
        Set<String> entries = new HashSet<>();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                new ClassPathResource(BREACHED_LIST).getInputStream(),
                                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String entry = line.strip().toLowerCase(Locale.ROOT);
                if (!entry.isEmpty() && !entry.startsWith("#")) {
                    entries.add(entry);
                }
            }
        } catch (IOException cannotRead) {
            throw new IllegalStateException(
                    "the bundled breached-password list is missing from the build", cannotRead);
        }
        return Set.copyOf(entries);
    }
}
