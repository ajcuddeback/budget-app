package com.budgetowl.checkin.service;

import com.budgetowl.bill.domain.OccurrenceStatus;
import com.budgetowl.checkin.domain.AnswerChoice;
import com.budgetowl.checkin.domain.IsoWeek;
import com.budgetowl.checkin.domain.ItemKind;
import com.budgetowl.common.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * What the check-in service returns. Every {@code toString} is redacted: these carry payees,
 * amounts, categories and a household's weekly figures, and a view is one careless log statement
 * from being printed (threat model, attack 13).
 */
public final class CheckInViews {

    private CheckInViews() {}

    /** The four steps, in order. */
    public enum Step {
        WHATS_NEW,
        CATEGORIZE,
        BILLS,
        NEXT_WEEK
    }

    /** Why step 3 is asking about a charge, or how it pre-answered it. */
    public enum ChargeReason {
        /** A charge rule matched within tolerance: pre-answered, not a question. */
        RULE_MATCH,
        /** A charge rule matched outside tolerance: asked. */
        RULE_OUTSIDE_TOLERANCE,
        /** Three months running with no rule: asked. */
        RECURRING
    }

    /**
     * One week, as every caller reads it.
     *
     * @param status {@code NOT_STARTED}, {@code DRAFT} or {@code COMPLETED}
     * @param version the draft's, which completion sends back; null before a draft exists
     * @param basis a digest of everything derived, which completion sends back; null on a completed
     *     week, whose snapshot is not a recomputation
     * @param otherCurrencyRows rows in the window in accounts outside the base currency, which no
     *     step looks at
     * @param byRule how many pre-answers rules supplied, including those in steps not shown
     */
    public record WeekView(
            IsoWeek week,
            LocalDate windowStart,
            String status,
            Long version,
            String basis,
            Person startedBy,
            Instant completedAt,
            Person completedBy,
            String currency,
            long otherCurrencyRows,
            RuleCounts byRule,
            List<StepView> steps,
            SnapshotView snapshot,
            List<OutcomeView> outcome) {

        public WeekView {
            steps = List.copyOf(steps);
            outcome = List.copyOf(outcome);
        }

        @Override
        public String toString() {
            return "WeekView[week=" + week + ", status=" + status + "]";
        }
    }

    /** A member, by id and display name. The name is null for a removed member. */
    public record Person(UUID id, String name) {

        @Override
        public String toString() {
            return "Person[id=" + id + "]";
        }
    }

    public record RuleCounts(int categorized, int charges) {}

    /**
     * One shown step. A step with nothing to answer is not shown, except step 4, which never is
     * skipped. The fields a step does not use are null.
     *
     * @param questions items that need an answer (a rule's pre-answer is not one)
     * @param answeredCount of those, how many have one
     * @param more uncategorised rows beyond the 200 shown (step 2)
     */
    public record StepView(
            Step step,
            boolean answered,
            int questions,
            int answeredCount,
            List<ItemView> items,
            List<IncomeLine> income,
            Long more,
            Money weekSpending,
            List<OccurrenceLine> bills,
            Money stillToPay,
            List<BillChoice> billChoices,
            FiguresView figures,
            List<ChoiceView> choices,
            List<DueLine> dueNextWeek,
            StreakView streak) {

        public StepView {
            items = items == null ? List.of() : List.copyOf(items);
            income = income == null ? null : List.copyOf(income);
            bills = bills == null ? null : List.copyOf(bills);
            billChoices = billChoices == null ? null : List.copyOf(billChoices);
            choices = choices == null ? null : List.copyOf(choices);
            dueNextWeek = dueNextWeek == null ? null : List.copyOf(dueNextWeek);
        }

        @Override
        public String toString() {
            return "StepView[step=" + step + ", items=" + items.size() + "]";
        }
    }

    /**
     * One item. Which fields are set depends on the kind; the rest are null.
     *
     * @param question false for an item a rule pre-answered: shown, changeable, not asked
     * @param usual the bill's amount or the payee's median, for an unusual charge
     * @param multiple {@code k} for a prepaid offer; null when only "leave it" is offered
     * @param skips how many later occurrences prepaid would skip, which can be fewer than {@code k
     *     − 1} for a bill whose schedule projects fewer
     * @param ruleCategoryId the payee's existing rule, for "Replaces your rule filing it under …"
     * @param debtPrompt the debt this bill pays, when the bill is a debt's bill row
     */
    public record ItemView(
            String itemKey,
            ItemKind kind,
            boolean question,
            UUID transactionId,
            Long transactionVersion,
            LocalDate date,
            Money amount,
            String payeeName,
            UUID accountId,
            String accountName,
            String accountLastFour,
            UUID billId,
            String billName,
            LocalDate dueDate,
            Money expected,
            Money usual,
            Integer multiple,
            Integer skips,
            ChargeReason reason,
            UUID suggestedBillId,
            LocalDate suggestedDueDate,
            DebtPrompt debtPrompt,
            UUID ruleCategoryId,
            String ruleCategoryName,
            PreAnswer preAnswer,
            AnswerView answer) {

        @Override
        public String toString() {
            return "ItemView[itemKey=" + itemKey + "]";
        }
    }

    public record DebtPrompt(UUID debtId, String debtName) {

        @Override
        public String toString() {
            return "DebtPrompt[debtId=" + debtId + "]";
        }
    }

    /** What a rule would answer. Derived on every read and never written by one. */
    public record PreAnswer(
            AnswerChoice choice,
            UUID categoryId,
            String categoryName,
            UUID billId,
            String billName,
            LocalDate dueDate) {

        @Override
        public String toString() {
            return "PreAnswer[choice=" + choice + "]";
        }
    }

    public record AnswerView(
            AnswerChoice choice,
            UUID categoryId,
            String categoryName,
            UUID billId,
            String billName,
            LocalDate dueDate,
            UUID goalId,
            UUID debtId,
            Money amount,
            boolean remember,
            boolean recordDebtPayment,
            boolean byRule,
            Person answeredBy,
            long version) {

        @Override
        public String toString() {
            return "AnswerView[choice=" + choice + ", version=" + version + "]";
        }
    }

    /**
     * Step 1's income line: information, never a question, and it writes nothing (decision 7).
     * {@code transactionId} is null when nothing has landed yet.
     */
    public record IncomeLine(
            UUID incomeSourceId,
            String name,
            LocalDate expectedDate,
            Money expected,
            UUID transactionId,
            LocalDate date,
            Money actual,
            UUID accountId,
            String accountName,
            String accountLastFour) {

        @Override
        public String toString() {
            return "IncomeLine[incomeSourceId=" + incomeSourceId + "]";
        }
    }

    /**
     * One row of step 3's table, read from the same {@code BillsForPeriod} Bills &amp; Income
     * reads.
     */
    public record OccurrenceLine(
            UUID billId,
            String billName,
            LocalDate dueDate,
            OccurrenceStatus status,
            Money amount,
            boolean overdue) {

        @Override
        public String toString() {
            return "OccurrenceLine[billId=" + billId + ", dueDate=" + dueDate + "]";
        }
    }

    /** A bill the "Different bill" picker offers, with the dates a charge may attach to. */
    public record BillChoice(
            UUID billId,
            String name,
            Money expected,
            boolean amountVaries,
            BigDecimal tolerance,
            List<LocalDate> dueDates) {

        public BillChoice {
            dueDates = List.copyOf(dueDates);
        }

        @Override
        public String toString() {
            return "BillChoice[billId=" + billId + "]";
        }
    }

    /**
     * Step 4's figures, every one the server's.
     *
     * @param weekTarget last week's completed check-in's target, or null
     * @param surplus {@code weekTarget − weekSpending}, or null with no target
     * @param free {@code free(M)} for the month next week starts in
     * @param soFar the week is not over yet, so its spending is "so far"
     */
    public record FiguresView(
            Money weekTarget,
            Money weekSpending,
            Money surplus,
            Money free,
            Money base,
            Money basePerDay,
            Money rolledOver,
            Money rolledPerDay,
            boolean surplusAllocatable,
            boolean soFar) {

        @Override
        public String toString() {
            return "FiguresView[]";
        }
    }

    /**
     * One place the surplus can go. Its figures come from the real write paths' previews.
     *
     * @param payoffMonthNow and {@code payoffMonthWith}: months, never days
     * @param monthInterest for a debt: this month's interest the payment would carry, because it
     *     would be the debt's first payment this month (ADR-0043). Null when the month's interest
     *     is already booked, or there is none. The clients say it in words — the payment can leave
     *     the balance higher than it found it — and compute nothing
     */
    public record ChoiceView(
            AnswerChoice choice,
            UUID debtId,
            String debtName,
            YearMonth payoffMonthNow,
            YearMonth payoffMonthWith,
            UUID goalId,
            String goalName,
            Money savedNow,
            Money savedWith,
            Money target,
            Money monthInterest) {

        @Override
        public String toString() {
            return "ChoiceView[choice=" + choice + "]";
        }
    }

    public record DueLine(UUID billId, String billName, LocalDate dueDate) {

        @Override
        public String toString() {
            return "DueLine[billId=" + billId + "]";
        }
    }

    /**
     * Consecutive weeks under target, this one included when it is.
     *
     * @param debtFreeFrom and {@code debtFreeTo}: the debt-free month at the streak's start and
     *     now, both null unless both exist and differ
     */
    public record StreakView(int weeks, YearMonth debtFreeFrom, YearMonth debtFreeTo) {}

    /** What a completed check-in recorded about itself. */
    public record SnapshotView(
            Money weekTarget,
            Money weekSpending,
            Money rolledOver,
            Money nextWeekTarget,
            YearMonth debtFreeMonth) {

        @Override
        public String toString() {
            return "SnapshotView[]";
        }
    }

    /** One record a completed check-in wrote, with the names a summary needs. */
    public record OutcomeView(
            ItemKind kind,
            AnswerChoice choice,
            boolean byRule,
            UUID transactionId,
            String payeeName,
            UUID categoryId,
            String categoryName,
            UUID billId,
            String billName,
            LocalDate dueDate,
            UUID goalId,
            String goalName,
            UUID debtId,
            String debtName,
            Money amount,
            boolean remember,
            boolean recordDebtPayment) {

        @Override
        public String toString() {
            return "OutcomeView[kind=" + kind + ", choice=" + choice + "]";
        }
    }

    /** One row of the history. */
    public record HistoryEntry(
            IsoWeek week,
            String status,
            Instant completedAt,
            String currency,
            SnapshotView snapshot,
            AnswerChoice allocation,
            UUID allocationTargetId) {

        @Override
        public String toString() {
            return "HistoryEntry[week=" + week + "]";
        }
    }

    /** A page, in the envelope every collection returns. */
    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

        public Page {
            content = List.copyOf(content);
        }

        @Override
        public String toString() {
            return "Page[size=" + content.size() + "]";
        }
    }

    /** A rule as the categories page and Bills &amp; Income list it. */
    public record PayeeRuleView(
            UUID id,
            UUID payeeId,
            String payeeName,
            UUID categoryId,
            String categoryName,
            boolean categoryArchived) {

        @Override
        public String toString() {
            return "PayeeRuleView[id=" + id + "]";
        }
    }

    public record ChargeRuleView(
            UUID id,
            UUID billId,
            String billName,
            UUID payeeId,
            String payeeName,
            UUID accountId,
            String accountName,
            String accountLastFour) {

        @Override
        public String toString() {
            return "ChargeRuleView[id=" + id + "]";
        }
    }
}
