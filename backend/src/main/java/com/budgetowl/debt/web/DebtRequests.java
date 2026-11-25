package com.budgetowl.debt.web;

import com.budgetowl.common.Change;
import com.budgetowl.debt.domain.Debt;
import com.budgetowl.debt.domain.DebtPayment;
import com.budgetowl.debt.service.DebtService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies for the debt endpoints. <b>Entities are never request bodies.</b>
 *
 * <p><b>Amounts and the rate are strings</b> (ADR-0006), with a shape {@code @Pattern} so a
 * malformed one is a {@code validation-failed} naming the field rather than a deserialization
 * failure naming nothing. A JSON <em>number</em> is refused outright, by {@code
 * CoreConfig.refuseCoercionIntoText} for these string fields and by {@code MoneyJsonModule}
 * everywhere else: {@code 22.9} is not exactly representable as a double either, and it multiplies
 * a balance.
 *
 * <p><b>No household id</b> anywhere (ADR-0017), and no acting user: both come from the caller's
 * verified membership.
 *
 * <p>Every {@code toString} is redacted. Spring MVC logs the deserialized body at {@code DEBUG},
 * and a record's generated {@code toString} prints every component — which here would be a balance,
 * a rate and a servicer's name (docs/memory/gotchas.md).
 */
public final class DebtRequests {

    /** Unsigned, at most four decimal places — the scale {@code NUMERIC(19,4)} holds. */
    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,4})?$";

    private static final String AMOUNT_MESSAGE = "must be a decimal with at most 4 decimal places";

    /**
     * At least one non-whitespace character, Unicode-aware.
     *
     * <p>Copied from {@code AccountRequests}, where the comment explains why it has to exist: on a
     * {@code PATCH}, {@code @NotBlank} cannot be used because it refuses {@code null}, and {@code
     * null} is the ordinary "not mentioned" case. {@code @Size(max = …)} alone passes {@code " "},
     * which then reaches the entity, strips to empty and throws {@code IllegalArgumentException} —
     * a {@code 500} with a stack trace at {@code ERROR}, from any authenticated caller, in a loop,
     * on a machine in somebody's house. Slice 3's audit found exactly this on {@code /api/accounts}
     * and slice 10 reintroduced it on a new DTO.
     *
     * <p>{@code (?U)} so a full-width ideographic space counts as whitespace; {@code DOTALL} so a
     * name containing a newline is still matched.
     */
    private static final String NOT_BLANK = "(?U).*\\S.*";

    /** A percentage as a statement prints it, to three places: {@code 22.900}. */
    private static final String RATE = "^\\d{1,3}(\\.\\d{1,3})?$";

    private static final String RATE_MESSAGE =
            "must be a percentage under 1000 with at most 3 decimal places";

    private DebtRequests() {}

    /**
     * A debt to record.
     *
     * <p>{@code apr} is required <b>unless</b> {@code payment} and a horizon are both given — the
     * installment-loan case — which is checked in the service, where the whole set is visible.
     *
     * @param monthsLeft and {@code payoffMonth}: one unknown with two faces. Sending both is a
     *     {@code 400}
     */
    public record CreateDebtRequest(
            @NotBlank @Size(max = Debt.NAME_MAX_LENGTH) String name,
            @NotBlank String kind,
            @Size(max = Debt.SERVICER_MAX_LENGTH) String servicer,
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter currency code")
                    String currency,
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String balance,
            @NotNull LocalDate balanceDate,
            @Pattern(regexp = RATE, message = RATE_MESSAGE) String apr,
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String minimumPayment,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String originalBalance,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String payment,
            @Min(1) Integer monthsLeft,
            YearMonth payoffMonth,
            UUID accountId,
            UUID billId) {

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "CreateDebtRequest[kind=" + kind + ", balanceDate=" + balanceDate + "]";
        }
    }

    /**
     * The terms editor's change.
     *
     * <p>A class with setters rather than a record, because <b>an absent field and an explicit null
     * are different instructions</b> and a record cannot tell them apart. Absent leaves a field
     * alone; null <b>releases</b> it to the solver; a value enters it. Jackson calls a setter only
     * when the property is present, and that is the whole mechanism.
     *
     * <p>It never infers "absent means release": a field dropped by accident must not silently
     * change what the household believes about its own payoff date.
     */
    public static final class UpdateDebtRequest {

        @Size(max = Debt.NAME_MAX_LENGTH)
        @Pattern(regexp = NOT_BLANK, flags = Pattern.Flag.DOTALL, message = "must not be blank")
        private String name;

        private String kind;

        @Size(max = Debt.SERVICER_MAX_LENGTH)
        private String servicer;

        private boolean servicerMentioned;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String minimumPayment;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String originalBalance;

        private boolean originalBalanceMentioned;

        @Pattern(regexp = RATE, message = RATE_MESSAGE)
        private String apr;

        private boolean aprMentioned;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String payment;

        private boolean paymentMentioned;

        @Min(1)
        private Integer monthsLeft;

        private boolean monthsLeftMentioned;

        private YearMonth payoffMonth;

        private boolean payoffMonthMentioned;

        private UUID accountId;

        private boolean accountIdMentioned;

        private UUID billId;

        private boolean billIdMentioned;

        @Min(0)
        private Integer customOrder;

        private boolean customOrderMentioned;

        /** Required: optimistic locking only works if the caller says which version it read. */
        @NotNull private Long version;

        public void setName(String name) {
            this.name = name;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public void setServicer(String servicer) {
            this.servicer = servicer;
            this.servicerMentioned = true;
        }

        public void setMinimumPayment(String minimumPayment) {
            this.minimumPayment = minimumPayment;
        }

        public void setOriginalBalance(String originalBalance) {
            this.originalBalance = originalBalance;
            this.originalBalanceMentioned = true;
        }

        public void setApr(String apr) {
            this.apr = apr;
            this.aprMentioned = true;
        }

        public void setPayment(String payment) {
            this.payment = payment;
            this.paymentMentioned = true;
        }

        public void setMonthsLeft(Integer monthsLeft) {
            this.monthsLeft = monthsLeft;
            this.monthsLeftMentioned = true;
        }

        public void setPayoffMonth(YearMonth payoffMonth) {
            this.payoffMonth = payoffMonth;
            this.payoffMonthMentioned = true;
        }

        public void setAccountId(UUID accountId) {
            this.accountId = accountId;
            this.accountIdMentioned = true;
        }

        public void setBillId(UUID billId) {
            this.billId = billId;
            this.billIdMentioned = true;
        }

        public void setCustomOrder(Integer customOrder) {
            this.customOrder = customOrder;
            this.customOrderMentioned = true;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String name() {
            return name;
        }

        public String kind() {
            return kind;
        }

        public String minimumPayment() {
            return minimumPayment;
        }

        public Long version() {
            return version;
        }

        /** A blank servicer is a cleared one: {@code ck_debts_servicer} refuses an empty string. */
        public Change<String> servicerChange() {
            if (!servicerMentioned) {
                return Change.unchanged();
            }
            return servicer == null || servicer.isBlank()
                    ? Change.cleared()
                    : Change.setTo(servicer);
        }

        public Change<String> originalBalanceChange() {
            return Change.of(originalBalance, originalBalanceMentioned);
        }

        /** Null is a <b>release</b>: the rate becomes the solver's to work out. */
        public Change<String> aprChange() {
            return Change.of(apr, aprMentioned);
        }

        public Change<String> paymentChange() {
            return Change.of(payment, paymentMentioned);
        }

        public Change<Integer> monthsLeftChange() {
            return Change.of(monthsLeft, monthsLeftMentioned);
        }

        public Change<YearMonth> payoffMonthChange() {
            return Change.of(payoffMonth, payoffMonthMentioned);
        }

        public Change<UUID> accountIdChange() {
            return Change.of(accountId, accountIdMentioned);
        }

        public Change<UUID> billIdChange() {
            return Change.of(billId, billIdMentioned);
        }

        public Change<Integer> customOrderChange() {
            return Change.of(customOrder, customOrderMentioned);
        }

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "UpdateDebtRequest[version=" + version + "]";
        }
    }

    /**
     * A payment to preview: the amount, and nothing else.
     *
     * <p>In a body rather than a query string, so it does not land in a reverse proxy's access log
     * or in Spring's own {@code DispatcherServlet} line at {@code DEBUG} — which masks the decoded
     * parameters but prints the raw query string beside them. A payment somebody is about to make
     * is not secret, but it is theirs, and a URL is the one part of a request every layer logs.
     */
    public record PreviewPaymentRequest(
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amount) {

        /** Redacted — the amount is a payment somebody is about to make. */
        @Override
        public String toString() {
            return "PreviewPaymentRequest[]";
        }
    }

    /** A payment to log. {@code transactionId} is evidence of a real movement, not authority. */
    public record LogPaymentRequest(
            @NotNull LocalDate paidDate,
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String amount,
            UUID transactionId,
            UUID billOccurrenceId,
            UUID paidFromAccountId,
            @Size(max = DebtPayment.NOTE_MAX_LENGTH) String note) {

        /** Redacted — the amount is a payment somebody made. */
        @Override
        public String toString() {
            return "LogPaymentRequest[paidDate=" + paidDate + "]";
        }
    }

    /**
     * A correction to a payment.
     *
     * <p>A class with setters for the same reason {@link UpdateDebtRequest} is one: clearing a
     * wrong transaction link and not mentioning it are different instructions.
     */
    public static final class CorrectPaymentRequest {

        private LocalDate paidDate;

        @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE)
        private String amount;

        private UUID transactionId;

        private boolean transactionIdMentioned;

        private UUID paidFromAccountId;

        private boolean paidFromAccountIdMentioned;

        @Size(max = DebtPayment.NOTE_MAX_LENGTH)
        private String note;

        private boolean noteMentioned;

        @NotNull private Long version;

        public void setPaidDate(LocalDate paidDate) {
            this.paidDate = paidDate;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }

        public void setTransactionId(UUID transactionId) {
            this.transactionId = transactionId;
            this.transactionIdMentioned = true;
        }

        public void setPaidFromAccountId(UUID paidFromAccountId) {
            this.paidFromAccountId = paidFromAccountId;
            this.paidFromAccountIdMentioned = true;
        }

        public void setNote(String note) {
            this.note = note;
            this.noteMentioned = true;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public LocalDate paidDate() {
            return paidDate;
        }

        public String amount() {
            return amount;
        }

        public Long version() {
            return version;
        }

        public Change<UUID> transactionIdChange() {
            return Change.of(transactionId, transactionIdMentioned);
        }

        public Change<UUID> paidFromAccountIdChange() {
            return Change.of(paidFromAccountId, paidFromAccountIdMentioned);
        }

        public Change<String> noteChange() {
            if (!noteMentioned) {
                return Change.unchanged();
            }
            return note == null || note.isBlank() ? Change.cleared() : Change.setTo(note);
        }

        /** Redacted — see {@link UpdateDebtRequest}. */
        @Override
        public String toString() {
            return "CorrectPaymentRequest[version=" + version + "]";
        }
    }

    /** What a statement says is owed, and when that was true. A statement wins. */
    public record ConfirmBalanceRequest(
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String balance,
            @NotNull LocalDate balanceDate,
            @Size(max = DebtPayment.NOTE_MAX_LENGTH) String note) {

        /** Redacted — the balance is what somebody owes. */
        @Override
        public String toString() {
            return "ConfirmBalanceRequest[balanceDate=" + balanceDate + "]";
        }
    }

    /**
     * The plan's two figures, and the household's own order.
     *
     * @param customOrder debt ids from rung 1 down, or absent to leave every position alone. A debt
     *     the list omits has its position cleared: the list is the whole order. <b>Bounded at the
     *     edge</b> by the household's own debt cap: an order longer than that names debts that
     *     cannot exist, and without the bound it is the one unbounded collection in the API — a
     *     seventy-megabyte body of UUIDs that Jackson materialises and the service then builds a
     *     map of, before discovering the ids belong to nothing
     */
    public record UpdatePlanRequest(
            String strategy,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String monthlyAmount,
            @Size(
                            max = DebtService.MAXIMUM_PER_HOUSEHOLD,
                            message = "cannot name more debts than a household may have")
                    List<UUID> customOrder,
            @NotNull Long version) {

        /** Redacted — the monthly amount is a statement of disposable income. */
        @Override
        public String toString() {
            return "UpdatePlanRequest[strategy=" + strategy + ", version=" + version + "]";
        }
    }

    /**
     * A term set to solve and not save.
     *
     * <p>Exactly one of {@code apr}, {@code payment} and the horizon is left out. The balance is
     * always supplied and never solved.
     */
    public record SolveTermsRequest(
            @NotNull @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String balance,
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be a three-letter currency code")
                    String currency,
            @Pattern(regexp = RATE, message = RATE_MESSAGE) String apr,
            @Pattern(regexp = AMOUNT, message = AMOUNT_MESSAGE) String payment,
            @Min(1) Integer monthsLeft,
            YearMonth payoffMonth,
            YearMonth firstPaymentMonth) {

        /** Redacted — every figure in here is somebody's debt. */
        @Override
        public String toString() {
            return "SolveTermsRequest[firstPaymentMonth=" + firstPaymentMonth + "]";
        }
    }
}
