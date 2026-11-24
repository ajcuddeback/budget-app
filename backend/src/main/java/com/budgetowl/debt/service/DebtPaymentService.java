package com.budgetowl.debt.service;

import com.budgetowl.common.AmountText;
import com.budgetowl.common.Change;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.Money;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.debt.domain.Debt;
import com.budgetowl.debt.domain.DebtPayment;
import com.budgetowl.debt.domain.PaymentProvenance;
import com.budgetowl.debt.persistence.DebtPaymentRepository;
import com.budgetowl.debt.persistence.DebtRepository;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payments recorded against a debt — the audit trail its balance is derived from.
 *
 * <p><b>A payment is a plan record, not a transaction</b> (ADR-0035). Logging one writes no ledger
 * row and moves no account balance; it carries a nullable {@code transactionId} naming the real
 * movement when the household knows it, and that link is evidence rather than authority. A
 * household that logs a payment and records no transaction has a correct debt balance and an
 * account balance that is too high — the same gap bills have, for the same reason, and it is
 * visible rather than hidden.
 *
 * <p>Four rules are specific to this service:
 *
 * <ul>
 *   <li><b>The split is computed once and stored.</b> {@code interest = round(balance × apr ÷ 100 ÷
 *       12, 4, HALF_EVEN)} at the time, the principal takes the remainder, so {@code interest +
 *       principal = amount} exactly and no payment loses or invents a cent.
 *   <li><b>A correction keeps the interest.</b> Recomputing it against today's rate would rewrite
 *       history to agree with the present.
 *   <li><b>Overpaying is not clamped.</b> A payment whose principal exceeds the balance takes it
 *       below zero and the screen says the debt is overpaid by that much. Clamping would hide a
 *       real error in somebody's favour.
 *   <li><b>A transaction settles at most one debt payment, ever</b> — {@code
 *       ux_debt_payments_transaction_id}, and ADR-0031's argument about one transaction and one
 *       envelope entry. Counting one charge against two debts would understate both balances
 *       plausibly.
 * </ul>
 *
 * <p>Scoped and role-checked as everywhere: another household's debt or payment is a {@code 404},
 * and a {@code VIEWER} reads the whole history and records nothing.
 */
@Service
public class DebtPaymentService {

    private static final Logger log = LoggerFactory.getLogger(DebtPaymentService.class);

    private final MembershipService memberships;
    private final DebtRepository debts;
    private final DebtPaymentRepository payments;
    private final DebtBalanceService balances;
    private final DebtLinks links;

    public DebtPaymentService(
            MembershipService memberships,
            DebtRepository debts,
            DebtPaymentRepository payments,
            DebtBalanceService balances,
            DebtLinks links) {
        this.memberships = memberships;
        this.debts = debts;
        this.payments = payments;
        this.balances = balances;
        this.links = links;
    }

    /**
     * One debt's history, newest first, <b>one page at a time</b>.
     *
     * <p>The page envelope every collection returns (docs/guides/api-style.md), and here also a
     * threat-model control: a household with years of records has more payments than one response
     * should carry. Default 50, maximum 200, and a request over the maximum is clamped rather than
     * rejected.
     */
    @Transactional(readOnly = true)
    public DebtPaymentView.Page list(UUID actorId, UUID debtId, int page, int size) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        Household household = membership.household();
        Debt debt = require(debtId, household.id());

        Page<DebtPayment> found =
                payments.findForDebt(household.id(), debt.id(), pageable(page, size));
        List<DebtPaymentView> views = new ArrayList<>(found.getNumberOfElements());
        for (DebtPayment payment : found.getContent()) {
            views.add(view(payment, debt.currency()));
        }
        return new DebtPaymentView.Page(
                List.copyOf(views),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /**
     * Logs a payment.
     *
     * @throws com.budgetowl.common.ForbiddenException for a {@code VIEWER}
     * @throws ConflictException {@code debt-paid-off}, or {@code conflict} where the transaction
     *     already settles another payment or the debt has the most payments we allow
     * @throws NotFoundException if the debt, the transaction or the occurrence is not this
     *     household's
     */
    @Transactional
    public DebtPaymentView log(UUID actorId, UUID debtId, NewDebtPayment requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Debt debt = require(debtId, household.id());
        requireNotSettled(debt);

        if (payments.countByHouseholdIdAndDebtId(household.id(), debt.id())
                >= DebtService.MAXIMUM_PAYMENTS_PER_DEBT) {
            throw new ConflictException(
                    ErrorCode.CONFLICT, "this debt already has the most payments we allow");
        }

        Currency currency = debt.currency();
        Money amount =
                AmountText.parse(requested.amount(), currency, AmountText.Sign.POSITIVE, "amount");
        LocalDate paidDate = requireNotInTheFuture(requested.paidDate(), "paidDate");

        DebtFacts facts = balances.factsFor(debt);
        Money balanceBefore = facts.balance();
        DebtPayment payment =
                DebtPayment.of(
                        debt,
                        paidDate,
                        amount,
                        interestOn(balanceBefore, facts),
                        balanceBefore,
                        PaymentProvenance.USER_LOGGED);
        payment.annotate(requested.note());
        if (requested.transactionId() != null) {
            payment.settledBy(link(requested.transactionId(), household.id(), payment.id()));
        }
        if (requested.billOccurrenceId() != null) {
            payment.recordedFrom(
                    links.requireOccurrence(requested.billOccurrenceId(), household.id()).id());
        }

        DebtPayment saved = save(payment);
        settleOrReopen(debt);
        log.info(
                "debt payment logged householdId={} debtId={} paymentId={} byUserId={} linked={}",
                household.id(),
                debtId,
                saved.id(),
                actorId,
                saved.transactionId().isPresent());
        return view(saved, currency);
    }

    /**
     * Corrects a payment somebody recorded.
     *
     * <p>The stored interest stands and the principal takes the difference — see {@code
     * PaymentChanges}. A household editing its own payment record is not an attack; it is the
     * feature.
     */
    @Transactional
    public DebtPaymentView correct(
            UUID actorId, UUID debtId, UUID paymentId, PaymentChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        Debt debt = require(debtId, household.id());
        DebtPayment payment = requirePayment(household.id(), debt.id(), paymentId);
        Currency currency = debt.currency();

        requireCurrentVersion(payment.version(), changes.version());

        if (changes.paidDate() != null) {
            payment.on(requireNotInTheFuture(changes.paidDate(), "paidDate"));
        }
        if (changes.amount() != null) {
            payment.correctAmount(
                    AmountText.parse(
                            changes.amount(), currency, AmountText.Sign.POSITIVE, "amount"));
        }
        switch (changes.transactionId()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<UUID> ignored -> payment.settledBy(null);
            case Change.SetTo<UUID> settled ->
                    payment.settledBy(link(settled.value(), household.id(), payment.id()));
        }
        switch (changes.note()) {
            case Change.Unchanged<String> ignored -> {
                // Not mentioned.
            }
            case Change.Cleared<String> ignored -> payment.annotate(null);
            case Change.SetTo<String> written -> payment.annotate(written.value());
        }

        DebtPayment saved = save(payment);
        settleOrReopen(debt);
        log.info(
                "debt payment corrected householdId={} debtId={} paymentId={} byUserId={}",
                household.id(),
                debtId,
                paymentId,
                actorId);
        return view(saved, currency);
    }

    /**
     * Removes a payment.
     *
     * <p>The balance moves back, because it was derived from this row and no other. Nothing in the
     * ledger is touched: a payment that named a transaction was pointing at it, not owning it.
     */
    @Transactional
    public void delete(UUID actorId, UUID debtId, UUID paymentId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Debt debt = require(debtId, householdId);
        payments.delete(requirePayment(householdId, debt.id(), paymentId));
        settleOrReopen(debt);
        log.info(
                "debt payment deleted householdId={} debtId={} paymentId={} byUserId={}",
                householdId,
                debtId,
                paymentId,
                actorId);
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * What a settled or overpaid balance accrues: nothing.
     *
     * <p>{@code interestFor} is arithmetic and would happily return a negative figure on a negative
     * balance, which would then be reported as interest somebody was charged. A debt below zero is
     * owed nothing and charged nothing.
     */
    private Money interestOn(Money balanceBefore, DebtFacts facts) {
        return balanceBefore.isPositive()
                ? balances.solver().interestFor(balanceBefore, facts.apr())
                : Money.zero(balanceBefore.currency());
    }

    /**
     * Keeps {@code paid_off_at} in step with the balance the payments now imply.
     *
     * <p>Run after every payment write, including a delete: a debt is settled when its derived
     * balance reaches zero and reopened when a correction puts a balance back on it. Storing the
     * flag rather than deriving it is deliberate — it is what the ladder and the "writes are
     * refused" rule read, and it records <em>when</em> the household got there.
     */
    private void settleOrReopen(Debt debt) {
        Money balance = balances.factsFor(debt).balance();
        boolean settled = debt.isPaidOff();
        if (!balance.isPositive() && !settled) {
            debt.settle(balances.now());
            debts.saveAndFlush(debt);
        } else if (balance.isPositive() && settled) {
            debt.reopen();
            debts.saveAndFlush(debt);
        }
    }

    /**
     * Resolves the transaction a payment points at, and refuses one that already settles another.
     *
     * <p>Resolved through the household first — the same path a plain transaction read uses, and
     * never taken on trust because the debt happens to reference the account. The uniqueness
     * look-up is the readable answer; {@code ux_debt_payments_transaction_id} is the control.
     */
    private UUID link(UUID transactionId, UUID householdId, UUID paymentId) {
        UUID resolved = links.requireTransaction(transactionId, householdId);
        UUID except = paymentId == null ? new UUID(0L, 0L) : paymentId;
        if (payments.countOtherPaymentsSettledBy(householdId, resolved, except) > 0) {
            throw new ConflictException(
                    ErrorCode.CONFLICT, "this transaction already settles a debt payment");
        }
        return resolved;
    }

    private static DebtPaymentView view(DebtPayment payment, Currency currency) {
        return new DebtPaymentView(
                payment.id(),
                payment.debtId(),
                payment.paidDate(),
                Money.of(payment.amount(), currency),
                Money.of(payment.interest(), currency),
                Money.of(payment.principal(), currency),
                Money.of(payment.balanceAfter(), currency),
                payment.provenance().name(),
                payment.transactionId().orElse(null),
                payment.billOccurrenceId().orElse(null),
                payment.note().orElse(null),
                payment.version());
    }

    private static Pageable pageable(int page, int size) {
        int clamped =
                Math.clamp(
                        size <= 0 ? com.budgetowl.common.web.PageResponse.DEFAULT_SIZE : size,
                        1,
                        com.budgetowl.common.web.PageResponse.MAXIMUM_SIZE);
        return PageRequest.of(Math.max(page, 0), clamped);
    }

    private Debt require(UUID debtId, UUID householdId) {
        return debts.findByIdAndHouseholdId(debtId, householdId)
                .orElseThrow(() -> new NotFoundException("no such debt"));
    }

    private DebtPayment requirePayment(UUID householdId, UUID debtId, UUID paymentId) {
        return payments.findOne(householdId, debtId, paymentId)
                .orElseThrow(() -> new NotFoundException("no such payment"));
    }

    private LocalDate requireNotInTheFuture(LocalDate date, String field) {
        if (date == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "a date is required", Map.of("field", field));
        }
        if (date.isAfter(balances.today())) {
            // A payment somebody intends to make is not a payment. The balance would move for a
            // month that has not happened, and the audit trail would carry a future fact.
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "a payment cannot be dated in the future",
                    Map.of("field", field));
        }
        return date;
    }

    /** See {@code AccountService.requireCurrentVersion} — the clear message, not the control. */
    private static void requireCurrentVersion(long actual, long expected) {
        if (actual != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the payment changed since it was read");
        }
    }

    private static void requireNotSettled(Debt debt) {
        if (debt.isPaidOff()) {
            throw new ConflictException(
                    ErrorCode.DEBT_PAID_OFF,
                    "this debt is paid off; confirm a balance to reopen it");
        }
    }

    /**
     * Saves and flushes, turning the two uniqueness indexes into answers a person can read.
     *
     * <p>The look-up in {@link #link} is the good path; these are the controls. Two members
     * attaching the same charge at the same moment both find nothing — and one of them still has to
     * be told, and told that it is already recorded rather than that something broke.
     */
    private DebtPayment save(DebtPayment payment) {
        try {
            return payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException violation) {
            if (mentions(violation, "ux_debt_payments_transaction_id")) {
                throw new ConflictException(
                        ErrorCode.CONFLICT, "this transaction already settles a debt payment");
            }
            if (mentions(violation, "ux_debt_payments_bill_occurrence_id")) {
                throw new ConflictException(
                        ErrorCode.CONFLICT, "this bill occurrence already has a payment");
            }
            throw violation;
        }
    }

    /** See {@code BillService.mentions} — matched on our own constraint name, not on text. */
    private static boolean mentions(DataIntegrityViolationException violation, String constraint) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
