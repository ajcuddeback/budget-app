package com.budgetowl.debt.domain;

import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.HouseholdOwned;
import com.budgetowl.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A payment recorded against a debt — <b>a plan record, not a transaction</b> (ADR-0035).
 *
 * <p>Writing one moves no account balance and creates no ledger row. {@link #transactionId()} names
 * the real movement when the household knows it, and that link is <b>evidence, not authority</b>:
 * deleting the transaction clears the link and leaves the balance exactly where it was.
 *
 * <p><b>The split is stored</b>, which looks like the opposite of the rule above it and is not.
 * {@link #interest()} and {@link #principal()} are what was true under the rate in force on {@link
 * #paidDate()}; recomputing a 2024 payment against today's APR would rewrite history to agree with
 * the present, which is the one thing an audit trail must not do.
 *
 * <p>{@code interest + principal = amount} and {@code balanceAfter = balance − principal} hold by
 * construction — see {@link #of} — so no payment ever loses or invents a cent.
 */
@Entity
@Table(name = "debt_payments")
public class DebtPayment implements HouseholdOwned {

    public static final int NOTE_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "debt_id", nullable = false, updatable = false)
    private UUID debtId;

    @Column(name = "paid_date", nullable = false)
    private LocalDate paidDate;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "interest", nullable = false)
    private BigDecimal interest;

    @Column(name = "principal", nullable = false)
    private BigDecimal principal;

    @Column(name = "balance_after", nullable = false)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false)
    private PaymentProvenance provenance = PaymentProvenance.USER_LOGGED;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "bill_occurrence_id")
    private UUID billOccurrenceId;

    /**
     * Which account the household says the money came from.
     *
     * <p><b>Not the account the debt is held at</b> ({@code Debt.accountId}): a card at Chase is
     * paid from checking, and conflating the two would send slice 16's matching to the wrong
     * ledger. Nothing is written to this account and no transaction is created (ADR-0035) — it
     * records where a real charge will eventually be looked for.
     */
    @Column(name = "paid_from_account_id")
    private UUID paidFromAccountId;

    @Column(name = "note")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** For Hibernate and the factory. Does nothing and cannot throw. */
    protected DebtPayment() {
        // Hibernate, and of().
    }

    /**
     * The only way a payment row is built, so the two identities are arithmetic rather than
     * assertions a caller has to remember.
     *
     * @param balanceBefore what was owed before this payment — the figure the split is taken
     *     against
     * @param interest {@code round(balanceBefore × apr ÷ 100 ÷ 12, 4, HALF_EVEN)}. The principal
     *     takes the remainder, so the split reconciles exactly
     */
    public static DebtPayment of(
            Debt debt,
            LocalDate paidDate,
            Money amount,
            Money interest,
            Money balanceBefore,
            PaymentProvenance provenance) {
        Objects.requireNonNull(debt, "debt");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(interest, "interest");
        Objects.requireNonNull(balanceBefore, "balanceBefore");
        requireSameCurrency(debt.currency(), amount, interest, balanceBefore);

        DebtPayment payment = new DebtPayment();
        payment.householdId = debt.householdId();
        payment.debtId = debt.id();
        payment.paidDate = Objects.requireNonNull(paidDate, "paidDate");
        payment.provenance = Objects.requireNonNull(provenance, "provenance");
        payment.apply(amount, interest, balanceBefore);
        return payment;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public UUID debtId() {
        return debtId;
    }

    public LocalDate paidDate() {
        return paidDate;
    }

    public BigDecimal amount() {
        return amount;
    }

    public BigDecimal interest() {
        return interest;
    }

    public BigDecimal principal() {
        return principal;
    }

    public BigDecimal balanceAfter() {
        return balanceAfter;
    }

    public PaymentProvenance provenance() {
        return provenance;
    }

    public Optional<UUID> transactionId() {
        return Optional.ofNullable(transactionId);
    }

    public Optional<UUID> billOccurrenceId() {
        return Optional.ofNullable(billOccurrenceId);
    }

    public Optional<UUID> paidFromAccountId() {
        return Optional.ofNullable(paidFromAccountId);
    }

    public Optional<String> note() {
        return Optional.ofNullable(note);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    /** What was owed before this payment: the split's own opening balance, kept derivable. */
    public BigDecimal balanceBefore() {
        return balanceAfter.add(principal);
    }

    public void on(LocalDate newPaidDate) {
        this.paidDate = Objects.requireNonNull(newPaidDate, "paidDate");
    }

    /**
     * Corrects the amount, <b>keeping the interest that was true on the day</b>.
     *
     * <p>The principal takes the difference and the closing balance follows, so the split still
     * reconciles. Recomputing the interest here would silently rewrite history against today's
     * rate, which is exactly what storing the split exists to prevent — a household correcting a
     * mistyped figure is not telling us the rate was different.
     */
    public void correctAmount(Money corrected) {
        Objects.requireNonNull(corrected, "amount");
        Currency currency = corrected.currency();
        apply(corrected, Money.of(interest, currency), Money.of(balanceBefore(), currency));
    }

    /**
     * Makes the payment principal only, keeping its amount and the balance it was taken against.
     *
     * <p>For one case only: a correction moves this payment into a month whose interest another
     * payment already carries. Interest is charged once per debt per month (ADR-0043), and a
     * payment logged into that month would have been principal only, so a payment moved there is
     * too — otherwise the month would be charged twice.
     */
    public void carryNoInterest(Currency currency) {
        Objects.requireNonNull(currency, "currency");
        apply(
                Money.of(amount, currency),
                Money.zero(currency),
                Money.of(balanceBefore(), currency));
    }

    /** Links the payment to the real movement, or clears the link. Evidence, not authority. */
    public void settledBy(UUID newTransactionId) {
        this.transactionId = newTransactionId;
    }

    /** Links the payment to the bill occurrence that wrote it, or clears the link. */
    public void recordedFrom(UUID newBillOccurrenceId) {
        this.billOccurrenceId = newBillOccurrenceId;
    }

    /**
     * Records which account the money came from, or clears it.
     *
     * <p>The id must already have been resolved through this household — the column's composite
     * foreign key refuses another household's account, but a {@code 404} read better than a
     * constraint violation.
     */
    public void paidFrom(UUID newPaidFromAccountId) {
        this.paidFromAccountId = newPaidFromAccountId;
    }

    /** A blank note is cleared rather than stored: {@code ck_debt_payments_note} refuses one. */
    public void annotate(String newNote) {
        if (newNote == null || newNote.isBlank()) {
            this.note = null;
            return;
        }
        String trimmed = newNote.strip();
        if (trimmed.length() > NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a note is at most " + NOTE_MAX_LENGTH + " characters");
        }
        this.note = trimmed;
    }

    private void apply(Money paid, Money interestThen, Money balanceBefore) {
        Money principalPart = paid.minus(interestThen);
        this.amount = paid.amount();
        this.interest = interestThen.amount();
        this.principal = principalPart.amount();
        this.balanceAfter = balanceBefore.minus(principalPart).amount();
    }

    private static void requireSameCurrency(Currency expected, Money... amounts) {
        for (Money amount : amounts) {
            if (!amount.currency().equals(expected)) {
                throw new CurrencyMismatchException(expected, amount.currency());
            }
        }
    }

    /**
     * Ids, a date and how it got here. <b>Never an amount</b>: a payment history is a behavioural
     * record — who pays late, who pays extra, and when money appeared.
     */
    @Override
    public String toString() {
        return "DebtPayment[id="
                + id
                + ", debtId="
                + debtId
                + ", paidDate="
                + paidDate
                + ", provenance="
                + provenance
                + "]";
    }
}
