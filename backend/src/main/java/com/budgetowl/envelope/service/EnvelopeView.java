package com.budgetowl.envelope.service;

import com.budgetowl.common.Money;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

/**
 * One envelope, with the figure the whole feature exists to produce.
 *
 * @param remaining {@code amount} minus {@code spent}, computed on read and never stored. <b>The
 *     headline on every envelope surface</b>, because it is the number a person acts on while still
 *     standing in the shop. It goes NEGATIVE when an envelope is overspent — that is information,
 *     not an error state, and nothing clamps it to zero
 * @param empty whether the envelope has no entries at all. Not the same fact as {@code spent} being
 *     zero: entries are always positive, so a zero sum means no entries, but a client should not
 *     have to know that to tell "nothing logged yet" from "nothing left"
 * @param categoryId null for an envelope with no category, which is ordinary — "Adhoc" is a
 *     perfectly good envelope
 */
public record EnvelopeView(
        UUID id,
        String name,
        YearMonth period,
        UUID categoryId,
        String categoryName,
        String categoryParentName,
        Money amount,
        Money spent,
        Money remaining,
        long entryCount,
        boolean empty,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
