package com.budgetowl.checkin.persistence;

import com.budgetowl.category.domain.CategoryKind;
import java.util.UUID;

/** A category as a check-in needs it: for the picker, the spending rule, and the rule lists. */
public record CategoryRow(
        UUID id, String name, CategoryKind kind, UUID parentId, boolean archived) {

    @Override
    public String toString() {
        return "CategoryRow[id=" + id + "]";
    }
}
