package com.budgetowl.household.domain;

/**
 * What a member may do in the household (ADR-0017). Stored as text and constrained by {@code
 * ck_household_members_role}, so a value this enum does not know cannot reach the table.
 *
 * <p>Since ADR-0026 removed multi-household deployments, role enforcement is the highest-value
 * authorization control in the codebase rather than one of two.
 */
public enum HouseholdRole {
    /** Everything, including invitations, role changes, removal and deletion. The operator. */
    OWNER,
    /** Reads and writes financial data. */
    MEMBER,
    /** Reads only. Never writes, on any endpoint. */
    VIEWER;

    public boolean canWrite() {
        return this != VIEWER;
    }

    public boolean isOwner() {
        return this == OWNER;
    }
}
