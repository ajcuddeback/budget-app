package com.budgetowl.auth.domain;

/**
 * Whether a user may authenticate at all. Stored as text, constrained by {@code ck_users_status}.
 */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
