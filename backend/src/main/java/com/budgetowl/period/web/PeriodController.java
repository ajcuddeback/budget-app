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
     * The three terms and the subtraction.
     *
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
                    summary.left().amount(),
                    summary.left().currency().getCurrencyCode());
        }
    }
}
