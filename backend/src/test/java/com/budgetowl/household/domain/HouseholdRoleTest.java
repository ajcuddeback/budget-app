package com.budgetowl.household.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HouseholdRoleTest {

    @Test
    void aViewerReadsAndNeverWrites() {
        // ADR-0026 made this the highest-value authorization check in the codebase: there is no
        // second household to be kept out of, so the role is the boundary.
        assertThat(HouseholdRole.VIEWER.canWrite()).isFalse();
        assertThat(HouseholdRole.MEMBER.canWrite()).isTrue();
        assertThat(HouseholdRole.OWNER.canWrite()).isTrue();
    }

    @Test
    void onlyTheOwnerIsTheOwner() {
        assertThat(HouseholdRole.OWNER.isOwner()).isTrue();
        assertThat(HouseholdRole.MEMBER.isOwner()).isFalse();
        assertThat(HouseholdRole.VIEWER.isOwner()).isFalse();
    }

    @Test
    void thereAreExactlyThreeRoles() {
        // ck_household_members_role names these three. Adding a fourth without a migration would
        // make every insert of it fail at runtime, so fail here instead.
        assertThat(HouseholdRole.values())
                .containsExactly(HouseholdRole.OWNER, HouseholdRole.MEMBER, HouseholdRole.VIEWER);
    }
}
