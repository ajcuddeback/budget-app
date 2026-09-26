package com.budgetowl.instance.web;

import com.budgetowl.auth.service.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for first-run setup. Records, validated at the edge, never entities — binding an
 * entity to a request body is how mass assignment happens (docs/guides/api-style.md).
 */
public final class SetupRequests {

    private SetupRequests() {}

    /**
     * @param password the length floor is a policy the service re-checks with the bundled breached
     *     list; the ceiling is BCrypt's, which ignores anything past 72 bytes
     */
    public record CreateFirstUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Size(min = PasswordPolicy.MINIMUM_LENGTH, max = PasswordPolicy.MAXIMUM_BYTES)
                    String password,
            @NotBlank @Size(max = 100) String householdName,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String baseCurrency) {}
}
