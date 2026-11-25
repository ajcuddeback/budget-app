package com.budgetowl.imports.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;

/**
 * The hand-written statement corpus under {@code src/test/resources/import/}. Every file in it is
 * invented: no real person's data, however anonymised it looks (docs/features/file-import.md,
 * testing notes).
 */
public final class ImportFixtures {

    public static final Currency GBP = Currency.getInstance("GBP");

    /** 25 November 2026, so the corpus's August dates are in range and stay there. */
    public static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);

    private ImportFixtures() {}

    public static byte[] bytes(String name) {
        try (InputStream in = ImportFixtures.class.getResourceAsStream("/import/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    public static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    public static ImportReader reader() {
        return new ImportReader(ImportLimits.DECIDED, CLOCK, System::nanoTime);
    }

    public static ReadOptions none() {
        return new ReadOptions(null, null, false, null, null, null, null);
    }

    public static ReadOptions lastFour(String lastFour) {
        return new ReadOptions(null, null, false, null, null, null, lastFour);
    }
}
