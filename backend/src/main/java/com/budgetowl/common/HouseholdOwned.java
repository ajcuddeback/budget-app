package com.budgetowl.common;

import java.util.UUID;

/**
 * Financial data owned by a household (ADR-0017), and the marker that makes the ownership rule
 * enforceable instead of merely documented.
 *
 * <p>Every entity implementing this carries a <b>direct</b> {@code household_id} column — not one
 * reachable through a join — so the scoping predicate in every query that touches it is a single
 * condition on the row itself. {@code ArchitectureTest} uses the marker twice: once to require that
 * every repository method reaching one of these entities takes a {@code householdId}, and once to
 * require that every new financial entity declares itself here. Without the second rule the first
 * would quietly stop applying the moment somebody forgot the marker, which is the failure mode
 * docs/memory/gotchas.md records for {@code allowEmptyShould}.
 *
 * <p>{@code UserAccount}, {@code Household}, {@code HouseholdMember} and {@code
 * HouseholdInvitation} are deliberately <em>not</em> household-owned data: they are the membership
 * graph that ownership is resolved <em>from</em>.
 */
public interface HouseholdOwned {

    /**
     * The household this row belongs to. Read from the stored column, never from a request — an id
     * supplied by a caller is a lookup key and never evidence of ownership (non-negotiable #2).
     */
    UUID householdId();
}
