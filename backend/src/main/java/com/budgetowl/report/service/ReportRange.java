package com.budgetowl.report.service;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A run of whole calendar months, inclusive at both ends: {@code 2025-09..2026-08} is twelve.
 *
 * <p>A range that exists is valid: {@code from} is not after {@code to}, it spans at most {@link
 * #MAXIMUM_MONTHS}, and neither it nor the range before it reaches before year 1. That is checked
 * here, in the service, <b>before any query is built</b> (docs/features/reporting.md, attack 4).
 * The four-digit shape of each end is the edge's job, so {@code YearMonth} never sees {@code
 * +10000-01} (attack 5).
 */
public record ReportRange(YearMonth from, YearMonth to) {

    /**
     * The longest range one request may ask for. Twelve months beside the same twelve a year
     * earlier, and it bounds every response without pagination (decision 11).
     */
    public static final int MAXIMUM_MONTHS = 24;

    /**
     * The earliest month any scan may reach. Year 0 is a real {@code LocalDate} in Java and not a
     * year in PostgreSQL, which has no year zero; refusing it here keeps {@code 0000-01} a {@code
     * 422} rather than a question about how a driver renders 1 BC.
     */
    private static final YearMonth EARLIEST = YearMonth.of(1, 1);

    public ReportRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to) || from.isBefore(EARLIEST)) {
            throw new InvalidRequestException(
                    ErrorCode.REPORT_RANGE_INVALID, "report range is not a range", Map.of());
        }
        if (ChronoUnit.MONTHS.between(from, to) + 1 > MAXIMUM_MONTHS) {
            throw new InvalidRequestException(
                    ErrorCode.REPORT_RANGE_TOO_LONG,
                    "report range is longer than the maximum",
                    Map.of("maximumMonths", MAXIMUM_MONTHS));
        }
    }

    /** How many months, counting both ends. */
    public int months() {
        return Math.toIntExact(ChronoUnit.MONTHS.between(from, to) + 1);
    }

    /**
     * The range of the same length that ends the month before this one starts. August is compared
     * with July; June to August with March to May.
     *
     * @throws InvalidRequestException when that range would reach before year 1
     */
    public ReportRange preceding() {
        return new ReportRange(from.minusMonths(months()), from.minusMonths(1));
    }

    /** The first day of the first month: the inclusive lower bound of a scan. */
    public LocalDate firstDay() {
        return from.atDay(1);
    }

    /** The first day of the month after the last: the exclusive upper bound of a scan. */
    public LocalDate endExclusive() {
        return to.plusMonths(1).atDay(1);
    }

    /** Every month in order, so a series can be dense. */
    public List<YearMonth> eachMonth() {
        List<YearMonth> months = new ArrayList<>(months());
        for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
            months.add(month);
        }
        return List.copyOf(months);
    }
}
