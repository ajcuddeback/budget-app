package com.budgetowl.envelope.service;

import java.util.List;

/** One page of an envelope's log. */
public record EnvelopeEntryPage(
        List<EnvelopeEntryView> entries, int page, int size, long totalElements, int totalPages) {}
