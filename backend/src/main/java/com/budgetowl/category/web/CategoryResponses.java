package com.budgetowl.category.web;

import com.budgetowl.category.domain.CategoryKind;
import java.time.Instant;
import java.util.UUID;

/** Response bodies for the category endpoints. The entity never appears in one. */
public final class CategoryResponses {

    private CategoryResponses() {}

    /**
     * One category, flat.
     *
     * <p><b>The tree is not nested in the response.</b> Every collection is paginated
     * (docs/guides/api-style.md) and a page boundary must not be able to fall between a parent and
     * its children, so the client assembles the tree from {@code parentId}. The service caps a
     * household at the maximum page size, so one request always returns the whole tree.
     *
     * <p>{@code name} and {@code parentName} are returned separately and never joined, for the
     * reason {@code AccountResponse} gives about {@code name} and {@code lastFour}: the client
     * composes {@code Bills → Utilities}, because a pre-joined label cannot be re-ordered for a
     * right-to-left locale (ADR-0023).
     *
     * @param parentId null for a top-level category
     * @param parentName null for a top-level category; present so a child can be rendered on its
     *     own without a second lookup
     * @param version what to send back on a {@code PATCH}
     */
    public record CategoryResponse(
            UUID id,
            String name,
            CategoryKind kind,
            UUID parentId,
            String parentName,
            boolean archived,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        /** Redacted: a category name is whatever the user typed, and MVC logs responses at DEBUG. */
        @Override
        public String toString() {
            return "CategoryResponse[id=" + id + ", kind=" + kind + ", archived=" + archived + "]";
        }
    }
}
