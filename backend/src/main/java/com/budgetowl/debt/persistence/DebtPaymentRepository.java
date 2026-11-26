package com.budgetowl.debt.persistence;

import com.budgetowl.debt.domain.DebtPayment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The payments recorded against a household's debts — the audit trail its balances are derived
 * from.
 *
 * <p>Every method takes a {@code householdId} or the entity itself, as everywhere (ADR-0017). The
 * debt id is a second condition and never a substitute for the first: an id in a path is a lookup
 * key, so {@code (householdId, debtId)} is the predicate even though the debt was already resolved
 * against the household before any of this is called.
 *
 * <p><b>The history pages in the query</b>, not in Java. A household with years of records has more
 * payments than one response should carry, which the threat model names as a live finding
 * (docs/features/debt-plan.md). Sorting is {@code paid_date DESC, id DESC}, matching {@code
 * ix_debt_payments_debt_paid_date}: the tiebreak on the id is what stops a page repeating or
 * skipping a row when several payments share a date — which, for a household catching up on a
 * month, is the normal case.
 */
public interface DebtPaymentRepository extends Repository<DebtPayment, UUID> {

    /** See {@code AccountRepository.saveAndFlush} — {@code @Version} is bumped at flush. */
    DebtPayment saveAndFlush(DebtPayment payment);

    void delete(DebtPayment payment);

    /** One payment of one debt, or empty — which is a {@code 404}, whichever part was wrong. */
    @Query(
            """
            select p from DebtPayment p
            where p.householdId = :householdId
              and p.debtId = :debtId
              and p.id = :paymentId
            """)
    Optional<DebtPayment> findOne(
            @Param("householdId") UUID householdId,
            @Param("debtId") UUID debtId,
            @Param("paymentId") UUID paymentId);

    /**
     * One debt's history, newest first, one page at a time.
     *
     * <p><b>The second sort key is load-bearing.</b> Several rows routinely share a {@code
     * paidDate} — logging a payment and then confirming the statement that followed it writes two
     * rows dated the same day — and the id is a random {@code UUID}, so ordering by it put them in
     * an arbitrary order. The history's {@code balanceAfter} column is a running figure, so an
     * arbitrary order makes it stop reconciling: the screenshot that found this showed a payment
     * leaving $6,162.62 printed <em>above</em> the correction that had since brought it to the
     * $6,180.00 the headline was reporting.
     *
     * <p>It also made the generated demo fixtures non-deterministic, since every regeneration mints
     * new ids and could flip the two rows.
     *
     * <p>{@code createdAt} is the row's own insertion time, which separates two requests. Rows
     * written inside <b>one</b> transaction still tie, because PostgreSQL's {@code now()} is the
     * transaction's start time — the id then breaks the tie, so the order is at least stable.
     */
    @Query(
            """
            select p from DebtPayment p
            where p.householdId = :householdId
              and p.debtId = :debtId
            order by p.paidDate desc, p.createdAt desc, p.id desc
            """)
    Page<DebtPayment> findForDebt(
            @Param("householdId") UUID householdId,
            @Param("debtId") UUID debtId,
            Pageable pageable);

    long countByHouseholdIdAndDebtId(UUID householdId, UUID debtId);

    /**
     * Rolls every anchor in the household forward in <b>one</b> query, rather than one query per
     * debt on the two screens that list them all.
     *
     * <p>The join to {@code Debt} is what carries each debt's own anchor date into the predicate,
     * and it is scoped on the household twice over — once per table — so neither side can be the
     * one that leaks.
     *
     * <p><b>On or after the anchor date, and the two exclusions are load-bearing.</b> The anchor is
     * the figure as at the <em>start</em> of its date, so a payment made on that date applies to it
     * — otherwise a household that records a debt from today's statement and then logs the payment
     * it made today watches nothing happen, which is the first thing anybody does.
     *
     * <p>A {@code STATEMENT_CORRECTION} dated exactly on the anchor date is the row that
     * <em>explains</em> where that anchor came from (see {@code DebtService.confirmBalance}), so
     * applying it again would count the statement's disagreement twice. It stands in the history
     * either way, which is the point of recording it rather than adjusting the figure silently.
     */
    @Query(
            """
            select new com.budgetowl.debt.persistence.PrincipalSinceAnchor(
                       p.debtId, sum(p.principal))
            from DebtPayment p, Debt d
            where d.id = p.debtId
              and d.householdId = :householdId
              and p.householdId = :householdId
              and p.paidDate >= d.anchorDate
              and not (p.paidDate = d.anchorDate
                       and p.provenance
                           = com.budgetowl.debt.domain.PaymentProvenance.STATEMENT_CORRECTION)
            group by p.debtId
            """)
    List<PrincipalSinceAnchor> principalPaidSinceAnchor(@Param("householdId") UUID householdId);

    /**
     * One debt's principal paid on or after a date, under the same two exclusions.
     *
     * <p>What a balance confirmation needs: the anchor it writes has to absorb the payments its own
     * date will re-apply, or the confirmed figure and the figure on screen differ by exactly those
     * payments. Null for a debt with none, rather than zero — the caller says what absent means.
     */
    @Query(
            """
            select sum(p.principal) from DebtPayment p
            where p.householdId = :householdId
              and p.debtId = :debtId
              and p.paidDate >= :date
              and not (p.paidDate = :date
                       and p.provenance
                           = com.budgetowl.debt.domain.PaymentProvenance.STATEMENT_CORRECTION)
            """)
    java.math.BigDecimal principalPaidOnOrAfter(
            @Param("householdId") UUID householdId,
            @Param("debtId") UUID debtId,
            @Param("date") java.time.LocalDate date);

    /**
     * What completed weekly check-ins recorded against debts in one currency between two dates. The
     * term next week's figure takes off {@code free(M)} (docs/features/weekly-check-in.md, decision
     * 1). Null for none, rather than zero.
     */
    @Query(
            """
            select sum(p.amount) from DebtPayment p, Debt d
            where d.id = p.debtId
              and d.householdId = :householdId
              and p.householdId = :householdId
              and d.currency = :currency
              and p.provenance = com.budgetowl.debt.domain.PaymentProvenance.CHECK_IN
              and p.paidDate >= :from
              and p.paidDate <= :to
            """)
    java.math.BigDecimal checkInPaymentsBetween(
            @Param("householdId") UUID householdId,
            @Param("currency") String currency,
            @Param("from") java.time.LocalDate from,
            @Param("to") java.time.LocalDate to);

    /** When each debt was last paid, and how many times, for every debt at once. */
    @Query(
            """
            select new com.budgetowl.debt.persistence.PaymentActivity(
                       p.debtId, max(p.paidDate), count(p))
            from DebtPayment p
            where p.householdId = :householdId
            group by p.debtId
            """)
    List<PaymentActivity> activity(@Param("householdId") UUID householdId);

    /**
     * Whether a transaction already settles a debt payment.
     *
     * <p>{@code ux_debt_payments_transaction_id} is the control — a transaction settles at most one
     * debt payment, ever (ADR-0031's argument) — and this is the look-up that turns the violation
     * into an answer a person can read instead of a {@code 500}.
     */
    @Query(
            """
            select count(p) from DebtPayment p
            where p.householdId = :householdId
              and p.transactionId = :transactionId
              and p.id <> :exceptPaymentId
            """)
    long countOtherPaymentsSettledBy(
            @Param("householdId") UUID householdId,
            @Param("transactionId") UUID transactionId,
            @Param("exceptPaymentId") UUID exceptPaymentId);
}
