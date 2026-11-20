package com.budgetowl.transaction.persistence;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One account's posted total, with the currency it is denominated in.
 *
 * <p>A constructor-expression projection rather than {@code Object[]}: the column order then cannot
 * be misread at the call site, and adding a column to the query is a compile error rather than an
 * index that silently means something else.
 *
 * @param currency the ACCOUNT's currency, carried because a transaction has none of its own — the
 *     caller needs it to build a {@code Money} and the only honest source is the account
 * @param total the sum of the account's signed amounts; never null for a row that appears at all,
 *     because an account with no transactions produces no group
 */
public record AccountTotal(UUID accountId, String currency, BigDecimal total) {}
