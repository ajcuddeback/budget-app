package com.budgetowl.income.service;

import com.budgetowl.common.schedule.Recurrence;
import java.util.UUID;

/** An income source to create. {@code amount} is a decimal string (ADR-0006). */
public record NewIncome(String name, String amount, Recurrence recurrence, UUID accountId) {}
