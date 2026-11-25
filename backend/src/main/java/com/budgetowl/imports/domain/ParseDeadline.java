package com.budgetowl.imports.domain;

import com.budgetowl.common.ErrorCode;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The parse's wall-clock budget (rule 3, "Parse deadline").
 *
 * <p>Read from a monotonic source rather than the {@code Clock} the rest of the app injects: a
 * deadline is about elapsed time, and a wall clock that steps backwards would extend it. Checked
 * every {@link ImportLimits#DEADLINE_STRIDE} records, so the check costs nothing measurable and a
 * pathological file still cannot run long between two checks — every record is bounded by the line,
 * leaf and element caps.
 */
public final class ParseDeadline {

    private final LongSupplier nanoTime;
    private final long expiresAt;

    public ParseDeadline(Duration budget, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.expiresAt = nanoTime.getAsLong() + budget.toNanos();
    }

    /**
     * @param records how many records have been read so far
     * @throws ImportRefusal {@code import-file-too-complex} once the budget is spent
     */
    public void check(int records) {
        if (records % ImportLimits.DEADLINE_STRIDE == 0) {
            checkNow();
        }
    }

    /** An unconditional check, for the boundaries between parse phases. */
    public void checkNow() {
        if (nanoTime.getAsLong() - expiresAt > 0) {
            throw ImportRefusal.of(ErrorCode.IMPORT_FILE_TOO_COMPLEX);
        }
    }
}
