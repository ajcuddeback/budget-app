package com.budgetowl.period.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.period.service.PeriodSummary;
import com.budgetowl.period.service.PeriodSummaryService;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a household has left to spend in one month.
 *
 * <p>One endpoint, because the Bills &amp; Income page leads with one figure and a page that
 * assembled it from three responses could show a headline its own blocks disagree with.
 */
@RestController
@RequestMapping("/api/periods")
public class PeriodController {

    private final PeriodSummaryService summaries;

    public PeriodController(PeriodSummaryService summaries) {
        this.summaries = summaries;
    }

    /**
     * @param period the month, as {@code 2026-08}. Bound by Spring as a {@code YearMonth}, so a
     *     malformed one is a {@code 400} before anything here runs and never reaches a query
     */
    @GetMapping("/{period}/summary")
    SummaryResponse summary(TransportAuthentication caller, @PathVariable YearMonth period) {
        return SummaryResponse.of(summaries.summarise(caller.userId(), period));
    }

    /**
     * The five terms and the subtraction.
     *
     * @param owed the total debt balance — the <b>Owed</b> card. <b>Not</b> part of the
     *     subtraction: it is a balance rather than a monthly commitment
     * @param debtMinimumsNotInBills the fourth term, and the only debt figure {@code left} loses.
     *     The minimums already carried by a bill row are excluded, so a card's minimum is counted
     *     once (docs/features/debt-plan.md)
     * @param goalsSetAside the fifth term (slice 11): what the month's goals set aside, each the
     *     greater of its plan and what was recorded (docs/features/goals.md)
     * @param debtPlanExtra the payoff plan's amount beyond its minimums, or null. <b>Reported, not
     *     subtracted</b>
     * @param debtPlanExtraExceedsLeft compared on the server, so the client compares no amounts
     * @param left <b>goes negative</b>, and a client must render that rather than clamping it: "you
     *     are £180 short this month" is the information
     */
    public record SummaryResponse(
            String period,
            BigDecimal income,
            BigDecimal bills,
            BigDecimal billsOutstanding,
            BigDecimal billsOverdue,
            BigDecimal envelopesAllocated,
            BigDecimal owed,
            int debtCount,
            BigDecimal debtMinimums,
            BigDecimal debtMinimumsNotInBills,
            BigDecimal goalsSetAside,
            int goalsCounted,
            BigDecimal debtPlanExtra,
            boolean debtPlanExtraExceedsLeft,
            BigDecimal left,
            String currency) {

        /** Redacted: a generated one would put a household's whole month into a log line. */
        @Override
        public String toString() {
            return "SummaryResponse[period=" + period + "]";
        }

        static SummaryResponse of(PeriodSummary summary) {
            return new SummaryResponse(
                    summary.period().toString(),
                    summary.income().amount(),
                    summary.bills().amount(),
                    summary.billsOutstanding().amount(),
                    summary.billsOverdue().amount(),
                    summary.envelopesAllocated().amount(),
                    summary.owed().amount(),
                    summary.debtCount(),
                    summary.debtMinimums().amount(),
                    summary.debtMinimumsNotInBills().amount(),
                    summary.goalsSetAside().amount(),
                    summary.goalsCounted(),
                    summary.debtPlanExtra() == null ? null : summary.debtPlanExtra().amount(),
                    summary.debtPlanExtraExceedsLeft(),
                    summary.left().amount(),
                    summary.left().currency().getCurrencyCode());
        }
    }
}
