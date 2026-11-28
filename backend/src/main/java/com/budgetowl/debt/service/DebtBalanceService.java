package com.budgetowl.debt.service;

import com.budgetowl.bill.domain.Bill;
import com.budgetowl.bill.persistence.BillRepository;
import com.budgetowl.common.Money;
import com.budgetowl.debt.domain.BalanceSource;
import com.budgetowl.debt.domain.Debt;
import com.budgetowl.debt.persistence.DebtPaymentRepository;
import com.budgetowl.debt.persistence.DebtRepository;
import com.budgetowl.debt.persistence.MonthInterestRow;
import com.budgetowl.debt.persistence.PaymentActivity;
import com.budgetowl.debt.persistence.PrincipalSinceAnchor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each debt is actually worth today, and at what rate.
 *
 * <p>The one place either figure is derived. Every screen, the payoff ladder and the Bills &amp;
 * Income summary read their balances through here, so there is a single definition of "what is
 * owed" — a second derivation would be a second answer, and the two would differ exactly when it
 * mattered.
 *
 * <p><b>Three figures, in this order, and the order is forced:</b>
 *
 * <ol>
 *   <li><b>The anchored balance</b> — {@code anchorBalance − Σ principal} over the payments on or
 *       after the anchor date. The anchor is the figure as at the <em>start</em> of its date, so a
 *       payment made on that date applies to it; {@code
 *       DebtPaymentRepository.principalPaidSinceAnchor} is where the two exclusions that make that
 *       exact are written out. One query for the whole household, not one per debt.
 *   <li><b>The rate</b> — the entered one, or solved from the entered payment and horizon
 *       <em>against the anchored balance</em>. It has to be this balance: estimation needs a rate
 *       and solving a rate needs a balance, and taking the pre-estimate figure is the one order
 *       that terminates.
 *   <li><b>The estimate</b> — the anchored balance rolled forward over whole months with no logged
 *       payment, applying the same interest formula as everything else and assuming the minimum was
 *       paid. An estimate is <b>not</b> the anchor minus the payments: the designs' own estimate
 *       screen forgets the interest in between, and this does not (see the feature doc's
 *       design-arithmetic note).
 * </ol>
 *
 * <p><b>There is no background job.</b> {@link BalanceSource#ESTIMATED} is computed on read and
 * capped, so no balance moves while nobody is looking and there is no scheduled task to attack.
 *
 * <p>Read-only throughout: nothing here writes, and nothing here resolves a household — the caller
 * has already done that through its membership, and passes the id in.
 */
@Service
public class DebtBalanceService {

    /**
     * How many months the app will assume on a household's behalf.
     *
     * <p>Twelve, because {@code ESTIMATED} is the one place a number the app invented sits in the
     * headline position, and extrapolating a year of guesses into a second year is a refusal
     * dressed as a feature. Past this the debt reports its last confirmed balance and that date,
     * and the screen asks for a statement figure.
     */
    public static final int MAXIMUM_ESTIMATED_MONTHS = 12;

    private final DebtRepository debts;
    private final DebtPaymentRepository payments;
    private final BillRepository bills;
    private final DebtTermsSolver solver;
    private final Clock clock;

    public DebtBalanceService(
            DebtRepository debts,
            DebtPaymentRepository payments,
            BillRepository bills,
            DebtTermsSolver solver,
            Clock clock) {
        this.debts = debts;
        this.payments = payments;
        this.bills = bills;
        this.solver = solver;
        this.clock = clock;
    }

    /** The clock, read once per request by whoever needs today's date. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * The same clock as an instant, for the two rows that record <em>when</em> something was done:
     * a snapshot's {@code takenAt} and a debt's {@code paidOffAt}. Injected rather than called
     * statically so a test can fix time instead of waiting for it.
     */
    public java.time.Instant now() {
        return clock.instant();
    }

    /** The solver, shared rather than constructed twice: it is stateless and has no identity. */
    public DebtTermsSolver solver() {
        return solver;
    }

    /**
     * Every debt in the household, with its derived balance and rate.
     *
     * @param includeArchived archived debts are still listed when asked for — they hold the history
     *     of what was paid against them — and never take part in the plan
     */
    @Transactional(readOnly = true)
    public List<DebtFacts> factsFor(UUID householdId, boolean includeArchived) {
        List<Debt> found = debts.findAllForHousehold(householdId, includeArchived);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> principalPaid = new HashMap<>();
        for (PrincipalSinceAnchor rollup : payments.principalPaidSinceAnchor(householdId)) {
            principalPaid.put(rollup.debtId(), rollup.principalPaid());
        }
        Map<UUID, PaymentActivity> activity = new HashMap<>();
        for (PaymentActivity row : payments.activity(householdId)) {
            activity.put(row.debtId(), row);
        }

        LocalDate today = today();
        YearMonth thisMonth = YearMonth.from(today);
        List<MonthInterestRow> thisMonthsRows =
                payments.monthInterestRowsForHousehold(
                        householdId, thisMonth.atDay(1), thisMonth.atEndOfMonth());
        Map<UUID, Bill> linkedBills = linkedBills(householdId, found);
        List<DebtFacts> facts = new ArrayList<>(found.size());
        for (Debt debt : found) {
            facts.add(
                    factsFor(
                            debt,
                            principalPaid.get(debt.id()),
                            activity.get(debt.id()),
                            linkedBills.get(debt.billId().orElse(null)),
                            thisMonthsRows,
                            today));
        }
        return List.copyOf(facts);
    }

    /**
     * One debt, for the response to a write.
     *
     * <p><b>It runs the household-wide aggregates and picks this debt out in Java.</b> The comment
     * here used to claim the opposite — that it queries one row — and it was worth correcting
     * rather than deleting, because the claim is the one a reader would trust when the payment
     * table is large: the cost of this call grows with the household's whole history, not with this
     * debt's, and it is on the path of every write response and every detail read.
     *
     * <p>Acceptable because {@code DebtService.MAXIMUM_PAYMENTS_PER_DEBT} bounds that history and
     * both aggregates are grouped in SQL — but it is reusing the batched queries for one row, not a
     * narrower query, and a per-debt variant is the thing to add if this ever shows up in a
     * profile.
     */
    @Transactional(readOnly = true)
    public DebtFacts factsFor(Debt debt) {
        UUID householdId = debt.householdId();
        BigDecimal principalPaid = null;
        for (PrincipalSinceAnchor rollup : payments.principalPaidSinceAnchor(householdId)) {
            if (rollup.debtId().equals(debt.id())) {
                principalPaid = rollup.principalPaid();
            }
        }
        PaymentActivity activity = null;
        for (PaymentActivity row : payments.activity(householdId)) {
            if (row.debtId().equals(debt.id())) {
                activity = row;
            }
        }
        Bill bill =
                debt.billId()
                        .flatMap(id -> bills.findByIdAndHouseholdId(id, householdId))
                        .orElse(null);
        LocalDate today = today();
        YearMonth thisMonth = YearMonth.from(today);
        List<MonthInterestRow> thisMonthsRows =
                payments.monthInterestRows(
                        householdId, debt.id(), thisMonth.atDay(1), thisMonth.atEndOfMonth());
        return factsFor(debt, principalPaid, activity, bill, thisMonthsRows, today);
    }

    // ------------------------------------------------------------------------------- internals

    private DebtFacts factsFor(
            Debt debt,
            BigDecimal principalPaid,
            PaymentActivity activity,
            Bill linkedBill,
            List<MonthInterestRow> thisMonthsRows,
            LocalDate today) {
        Money anchored =
                principalPaid == null
                        ? debt.anchorBalance()
                        : debt.anchorBalance().minus(Money.of(principalPaid, debt.currency()));

        LocalDate lastPaid = activity == null ? null : activity.latestPaidDate();
        long paymentCount = activity == null ? 0L : activity.paymentCount();
        YearMonth firstPaymentMonth = firstPaymentMonth(linkedBill, lastPaid, today);
        // This month so far (ADR-0043): whether its interest is booked, and what was paid towards
        // it. A projection that starts this month plans only what is still to come.
        YearMonth thisMonth = YearMonth.from(today);
        boolean booked = MonthInterest.isBooked(debt.id(), thisMonth, thisMonthsRows, null);
        Money paid = MonthInterest.paidIn(debt.id(), thisMonth, thisMonthsRows, debt.currency());
        FirstMonth first =
                firstPaymentMonth.equals(thisMonth)
                        ? new FirstMonth(booked, paid)
                        : FirstMonth.untouched(debt.currency());

        Rate rate = rate(debt, anchored, firstPaymentMonth, first);
        Estimate estimate = estimate(debt, anchored, rate.apr(), lastPaid, today);

        return new DebtFacts(
                debt,
                estimate.balance(),
                anchored,
                estimate.source(),
                estimate.asOf(),
                estimate.months(),
                estimate.stale(),
                rate.apr(),
                rate.source(),
                rate.derivable(),
                paymentCount,
                lastPaid,
                firstPaymentMonth,
                thisMonth,
                booked,
                paid);
    }

    /**
     * The rate the arithmetic uses.
     *
     * <p>Entered, or solved from the entered payment and horizon — the installment-loan case where
     * a statement gives a balance, a payment and a term but never names the rate.
     *
     * <p><b>When it can be neither.</b> A payment that clears this balance before its horizon at
     * every rate under 1000% implies no rate at all, and a balance falls as payments are logged, so
     * a debt that solved on Monday can stop solving on Friday. The rate is then reported as not
     * available and <b>zero is used for ranking only</b>, so the debt keeps its minimum and its
     * place in the ladder rather than vanishing from the plan. Ranking it as 0% puts it last under
     * avalanche, which is recorded as a known gap rather than hidden.
     */
    private Rate rate(Debt debt, Money anchored, YearMonth firstPaymentMonth, FirstMonth first) {
        Optional<BigDecimal> entered = debt.apr();
        if (entered.isPresent()) {
            return new Rate(entered.get(), TermSource.ENTERED, true);
        }
        Horizon horizon = horizonOf(debt, firstPaymentMonth);
        if (debt.payment().isEmpty() || horizon == null || !anchored.isPositive()) {
            return new Rate(BigDecimal.ZERO, TermSource.SOLVED, false);
        }
        TermsSolution solution =
                solver.solve(
                        DebtTerms.solvingApr(
                                        anchored,
                                        debt.payment().orElseThrow(),
                                        horizon,
                                        firstPaymentMonth)
                                .withFirstMonth(first));
        return switch (solution) {
            case TermsSolution.Solved solved ->
                    new Rate(solved.terms().apr(), TermSource.SOLVED, true);
            case TermsSolution.Unsolvable ignored ->
                    new Rate(BigDecimal.ZERO, TermSource.SOLVED, false);
        };
    }

    /**
     * The entered horizon, in whichever face it was stored, or null when it was not entered.
     *
     * <p>A payoff month before the first payment month is a horizon in the past. It is {@code
     * terms-unsolvable} where it <em>arrives</em>, and simply absent here — a stored row that has
     * aged past its own payoff month must not make every read of it throw.
     */
    static Horizon horizonOf(Debt debt, YearMonth firstPaymentMonth) {
        if (debt.monthsLeft().isPresent()) {
            int months = debt.monthsLeft().orElseThrow();
            return months > DebtTermsSolver.MAX_MONTHS ? null : Horizon.ofMonths(months);
        }
        // isSolvableSpan, not merely !isBefore: a payoff month far in the future is as unanswerable
        // as one in the past, and asking the shared predicate is what keeps Horizon.endingIn from
        // being reached with a span it has to throw about.
        return debt.payoffMonth()
                .filter(month -> Horizon.isSolvableSpan(month, firstPaymentMonth))
                .map(month -> Horizon.endingIn(month, firstPaymentMonth))
                .orElse(null);
    }

    /**
     * Rolls the balance forward over the months nobody logged a payment in.
     *
     * <p>Whole months only, counted from the later of the anchor and the last payment, and never
     * past today. A month with a payment in it is a month that needs no assumption, which is what
     * counting from the last payment achieves without a second query.
     */
    private Estimate estimate(
            Debt debt, Money anchored, BigDecimal apr, LocalDate lastPaid, LocalDate today) {
        LocalDate from =
                lastPaid == null || lastPaid.isBefore(debt.anchorDate())
                        ? debt.anchorDate()
                        : lastPaid;
        long months = ChronoUnit.MONTHS.between(from, today);
        if (months <= 0 || !anchored.isPositive() || debt.isPaidOff()) {
            return new Estimate(anchored, debt.balanceSource(), from, 0, false);
        }
        if (months > MAXIMUM_ESTIMATED_MONTHS) {
            // Reported as stale rather than estimated: the figure is the last one somebody
            // confirmed, and the screen asks for a statement instead of extrapolating a year of
            // guesses into a second one.
            return new Estimate(
                    anchored, debt.balanceSource(), from, Math.toIntExact(months), true);
        }
        // Bounded just above, so it fits an int; the loop counter and its limit are then the same
        // width (CodeQL java/comparison-with-wider-type flagged an int counter against a long).
        int monthCount = Math.toIntExact(months);
        Money balance = anchored;
        Money minimum = debt.minimumPayment();
        for (int month = 1; month <= monthCount; month++) {
            balance = balance.plus(solver.interestFor(balance, apr)).minus(minimum);
            if (!balance.isPositive()) {
                // The assumed minimums have cleared it. Estimating past zero would invent an
                // overpayment nobody made.
                balance = Money.zero(debt.currency());
                break;
            }
        }
        return new Estimate(balance, BalanceSource.ESTIMATED, today, monthCount, false);
    }

    /**
     * The month the projection's first period starts in — the debt's next payment date.
     *
     * <p>The linked bill's next occurrence if there is one, otherwise the day-of-month of the most
     * recent payment, otherwise today. Month-end clamps the way a bill schedule does: the 31st
     * becomes the 28th in February.
     */
    private static YearMonth firstPaymentMonth(
            Bill linkedBill, LocalDate lastPaid, LocalDate today) {
        if (linkedBill != null) {
            Optional<LocalDate> next = nextOccurrence(linkedBill, today);
            if (next.isPresent()) {
                return YearMonth.from(next.orElseThrow());
            }
        }
        if (lastPaid != null) {
            LocalDate candidate = clampTo(YearMonth.from(today), lastPaid.getDayOfMonth());
            return YearMonth.from(
                    candidate.isBefore(today)
                            ? clampTo(
                                    YearMonth.from(today).plusMonths(1), candidate.getDayOfMonth())
                            : candidate);
        }
        return YearMonth.from(today);
    }

    /**
     * The bill's next due date on or after today, looked for in this month and the next.
     *
     * <p>Two months is enough for every schedule that projects anything: the longest gap between
     * two occurrences of a {@code MONTHLY}, {@code WEEKLY} or {@code FORTNIGHTLY} bill is under a
     * month, and a {@code YEARLY} one outside its month — like an {@code IRREGULAR} one, which
     * projects nothing at all — falls through to the payment-date rule rather than making this scan
     * twelve months to find out.
     */
    private static Optional<LocalDate> nextOccurrence(Bill bill, LocalDate today) {
        if (!bill.isActive()) {
            return Optional.empty();
        }
        YearMonth month = YearMonth.from(today);
        for (YearMonth candidate : List.of(month, month.plusMonths(1))) {
            for (LocalDate date : bill.recurrence().datesIn(candidate)) {
                if (!date.isBefore(today)) {
                    return Optional.of(date);
                }
            }
        }
        return Optional.empty();
    }

    private static LocalDate clampTo(YearMonth month, int dayOfMonth) {
        return month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
    }

    private Map<UUID, Bill> linkedBills(UUID householdId, List<Debt> found) {
        Map<UUID, Bill> byId = new HashMap<>();
        for (Debt debt : found) {
            debt.billId()
                    .filter(id -> !byId.containsKey(id))
                    .flatMap(id -> bills.findByIdAndHouseholdId(id, householdId))
                    .ifPresent(bill -> byId.put(bill.id(), bill));
        }
        return byId;
    }

    private record Rate(BigDecimal apr, TermSource source, boolean derivable) {}

    private record Estimate(
            Money balance, BalanceSource source, LocalDate asOf, int months, boolean stale) {}
}
