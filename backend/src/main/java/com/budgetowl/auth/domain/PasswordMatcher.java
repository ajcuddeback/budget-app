package com.budgetowl.auth.domain;

/**
 * Compares a submitted password against an encoded one.
 *
 * <p>Exists so {@link PasswordCredential} can verify a password without ever handing the encoded
 * form back to its caller, and without the domain depending on Spring Security. The service layer
 * supplies {@code passwordEncoder::matches}.
 *
 * <p>Implementations must be constant-time with respect to the encoded value — every adaptive
 * encoder Spring Security ships already is.
 */
@FunctionalInterface
public interface PasswordMatcher {

    boolean matches(CharSequence rawPassword, String encodedPassword);
}
