package com.budgetowl.bill.service;

import com.budgetowl.bill.domain.OccurrenceStatus;
import java.time.LocalDate;

/**
 * What a household is recording about one occurrence.
 *
 * <p><b>No version.</b> This is a statement of fact about the world — "we paid this" — not an edit
 * of a field two people might be holding different copies of, and the same statement made twice is
 * the same statement. The endpoint is a {@code PUT} for exactly that reason (ADR-0032).
 *
 * @param actualAmount what was really paid, for {@code PAID}. Null means "what was budgeted", so
 *     the common case is one tap
 * @param paidDate when, for {@code PAID}. Null means today
 */
public record StatusRecord(OccurrenceStatus status, String actualAmount, LocalDate paidDate) {}
