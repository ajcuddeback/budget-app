package com.budgetowl.category.service;

import java.util.UUID;

/**
 * A category to create.
 *
 * <p>{@code kind} is ignored when {@code parentId} is set: a child takes its parent's kind, because
 * two sources for one fact are two sources that can disagree. The service rejects a request that
 * states both and contradicts itself rather than silently preferring one.
 *
 * @param parentId null for a top-level category
 * @param kind required for a top-level category; for a child it must match the parent's or be null
 */
public record NewCategory(String name, String kind, UUID parentId) {}
