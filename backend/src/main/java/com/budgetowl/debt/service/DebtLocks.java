package com.budgetowl.debt.service;

import com.budgetowl.common.NotFoundException;
import com.budgetowl.debt.domain.Debt;
import com.budgetowl.debt.persistence.DebtRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The row locks every write to a debt's payments takes (ADR-0043, docs/features/debt-plan.md, The
 * split, rule 5). One place, so every writer locks the same way.
 *
 * <ul>
 *   <li><b>One debt:</b> {@link #lock}. A payment's split reads the debt's balance and whether its
 *       month's interest is already booked, then writes a row that changes both; two writers at
 *       once would both read the month as unbooked.
 *   <li><b>Several debts:</b> {@link #lockInOrder}, in one global order, so two transactions taking
 *       the same debts can only wait for each other, never deadlock.
 *   <li><b>Fresh state.</b> The lock is taken by <em>refreshing</em> the row {@code FOR UPDATE},
 *       not by a query: a query returns an entity the transaction already loaded exactly as it was
 *       loaded, so a check-in completion that read its debts before locking them would decide
 *       against a paid-off flag or an anchor from before the wait.
 *   <li><b>Bounded waits.</b> {@link #LOCK_TIMEOUT} at most, so a transaction that hangs holding a
 *       debt cannot block it forever. Running out is {@code CannotAcquireLockException}, which the
 *       API answers with a retryable {@code 409 record-busy}.
 * </ul>
 *
 * <p>Every look-up goes through the household first, as everywhere (ADR-0017): a debt that is not
 * this household's is never locked, refreshed or returned.
 */
@Component
public class DebtLocks {

    /** How long a writer waits for a debt another transaction holds, in PostgreSQL's syntax. */
    static final String LOCK_TIMEOUT = "3s";

    private final DebtRepository debts;
    private final EntityManager entityManager;

    public DebtLocks(DebtRepository debts, EntityManager entityManager) {
        this.debts = debts;
        this.entityManager = entityManager;
    }

    /**
     * The household's debt, locked {@code FOR UPDATE} until the transaction ends, with its state
     * read after the lock was granted.
     *
     * @throws NotFoundException if it is not this household's
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Debt lock(UUID debtId, UUID householdId) {
        Debt debt =
                debts.findByIdAndHouseholdId(debtId, householdId)
                        .orElseThrow(() -> new NotFoundException("no such debt"));
        boundTheWait();
        entityManager.refresh(debt, LockModeType.PESSIMISTIC_WRITE);
        return debt;
    }

    /**
     * Every one of {@code debtIds} that is this household's, locked and refreshed in {@link
     * #lockOrder}. A debt that is not the household's, or no longer exists, is skipped: the write
     * that names it answers for it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockInOrder(UUID householdId, Collection<UUID> debtIds) {
        boundTheWait();
        for (UUID debtId : lockOrder(debtIds)) {
            debts.findByIdAndHouseholdId(debtId, householdId)
                    .ifPresent(debt -> entityManager.refresh(debt, LockModeType.PESSIMISTIC_WRITE));
        }
    }

    /**
     * The one order debt rows are locked in when more than one is: by the id's text, which is also
     * PostgreSQL's order for a {@code uuid}. Distinct, so a debt named twice is locked once.
     */
    public static List<UUID> lockOrder(Collection<UUID> debtIds) {
        return debtIds.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    /**
     * {@code SET LOCAL lock_timeout}: for the rest of this transaction only, and gone at commit or
     * rollback. Set before each lock so a writer that locks more than once is bounded every time.
     */
    private void boundTheWait() {
        entityManager
                .createNativeQuery("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'")
                .executeUpdate();
    }
}
