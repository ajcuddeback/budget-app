package com.budgetowl.checkin.persistence;

import java.time.LocalDate;
import java.util.UUID;

/** An unarchived envelope's category in one period: spending there is already in {@code left}. */
public record EnvelopeCategory(LocalDate period, UUID categoryId) {}
