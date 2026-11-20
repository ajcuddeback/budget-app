package com.budgetowl.category.service;

import java.util.List;

/** One page of categories, plus the counts the envelope needs. Mirrors {@code AccountPage}. */
public record CategoryPage(
        List<CategoryView> categories, int page, int size, long totalElements, int totalPages) {}
