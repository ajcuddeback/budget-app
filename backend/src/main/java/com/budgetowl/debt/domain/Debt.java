package com.budgetowl.debt.domain;

import com.budgetowl.common.CurrencyMismatchException;
import com.budgetowl.common.HouseholdOwned;
import com.budgetowl.common.Money;
import com.budgetowl.household.domain.Household;
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
import java.time.YearMonth;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A balance you are reducing, with terms.
 *
 * <p><b>Only entered values are stored, and {@code null} is the entered/solved flag.</b> {@link
 * #apr()}, {@link #payment()} and the two faces of the horizon hold what the household typed and
 * nothing else; everything a solver worked out is derived on every read. A stored solved value
 * would be a second copy of a derivable fact, and the copy is what a reader would act on after the
 * fact moved.
 *
 * <p><b>There is no current balance here either.</b> What is stored is the anchor — {@link
 * #anchorBalance()}, {@link #anchorDate()}, {@link #balanceSource()} — and today's balance is that
 * rolled forward by the payments after it. A stored balance would be a fourth figure to keep in
 * step with the anchor, the payments and the linked account, and the first of them to drift.
 *
 * <p><b>And no rung.</b> For {@code AVALANCHE} and {@code SNOWBALL} the order comes from the
 * strategy; only {@code CUSTOM} stores one, in {@link #customOrder()}.
 *
 * <p>The degrees-of-freedom rule is {@code ck_debts_degrees_of_freedom} in the database and {@link
 * #isOverDetermined()} here. It is deliberately <b>not</b> asserted inside the setters: a terms
 * edit that releases one field and enters another passes through an over-determined state on its
 * way, and refusing that would make the legal edit unwritable.
 */
@Entity
@Table(name = "debts")
public class Debt implements HouseholdOwned {

    public static final int NAME_MAX_LENGTH = 60;
    public static final int SERVICER_MAX_LENGTH = 80;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private DebtKind kind;

    @Column(name = "servicer")
    private String servicer;

    @Column(name = "currency", nullable = false)
    private String currency;

    /** A <b>percentage</b> — {@code 22.900}, as a statement prints it. Null means solved. */
    @Column(name = "apr")
    private BigDecimal apr;

    @Column(name = "minimum_payment", nullable = false)
    private BigDecimal minimumPayment;

    @Column(name = "original_balance")
    private BigDecimal originalBalance;

    @Column(name = "anchor_balance", nullable = false)
    private BigDecimal anchorBalance;

    @Column(name = "anchor_date", nullable = false)
    private LocalDate anchorDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "balance_source", nullable = false)
    private BalanceSource balanceSource = BalanceSource.CONFIRMED;

    /** Entered only. The plan's allocation is not stored anywhere. */
    @Column(name = "payment")
    private BigDecimal payment;

    @Column(name = "months_left")
    private Integer monthsLeft;

    /** The first day of the labelled month, as {@code envelopes.period} is (ADR-0031). */
    @Column(name = "payoff_month")
    private LocalDate payoffMonth;

    @Column(name = "custom_order")
    private Integer customOrder;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "bill_id")
    private UUID billId;

    @Column(name = "paid_off_at")
    private Instant paidOffAt;

    @Column(name = "archived", nullable = false)
    private boolean archived;

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
    protected Debt() {
        // Hibernate, and of().
    }

    /**
     * A new debt: a name, a kind, a floor, and the balance it starts from.
     *
     * @param anchorBalance what is owed, and the currency the whole debt is denominated in. Must be
     *     positive: a debt <em>created</em> at zero is nothing to reduce, which is a different
     *     claim from a debt that has <em>reached</em> zero
     * @param anchorDate the date that balance was true
     */
    public static Debt of(
            Household household,
            String name,
            DebtKind kind,
            Money anchorBalance,
            LocalDate anchorDate,
            Money minimumPayment) {
        Objects.requireNonNull(household, "household");
        Objects.requireNonNull(anchorBalance, "anchorBalance");
        if (!anchorBalance.isPositive()) {
            throw new IllegalArgumentException("a debt starts from a positive balance");
        }
        Debt debt = new Debt();
        debt.householdId = household.id();
        debt.currency = anchorBalance.currency().getCurrencyCode();
        debt.rename(name);
        debt.kind = Objects.requireNonNull(kind, "kind");
        debt.anchorAt(anchorBalance, anchorDate, BalanceSource.CONFIRMED);
        debt.setMinimumPayment(minimumPayment);
        return debt;
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public String name() {
        return name;
    }

    public DebtKind kind() {
        return kind;
    }

    public Optional<String> servicer() {
        return Optional.ofNullable(servicer);
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    /** The entered rate, or empty when it is solved. */
    public Optional<BigDecimal> apr() {
        return Optional.ofNullable(apr);
    }

    public Money minimumPayment() {
        return Money.of(minimumPayment, currency());
    }

    public Optional<Money> originalBalance() {
        return Optional.ofNullable(originalBalance).map(figure -> Money.of(figure, currency()));
    }

    public Money anchorBalance() {
        return Money.of(anchorBalance, currency());
    }

    public LocalDate anchorDate() {
        return anchorDate;
    }

    public BalanceSource balanceSource() {
        return balanceSource;
    }

    /** The entered payment, or empty when the plan supplies it or it is solved. */
    public Optional<Money> payment() {
        return Optional.ofNullable(payment).map(figure -> Money.of(figure, currency()));
    }

    public Optional<Integer> monthsLeft() {
        return Optional.ofNullable(monthsLeft);
    }

    public Optional<YearMonth> payoffMonth() {
        return Optional.ofNullable(payoffMonth).map(YearMonth::from);
    }

    /** True when either face of the horizon was entered. They are one unknown. */
    public boolean hasEnteredHorizon() {
        return monthsLeft != null || payoffMonth != null;
    }

    public Optional<Integer> customOrder() {
        return Optional.ofNullable(customOrder);
    }

    public Optional<UUID> accountId() {
        return Optional.ofNullable(accountId);
    }

    public Optional<UUID> billId() {
        return Optional.ofNullable(billId);
    }

    public Optional<Instant> paidOffAt() {
        return Optional.ofNullable(paidOffAt);
    }

    public boolean isPaidOff() {
        return paidOffAt != null;
    }

    public boolean isArchived() {
        return archived;
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

    /**
     * All three solvable quantities entered, which over-determines one equation.
     *
     * <p>The answer to this is {@code 422 terms-over-determined} naming the fields — never dropping
     * one. {@code ck_debts_degrees_of_freedom} says the same thing at the other end.
     */
    public boolean isOverDetermined() {
        return apr != null && payment != null && hasEnteredHorizon();
    }

    /** Both faces of one unknown, which cannot both be stored: they could disagree. */
    public boolean hasBothFacesOfTheHorizon() {
        return monthsLeft != null && payoffMonth != null;
    }

    public void rename(String newName) {
        String trimmed = Objects.requireNonNull(newName, "name").strip();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a debt name must be 1–" + NAME_MAX_LENGTH + " characters");
        }
        this.name = trimmed;
    }

    public void reclassify(DebtKind newKind) {
        this.kind = Objects.requireNonNull(newKind, "kind");
    }

    /** A blank servicer is cleared rather than stored: {@code ck_debts_servicer} refuses one. */
    public void servicedBy(String newServicer) {
        if (newServicer == null || newServicer.isBlank()) {
            this.servicer = null;
            return;
        }
        String trimmed = newServicer.strip();
        if (trimmed.length() > SERVICER_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a servicer name is at most " + SERVICER_MAX_LENGTH + " characters");
        }
        this.servicer = trimmed;
    }

    /**
     * Moves the anchor: what was owed, when that was true, and how we know.
     *
     * <p>Deliberately accepts zero and a negative figure. A confirmed zero is how a debt is paid
     * off and a negative one is an overpayment the screen has to be able to show — clamping either
     * would hide a real error in somebody's favour.
     */
    public void anchorAt(Money balance, LocalDate asOf, BalanceSource source) {
        requireSameCurrency(Objects.requireNonNull(balance, "anchorBalance"));
        this.anchorBalance = balance.amount();
        this.anchorDate = Objects.requireNonNull(asOf, "anchorDate");
        this.balanceSource = Objects.requireNonNull(source, "balanceSource");
    }

    /** Zero is legal: a promotional period with no minimum due is better recorded than refused. */
    public void setMinimumPayment(Money floor) {
        requireSameCurrency(Objects.requireNonNull(floor, "minimumPayment"));
        if (floor.isNegative()) {
            throw new IllegalArgumentException("a minimum payment cannot be negative");
        }
        this.minimumPayment = floor.amount();
    }

    /**
     * The progress baseline, or null for no baseline at all.
     *
     * <p>Progress is {@code (original − balance) ÷ original}, so a zero baseline is a division by
     * zero and a negative one inverts the percentage. Absent is the honest value and the screen
     * renders no progress rather than 0%.
     */
    public void setOriginalBalance(Money baseline) {
        if (baseline == null) {
            this.originalBalance = null;
            return;
        }
        requireSameCurrency(baseline);
        if (!baseline.isPositive()) {
            throw new IllegalArgumentException("an original balance is positive or absent");
        }
        this.originalBalance = baseline.amount();
    }

    public void enterApr(BigDecimal rate) {
        Objects.requireNonNull(rate, "apr");
        if (rate.signum() < 0 || rate.compareTo(new BigDecimal("1000")) >= 0) {
            throw new IllegalArgumentException("a rate is at least 0% and under 1000%");
        }
        this.apr = rate;
    }

    /** Releases the rate to the solver. {@code NULL} is the flag — see the class Javadoc. */
    public void releaseApr() {
        this.apr = null;
    }

    public void enterPayment(Money made) {
        requireSameCurrency(Objects.requireNonNull(made, "payment"));
        if (!made.isPositive()) {
            throw new IllegalArgumentException("an entered payment clears something");
        }
        this.payment = made.amount();
    }

    public void releasePayment() {
        this.payment = null;
    }

    /** Enters the horizon as a month count, clearing the other face of the same unknown. */
    public void enterMonthsLeft(int months) {
        if (months < 1) {
            throw new IllegalArgumentException("a horizon is at least one month");
        }
        this.monthsLeft = months;
        this.payoffMonth = null;
    }

    /** Enters the horizon as a month, clearing the other face of the same unknown. */
    public void enterPayoffMonth(YearMonth month) {
        this.payoffMonth = Objects.requireNonNull(month, "payoffMonth").atDay(1);
        this.monthsLeft = null;
    }

    /** Releases the horizon to the solver — both faces, because they are one unknown. */
    public void releaseHorizon() {
        this.monthsLeft = null;
        this.payoffMonth = null;
    }

    /** The household's own position under {@code CUSTOM}, or null for one it has not placed. */
    public void placeAt(Integer position) {
        if (position != null && position < 0) {
            throw new IllegalArgumentException("a custom order is not negative");
        }
        this.customOrder = position;
    }

    /**
     * Binds the debt to an account this household holds, or unbinds it.
     *
     * <p>The id is resolved through the membership-scoped query before this is called, and {@code
     * fk_debts_account} makes the row unwritable otherwise. Two layers, and the second cannot be
     * forgotten by a future caller.
     */
    public void heldAt(UUID newAccountId) {
        this.accountId = newAccountId;
    }

    /** Names the bill whose occurrence pays this minimum — <b>never inferred</b>, always set. */
    public void minimumPaidBy(UUID newBillId) {
        this.billId = newBillId;
    }

    /** The balance reached zero: it leaves the ladder and keeps its history. */
    public void settle(Instant when) {
        this.paidOffAt = Objects.requireNonNull(when, "paidOffAt");
    }

    /** A correction put a balance back on it. */
    public void reopen() {
        this.paidOffAt = null;
    }

    public void archive() {
        this.archived = true;
    }

    public void unarchive() {
        this.archived = false;
    }

    private void requireSameCurrency(Money amount) {
        if (!amount.currency().equals(currency())) {
            throw new CurrencyMismatchException(currency(), amount.currency());
        }
    }

    /**
     * Ids only — no name, no servicer, no amount, no rate.
     *
     * <p>Hibernate prints every mapped column of a managed entity at flush through its own {@code
     * EntityPrinter}, which does not use this method; that is pinned in {@code application.yml}.
     * This is the defence against our own logging, and the servicer is the sharpest field in the
     * row: one string, no amount attached, and it still discloses.
     */
    @Override
    public String toString() {
        return "Debt[id=" + id + ", kind=" + kind + ", archived=" + archived + "]";
    }
}
