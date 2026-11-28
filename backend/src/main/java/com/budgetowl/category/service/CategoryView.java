package com.budgetowl.category.service;

import com.budgetowl.category.domain.CategoryKind;
import java.time.Instant;
import java.util.UUID;

/**
 * One category, flat.
 *
 * <p>Flat rather than nested because the response is paginated and a page boundary must not be able
 * to fall between a parent and its children (docs/guides/api-style.md). {@code parentId} is enough
 * for the client to assemble the tree, and {@code parentName} spares it a second lookup when it
 * renders a child on its own — in a transaction row, a picker's selected value, or a search result,
 * where "Utilities" alone is ambiguous and "Bills → Utilities" is not.
 *
 * @param parentId null for a top-level category
 * @param parentName null for a top-level category; the parent's name otherwise
 */
public record CategoryView(
        UUID id,
        String name,
        CategoryKind kind,
        UUID parentId,
        String parentName,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
