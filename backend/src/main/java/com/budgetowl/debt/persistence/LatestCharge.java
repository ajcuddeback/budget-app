package com.budgetowl.debt.persistence;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The latest date on which a payment charged one debt a month's interest (ADR-0040).
 *
 * <p>Only payments on or after the anchor date are counted, because only those count against the
 * balance. The month this date falls in has been charged, so a projection starts after it.
 */
public record LatestCharge(UUID debtId, LocalDate paidDate) {}
