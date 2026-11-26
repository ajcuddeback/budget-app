package com.budgetowl.checkin.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The arithmetic of steps 1 and 3, with no database and no clock
 * (docs/features/weekly-check-in.md).
 *
 * <ul>
 *   <li><b>Unusual</b>: at least {@link #UNUSUAL_MULTIPLE} times the reference — a charge rule's
 *       bill amount, or else the median of at least {@link #MINIMUM_HISTORY} earlier outflows.
 *   <li><b>Prepaid</b>: within the bill's tolerance of a whole multiple {@code k}, {@code 2 ≤ k ≤}
 *       {@link #MAXIMUM_PREPAID_MULTIPLE}.
 *   <li><b>Recurring</b>: one outflow in each of the three calendar months ending with the
 *       window's, each within {@link #RECURRING_BAND} of the latest.
 * </ul>
 *
 * <p>Every amount here is a magnitude: callers pass {@code |t.amount|}. Each check is linear in the
 * rows it is given; the service indexes history by {@code (payee, account)} first, so nothing is
 * matched pairwise across the window (the slice-12 lesson).
 */
final class ChargeDetection {

    /** "A charge twice its normal size." Assumed in the feature doc. */
    static final BigDecimal UNUSUAL_MULTIPLE = BigDecimal.valueOf(2);

    /** Fewer earlier rows than this is not a history. Assumed in the feature doc. */
    static final int MINIMUM_HISTORY = 3;

    /** At most twelve months at once, so at most eleven skips. */
    static final int MAXIMUM_PREPAID_MULTIPLE = 12;

    /** "Each within 10% of the latest." Assumed in the feature doc. */
    static final BigDecimal RECURRING_BAND = new BigDecimal("0.10");

    /** Three months running. Assumed in the feature doc. */
    static final int RECURRING_MONTHS = 3;

    private ChargeDetection() {}

    /** The median of the magnitudes, or empty for fewer than {@link #MINIMUM_HISTORY}. */
    static Optional<BigDecimal> median(Collection<BigDecimal> magnitudes) {
        if (magnitudes.size() < MINIMUM_HISTORY) {
            return Optional.empty();
        }
        List<BigDecimal> sorted = new ArrayList<>(magnitudes);
        sorted.sort(null);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return Optional.of(sorted.get(middle));
        }
        return Optional.of(
                sorted.get(middle - 1)
                        .add(sorted.get(middle))
                        .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_EVEN));
    }

    /** At least twice the reference. A zero reference makes nothing unusual. */
    static boolean isUnusual(BigDecimal magnitude, BigDecimal reference) {
        return reference.signum() > 0
                && magnitude.compareTo(reference.multiply(UNUSUAL_MULTIPLE)) >= 0;
    }

    /**
     * Whether a charge pays a bill as it stands: the exact amount for a fixed bill, within the
     * tolerance for one that varies.
     *
     * @param tolerance null for a fixed bill
     */
    static boolean withinTolerance(
            BigDecimal magnitude, BigDecimal expected, BigDecimal tolerance) {
        BigDecimal allowed = tolerance == null ? BigDecimal.ZERO : tolerance;
        return magnitude.subtract(expected).abs().compareTo(allowed) <= 0;
    }

    /**
     * How many periods a charge pays for at once, or empty when it is not within tolerance of a
     * whole multiple from 2 to 12. The canvas's {@code $126} on a {@code $42} bill is 3.
     */
    static OptionalInt prepaidMultiple(
            BigDecimal magnitude, BigDecimal expected, BigDecimal tolerance) {
        if (expected.signum() <= 0) {
            return OptionalInt.empty();
        }
        int k = magnitude.divide(expected, 0, RoundingMode.HALF_EVEN).intValue();
        if (k < 2 || k > MAXIMUM_PREPAID_MULTIPLE) {
            return OptionalInt.empty();
        }
        BigDecimal allowed = tolerance == null ? BigDecimal.ZERO : tolerance;
        BigDecimal off = magnitude.subtract(expected.multiply(BigDecimal.valueOf(k))).abs();
        return off.compareTo(allowed) <= 0 ? OptionalInt.of(k) : OptionalInt.empty();
    }

    /**
     * Whether {@code latest} is the third of three monthly charges: one earlier outflow in each of
     * the two calendar months before {@code latest}'s, each within 10% of it.
     *
     * @param latestDate must be in {@code endMonth}; the caller has already checked it is inside
     *     the window
     * @param earlier this payee and account's earlier outflows, as dates and magnitudes
     */
    static boolean isRecurring(
            LocalDate latestDate,
            BigDecimal latest,
            YearMonth endMonth,
            Collection<DatedAmount> earlier) {
        if (!YearMonth.from(latestDate).equals(endMonth) || latest.signum() <= 0) {
            return false;
        }
        BigDecimal band = latest.multiply(RECURRING_BAND);
        for (int back = 1; back < RECURRING_MONTHS; back++) {
            YearMonth month = endMonth.minusMonths(back);
            boolean found = false;
            for (DatedAmount row : earlier) {
                if (YearMonth.from(row.date()).equals(month)
                        && row.magnitude().subtract(latest).abs().compareTo(band) <= 0) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** A date and a magnitude, for {@link #isRecurring}. */
    record DatedAmount(LocalDate date, BigDecimal magnitude) {

        @Override
        public String toString() {
            return "DatedAmount[date=" + date + "]";
        }
    }
}
