package com.budgetowl.common.web;

import java.util.List;

/**
 * The collection envelope every list endpoint returns, from day one — retrofitting pagination is a
 * breaking change (docs/guides/api-style.md).
 *
 * <p>Default size 50, maximum 200, and a request over the maximum is <b>clamped, not rejected</b>.
 */
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAXIMUM_SIZE = 200;

    public PageResponse {
        content = List.copyOf(content);
    }

    /**
     * Pages an already-materialised list.
     *
     * <p>Honest about what it is: the collections paged this way are one household's members,
     * devices and invitations, all bounded by how many people live in a house. A financial
     * collection is not allowed to do this — it pages in the query.
     */
    public static <T> PageResponse<T> of(List<T> all, int requestedPage, int requestedSize) {
        int size = Math.clamp(requestedSize <= 0 ? DEFAULT_SIZE : requestedSize, 1, MAXIMUM_SIZE);
        int page = Math.max(requestedPage, 0);
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        // Integer ceiling. ADR-0006 bans floating point from this codebase, and a page
        // count computed with a double is the same mistake in a smaller place.
        int totalPages = (all.size() + size - 1) / size;
        return new PageResponse<>(all.subList(from, to), page, size, all.size(), totalPages);
    }
}
