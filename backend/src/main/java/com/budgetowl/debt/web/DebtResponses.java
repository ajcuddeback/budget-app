package com.budgetowl.debt.web;

import com.budgetowl.common.Money;
import com.budgetowl.debt.service.DebtDetailView;
import com.budgetowl.debt.service.DebtPaymentView;
import com.budgetowl.debt.service.DebtView;
import com.budgetowl.debt.service.DebtsView;
import com.budgetowl.debt.service.PlanView;
import com.budgetowl.debt.service.SolvedTermsView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Response bodies for the debt endpoints. <b>Entities are never response bodies.</b>
 *
 * <p><b>Amounts and the rate cross the wire as strings</b> (ADR-0006) — every {@code BigDecimal}
 * here is serialized by {@code MoneyJsonModule}, so a response cannot emit a bare JSON number by
 * omission. Most JSON parsers, JavaScript's included, read a JSON number as a double, and {@code
 * 22.9} is not exactly representable as one.
 *
 * <p>Nothing is <b>formatted</b>: no currency symbols, no thousands separators, no locale dates.
 * Those are decisions the client makes. Months are {@code "2027-04"} strings everywhere, because a
 * payoff date derived from monthly arithmetic cannot be day-accurate and a date that pretended
 * otherwise would be false precision on the wire.
 *
 * <p>Every solver figure travels with its source — {@code ENTERED}, {@code SOLVED} or {@code
 * FROM_PLAN} — because a figure the app worked out must never be mistaken for one the household
 * asserted.
 */
public final class DebtResponses {

    private DebtResponses() {}

    /**
     * One debt.
     *
     * @param apr null where a rate was neither entered nor derivable; {@code aprDerivable} tells
     *     that apart from a rate nobody has supplied yet
     * @param monthsLeft and {@code payoffMonth} null together with {@code projectionReason} set —
     *     {@code never-clears} is a fact about the terms, not a fault in them
     * @param rung null for a debt that is not in the ladder: archived, settled, or at zero
     */
    public record DebtResponse(
            UUID id,
            String name,
            String kind,
            String servicer,
            String currency,
            BigDecimal balance,
            String balanceSource,
            LocalDate balanceAsOf,
            int estimatedMonths,
            boolean balanceStale,
            BigDecimal anchorBalance,
            LocalDate anchorDate,
            BigDecimal apr,
            String aprSource,
            boolean aprDerivable,
            BigDecimal minimumPayment,
            BigDecimal originalBalance,
            BigDecimal paidOffSoFar,
            BigDecimal progressPercent,
            BigDecimal payment,
            String paymentSource,
            Integer monthsLeft,
            String payoffMonth,
            String horizonSource,
            BigDecimal totalInterestLeft,
            String projectionReason,
            Integer rung,
            BigDecimal planMinimum,
            BigDecimal planExtra,
            UUID accountId,
            UUID billId,
            Integer customOrder,
            boolean archived,
            Instant paidOffAt,
            long paymentCount,
            long version) {

        static DebtResponse of(DebtView debt) {
            return new DebtResponse(
                    debt.id(),
                    debt.name(),
                    debt.kind(),
                    debt.servicer(),
                    debt.currency(),
                    plainAmount(debt.balance()),
                    debt.balanceSource(),
                    debt.balanceAsOf(),
                    debt.estimatedMonths(),
                    debt.balanceStale(),
                    plainAmount(debt.anchorBalance()),
                    debt.anchorDate(),
                    debt.apr(),
                    debt.aprSource().name(),
                    debt.aprDerivable(),
                    plainAmount(debt.minimumPayment()),
                    plainAmount(debt.originalBalance()),
                    plainAmount(debt.paidOffSoFar()),
                    debt.progressPercent(),
                    plainAmount(debt.payment()),
                    debt.paymentSource().name(),
                    debt.monthsLeft(),
                    month(debt.payoffMonth()),
                    debt.horizonSource().name(),
                    plainAmount(debt.totalInterestLeft()),
                    debt.projectionReason(),
                    debt.rung(),
                    plainAmount(debt.planMinimum()),
                    plainAmount(debt.planExtra()),
                    debt.accountId(),
                    debt.billId(),
                    debt.customOrder(),
                    debt.archived(),
                    debt.paidOffAt(),
                    debt.paymentCount(),
                    debt.version());
        }

        /** Redacted: a generated one is the name, the servicer, the balance and the rate. */
        @Override
        public String toString() {
            return "DebtResponse[id=" + id + "]";
        }
    }

    /**
     * The debt list, with the figures Bills &amp; Income shares.
     *
     * <p><b>Not a page envelope</b>, and deliberately so: the plan ranks every debt and these
     * totals are sums over all of them, so a page would make the order and the figures either wrong
     * or a second request. The per-household cap is what makes that safe — see {@code
     * DebtService.MAXIMUM_PER_HOUSEHOLD}.
     */
    public record DebtsResponse(
            List<DebtResponse> debts,
            String strategy,
            String currency,
            BigDecimal owed,
            BigDecimal debtMinimums,
            BigDecimal debtMinimumsNotInBills,
            int debtCount) {

        static DebtsResponse of(DebtsView view) {
            List<DebtResponse> debts = new ArrayList<>(view.debts().size());
            for (DebtView debt : view.debts()) {
                debts.add(DebtResponse.of(debt));
            }
            return new DebtsResponse(
                    List.copyOf(debts),
                    view.strategy(),
                    view.currency(),
                    plainAmount(view.owed()),
                    plainAmount(view.debtMinimums()),
                    plainAmount(view.debtMinimumsNotInBills()),
                    view.debtCount());
        }

        /** Redacted — see {@link DebtResponse}. */
        @Override
        public String toString() {
            return "DebtsResponse[debts=" + debts.size() + "]";
        }
    }

    /** One debt and the two lines its chart draws. */
    public record DebtDetailResponse(
            DebtResponse debt,
            List<SeriesPointResponse> pace,
            List<SeriesPointResponse> atMinimum,
            int seriesMonthStride,
            boolean atMinimumIsTheSameLine) {

        static DebtDetailResponse of(DebtDetailView view) {
            return new DebtDetailResponse(
                    DebtResponse.of(view.debt()),
                    seriesOf(view.pace()),
                    seriesOf(view.atMinimum()),
                    view.seriesMonthStride(),
                    view.atMinimumIsTheSameLine());
        }

        /** Redacted — see {@link DebtResponse}. */
        @Override
        public String toString() {
            return "DebtDetailResponse[id=" + debt.id() + "]";
        }
    }

    /** One point on a chart: what is still owed at the start of that month. */
    public record SeriesPointResponse(String month, BigDecimal balance) {

        @Override
        public String toString() {
            return "SeriesPointResponse[month=" + month + "]";
        }
    }

    /** One payment, with the split as it was recorded and how the row got here. */
    public record PaymentResponse(
            UUID id,
            UUID debtId,
            LocalDate paidDate,
            String currency,
            BigDecimal amount,
            BigDecimal interest,
            BigDecimal principal,
            BigDecimal balanceAfter,
            String provenance,
            UUID transactionId,
            UUID billOccurrenceId,
            String note,
            long version) {

        static PaymentResponse of(DebtPaymentView payment) {
            return new PaymentResponse(
                    payment.id(),
                    payment.debtId(),
                    payment.paidDate(),
                    payment.amount().currency().getCurrencyCode(),
                    plainAmount(payment.amount()),
                    plainAmount(payment.interest()),
                    plainAmount(payment.principal()),
                    plainAmount(payment.balanceAfter()),
                    payment.provenance(),
                    payment.transactionId(),
                    payment.billOccurrenceId(),
                    payment.note(),
                    payment.version());
        }

        /** Redacted: a payment history is a behavioural record. */
        @Override
        public String toString() {
            return "PaymentResponse[id=" + id + ", paidDate=" + paidDate + "]";
        }
    }

    /** One page of a debt's history — the envelope every collection returns. */
    public record PaymentPageResponse(
            List<PaymentResponse> content, int page, int size, long totalElements, int totalPages) {

        static PaymentPageResponse of(DebtPaymentView.Page page) {
            List<PaymentResponse> content = new ArrayList<>(page.content().size());
            for (DebtPaymentView payment : page.content()) {
                content.add(PaymentResponse.of(payment));
            }
            return new PaymentPageResponse(
                    List.copyOf(content),
                    page.page(),
                    page.size(),
                    page.totalElements(),
                    page.totalPages());
        }

        /** Redacted — see {@link PaymentResponse}. */
        @Override
        public String toString() {
            return "PaymentPageResponse[content=" + content.size() + "]";
        }
    }

    /** The plan: the selection, the order, both projections and the comparison. */
    public record PlanResponse(
            String strategy,
            String currency,
            BigDecimal monthlyAmount,
            BigDecimal minimums,
            int debtCount,
            boolean mixedCurrency,
            List<CurrencyGroupResponse> currencies,
            long version) {

        static PlanResponse of(PlanView plan) {
            List<CurrencyGroupResponse> groups = new ArrayList<>(plan.groups().size());
            for (PlanView.CurrencyGroup group : plan.groups()) {
                groups.add(CurrencyGroupResponse.of(group));
            }
            return new PlanResponse(
                    plan.strategy(),
                    plan.monthlyAmount().currency().getCurrencyCode(),
                    plainAmount(plan.monthlyAmount()),
                    plainAmount(plan.minimums()),
                    plan.debtCount(),
                    plan.isMixedCurrency(),
                    List.copyOf(groups),
                    plan.version());
        }

        /** Redacted — the plan is a statement of disposable income. */
        @Override
        public String toString() {
            return "PlanResponse[strategy=" + strategy + ", debtCount=" + debtCount + "]";
        }
    }

    /**
     * One currency's ladder.
     *
     * @param monthlyAmount what <b>this group's</b> projection assumed: the plan's amount for the
     *     household's own currency, that group's minimums for any other. Reported so the assumption
     *     is visible rather than mysterious (ADR-0022)
     */
    public record CurrencyGroupResponse(
            String currency,
            boolean planCurrency,
            BigDecimal monthlyAmount,
            BigDecimal minimums,
            BigDecimal surplus,
            BigDecimal owed,
            boolean belowMinimums,
            StrategyResponse selected,
            StrategyResponse avalanche,
            StrategyResponse snowball) {

        static CurrencyGroupResponse of(PlanView.CurrencyGroup group) {
            return new CurrencyGroupResponse(
                    group.currency(),
                    group.planCurrency(),
                    plainAmount(group.monthlyAmount()),
                    plainAmount(group.minimums()),
                    plainAmount(group.surplus()),
                    plainAmount(group.owed()),
                    group.belowMinimums(),
                    StrategyResponse.of(group.selected()),
                    StrategyResponse.of(group.avalanche()),
                    StrategyResponse.of(group.snowball()));
        }

        /** Redacted. */
        @Override
        public String toString() {
            return "CurrencyGroupResponse[currency=" + currency + "]";
        }
    }

    /**
     * One strategy's answer.
     *
     * @param rungs empty on the two comparison projections: the table is drawn once, for the
     *     selected strategy
     * @param seriesMonthStride how many months one point of {@code series} stands for. More than 1
     *     for a plan that runs long, because the line is thinned rather than truncated
     */
    public record StrategyResponse(
            String strategy,
            String debtFreeMonth,
            String firstWinMonth,
            BigDecimal totalInterest,
            String reason,
            List<RungResponse> rungs,
            List<SeriesPointResponse> series,
            int seriesMonthStride) {

        static StrategyResponse of(PlanView.StrategyProjection projection) {
            List<RungResponse> rungs = new ArrayList<>(projection.rungs().size());
            for (PlanView.Rung rung : projection.rungs()) {
                rungs.add(RungResponse.of(rung));
            }
            return new StrategyResponse(
                    projection.strategy(),
                    month(projection.debtFreeMonth()),
                    month(projection.firstWinMonth()),
                    plainAmount(projection.totalInterest()),
                    projection.reason(),
                    List.copyOf(rungs),
                    seriesOf(projection.series()),
                    projection.seriesMonthStride());
        }

        /** Redacted. */
        @Override
        public String toString() {
            return "StrategyResponse[strategy=" + strategy + "]";
        }
    }

    /** One debt's place in the ladder, and what the plan allocates it in the first month. */
    public record RungResponse(
            int rung,
            UUID debtId,
            String name,
            String kind,
            String servicer,
            BigDecimal balance,
            BigDecimal apr,
            boolean aprDerivable,
            BigDecimal minimum,
            BigDecimal extra,
            BigDecimal monthlyAmount,
            String payoffMonth,
            BigDecimal totalInterest) {

        static RungResponse of(PlanView.Rung rung) {
            return new RungResponse(
                    rung.rung(),
                    rung.debtId(),
                    rung.name(),
                    rung.kind(),
                    rung.servicer(),
                    plainAmount(rung.balance()),
                    rung.apr(),
                    rung.aprDerivable(),
                    plainAmount(rung.minimum()),
                    plainAmount(rung.extra()),
                    plainAmount(rung.monthlyAmount()),
                    month(rung.payoffMonth()),
                    plainAmount(rung.totalInterest()));
        }

        /** Redacted — a rung is a name, a balance and a rate in one line. */
        @Override
        public String toString() {
            return "RungResponse[rung=" + rung + ", debtId=" + debtId + "]";
        }
    }

    /** A solved term set, with the one field that was derived named. */
    public record SolvedTermsResponse(
            String currency,
            BigDecimal balance,
            BigDecimal apr,
            BigDecimal payment,
            Integer monthsLeft,
            String payoffMonth,
            BigDecimal totalInterest,
            String solvedField,
            String reason,
            List<SeriesPointResponse> series,
            int seriesMonthStride,
            int bisectionSteps) {

        static SolvedTermsResponse of(SolvedTermsView solved) {
            return new SolvedTermsResponse(
                    solved.balance().currency().getCurrencyCode(),
                    plainAmount(solved.balance()),
                    solved.apr(),
                    plainAmount(solved.payment()),
                    solved.monthsLeft(),
                    month(solved.payoffMonth()),
                    plainAmount(solved.totalInterest()),
                    solved.solvedField(),
                    solved.reason(),
                    seriesOf(solved.series()),
                    solved.seriesMonthStride(),
                    solved.bisectionSteps());
        }

        /** Redacted. */
        @Override
        public String toString() {
            return "SolvedTermsResponse[solvedField=" + solvedField + "]";
        }
    }

    // ------------------------------------------------------------------------------- internals

    private static List<SeriesPointResponse> seriesOf(List<PlanView.SeriesPoint> points) {
        List<SeriesPointResponse> series = new ArrayList<>(points.size());
        for (PlanView.SeriesPoint point : points) {
            series.add(
                    new SeriesPointResponse(
                            point.month().toString(), plainAmount(point.balance())));
        }
        return List.copyOf(series);
    }

    /** A null amount stays null: an absent figure is not a zero (see {@code never-clears}). */
    private static BigDecimal plainAmount(Money money) {
        return money == null ? null : money.amount();
    }

    private static String month(YearMonth month) {
        return month == null ? null : month.toString();
    }
}
