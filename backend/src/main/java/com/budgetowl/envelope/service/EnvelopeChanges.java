package com.budgetowl.envelope.service;

import com.budgetowl.common.Change;
import java.util.UUID;

/**
 * A partial update. A null {@code name} or {@code amount} was not mentioned.
 *
 * <p><b>There is no {@code period}.</b> Moving an envelope between months would carry its entries
 * with it and change what two periods' totals mean. The next month's envelope is a new envelope,
 * which is also what makes two months comparable.
 */
public record EnvelopeChanges(String name, String amount, Change<UUID> categoryId, long version) {}
