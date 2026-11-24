package com.budgetowl.debt.service;

import com.budgetowl.common.AmountText;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.household.service.MembershipService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Solves a term set without saving it: the terms editor's and the add form's live preview.
 *
 * <p><b>A pure calculation.</b> It reads no stored data at all — no debt, no plan, no household's
 * figures — so it discloses nothing, because it knows nothing. It is nevertheless <b>authenticated
 * and rate-limited</b>, for a cost reason rather than a confidentiality one: this is the first
 * endpoint in the app whose work depends on the numbers in the request, and the caps are what keep
 * one call bounded while {@link TermsSolveRateLimiter} keeps a loop of them bounded.
 *
 * <p>Not {@code @Transactional}: there is nothing to read and nothing to write. A transaction here
 * would be a database connection held for the length of a calculation, which is the opposite of
 * what is wanted.
 */
@Service
public class DebtTermsService {

    private final MembershipService memberships;
    private final DebtTermsSolver solver;
    private final TermsSolveRateLimiter rateLimiter;

    /**
     * How far from now a first payment month may be, in years.
     *
     * <p>Not a business rule — a bound, so an absurd magnitude is refused explicitly rather than
     * reaching {@code YearMonth.plusMonths} and leaving as a {@code 500}. A century either side
     * covers every debt anybody could mean.
     */
    private static final int FIRST_PAYMENT_MONTH_WINDOW_YEARS = 100;

    private final Clock clock;

    public DebtTermsService(
            MembershipService memberships,
            DebtTermsSolver solver,
            TermsSolveRateLimiter rateLimiter,
            Clock clock) {
        this.memberships = memberships;
        this.solver = solver;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /**
     * Fills in whichever of the three solvable quantities was left out.
     *
     * @throws com.budgetowl.common.ForbiddenException if the caller is not a member. A {@code
     *     VIEWER} may use this: it is arithmetic over a request body and stores nothing, so there
     *     is nothing for a read-only role to change
     * @throws com.budgetowl.common.RateLimitedException {@code 429}, with a finite {@code
     *     Retry-After}
     * @throws InvalidRequestException {@code terms-over-determined} or {@code terms-unsolvable}
     *     ({@code 422}), or {@code validation-failed} naming the field
     */
    public SolvedTermsView solve(UUID actorId, TermsToSolve requested) {
        memberships.requireMembership(actorId);
        rateLimiter.requireAllowed(actorId.toString());

        Currency currency = currencyNamed(requested.currency());
        Money balance =
                AmountText.parse(
                        requested.balance(), currency, AmountText.Sign.NOT_NEGATIVE, "balance");
        YearMonth firstPaymentMonth = firstPaymentMonth(requested);

        BigDecimal apr = requested.apr() == null ? null : rate(requested.apr());
        Money payment =
                requested.payment() == null
                        ? null
                        : AmountText.parse(
                                requested.payment(), currency, AmountText.Sign.POSITIVE, "payment");
        Horizon horizon = horizon(requested, firstPaymentMonth);

        DebtTerms terms = new DebtTerms(balance, apr, payment, horizon, firstPaymentMonth);
        if (terms.isOverDetermined()) {
            throw new InvalidRequestException(
                    ErrorCode.TERMS_OVER_DETERMINED,
                    "apr, payment and the horizon are three values for a two-value equation",
                    Map.of("fields", List.of("apr", "payment", "payoffMonth")));
        }
        if (terms.isUnderDetermined()) {
            // Two unknowns and one equation. A validation failure rather than a 422: the request
            // is incomplete, not semantically impossible.
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "give any three of the balance, the rate, the payment and the horizon",
                    Map.of("field", "apr"));
        }

        TermsSolution solution = solver.solve(terms);
        return switch (solution) {
            case TermsSolution.Solved solved -> view(solved, firstPaymentMonth);
            case TermsSolution.Unsolvable unsolvable ->
                    throw new InvalidRequestException(
                            ErrorCode.TERMS_UNSOLVABLE,
                            unsolvable.detail(),
                            Map.of("field", unsolvable.field().code()));
        };
    }

    // ------------------------------------------------------------------------------- internals

    private static SolvedTermsView view(TermsSolution.Solved solved, YearMonth firstPaymentMonth) {
        DebtTerms terms = solved.terms();
        if (solved.projection() instanceof Projection.Cleared cleared) {
            BalanceSeries.Series series =
                    BalanceSeries.of(
                            firstPaymentMonth,
                            BalanceSeries.openings(cleared.months()),
                            PayoffPlanService.MAXIMUM_SERIES_POINTS);
            return new SolvedTermsView(
                    terms.balance(),
                    terms.apr(),
                    terms.payment(),
                    cleared.monthsLeft(),
                    cleared.payoffMonth(),
                    cleared.totalInterest(),
                    solved.solvedField().code(),
                    null,
                    series.points(),
                    series.stride(),
                    solved.bisectionSteps());
        }
        // never-clears: accepted, reported, and never an error. A card's real minimum is a
        // percentage of the balance, so a household entering last month's flat figure lands here
        // legitimately.
        return new SolvedTermsView(
                terms.balance(),
                terms.apr(),
                terms.payment(),
                null,
                null,
                null,
                solved.solvedField().code(),
                ((Projection.None) solved.projection()).reason().code(),
                List.of(),
                1,
                solved.bisectionSteps());
    }

    /**
     * When the projection starts, bounded.
     *
     * <p>{@code YearMonth.parse} accepts {@code +999999999-12} under the ISO formatter, and the
     * solver then calls {@code plusMonths} on it — {@code DateTimeException}, which is not a {@code
     * DomainException}, so it left as a {@code 500} with a stack trace. A first payment month is a
     * date somebody could plausibly mean; a window of a century either side of now is more than
     * generous and anything outside it is a request with no answer in it.
     */
    private YearMonth firstPaymentMonth(TermsToSolve requested) {
        YearMonth now = YearMonth.from(LocalDate.now(clock));
        if (requested.firstPaymentMonth() == null) {
            return now;
        }
        YearMonth asked = requested.firstPaymentMonth();
        if (asked.isBefore(now.minusYears(FIRST_PAYMENT_MONTH_WINDOW_YEARS))
                || asked.isAfter(now.plusYears(FIRST_PAYMENT_MONTH_WINDOW_YEARS))) {
            throw new InvalidRequestException(
                    ErrorCode.TERMS_UNSOLVABLE,
                    "a first payment month more than "
                            + FIRST_PAYMENT_MONTH_WINDOW_YEARS
                            + " years from now",
                    Map.of("field", "firstPaymentMonth"));
        }
        return asked;
    }

    /**
     * The horizon, in whichever of its two faces arrived.
     *
     * <p>Both is a validation failure — they are one unknown, and two answers to it can disagree —
     * and a payoff month before the first payment month is {@code 422 terms-unsolvable}, because a
     * date in the past is a well-formed request with no answer in it.
     */
    private static Horizon horizon(TermsToSolve requested, YearMonth firstPaymentMonth) {
        if (requested.monthsLeft() != null && requested.payoffMonth() != null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "monthsLeft and payoffMonth are one unknown with two faces; send one",
                    Map.of("field", "payoffMonth"));
        }
        if (requested.monthsLeft() != null) {
            if (requested.monthsLeft() < 1) {
                throw new InvalidRequestException(
                        ErrorCode.VALIDATION_FAILED,
                        "a horizon is at least one month",
                        Map.of("field", "monthsLeft"));
            }
            if (requested.monthsLeft() > DebtTermsSolver.MAX_MONTHS) {
                throw new InvalidRequestException(
                        ErrorCode.TERMS_UNSOLVABLE,
                        "a horizon beyond " + DebtTermsSolver.MAX_MONTHS + " months",
                        Map.of("field", "monthsLeft"));
            }
            return Horizon.ofMonths(requested.monthsLeft());
        }
        if (requested.payoffMonth() == null) {
            return null;
        }
        if (!Horizon.isSolvableSpan(requested.payoffMonth(), firstPaymentMonth)) {
            throw new InvalidRequestException(
                    ErrorCode.TERMS_UNSOLVABLE,
                    "a payoff month outside the "
                            + DebtTermsSolver.MAX_MONTHS
                            + " months a projection covers",
                    Map.of("field", "payoffMonth"));
        }
        return Horizon.endingIn(requested.payoffMonth(), firstPaymentMonth);
    }

    private static BigDecimal rate(String text) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(text.strip());
        } catch (NumberFormatException malformed) {
            // Never echoed back: an APR is a statement about somebody's credit rating.
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unparseable rate", Map.of("field", "apr"));
        }
        if (parsed.signum() < 0 || parsed.compareTo(new BigDecimal("1000")) >= 0) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a rate is at least 0% and under 1000%",
                    Map.of("field", "apr"));
        }
        return parsed;
    }

    private static Currency currencyNamed(String code) {
        if (code == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a currency is required",
                    Map.of("field", "currency"));
        }
        try {
            return Currency.getInstance(code.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unknown currency", Map.of("field", "currency"));
        }
    }
}
