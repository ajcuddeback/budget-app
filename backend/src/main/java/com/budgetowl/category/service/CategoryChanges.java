package com.budgetowl.category.service;

import com.budgetowl.common.Change;
import java.util.UUID;

/**
 * A partial update. A null {@code name} was not mentioned and is left alone.
 *
 * <p><b>There is no {@code kind}.</b> It is fixed at creation — see {@code
 * ErrorCode.CATEGORY_KIND_IMMUTABLE} — so it is absent from this record rather than present and
 * rejected, which is the difference between a contract that cannot express the mistake and one that
 * accepts it and apologises.
 *
 * <p>The parent is a {@link Change} rather than a nullable {@code UUID} because "not mentioned" and
 * "move this to the top level" are different instructions and a null cannot be both.
 */
public record CategoryChanges(String name, Change<UUID> parent, long version) {}
