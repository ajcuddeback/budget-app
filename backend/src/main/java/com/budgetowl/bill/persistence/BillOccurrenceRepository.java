package com.budgetowl.bill.persistence;

import com.budgetowl.bill.domain.BillOccurrence;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The occurrences a household has actually recorded something against.
 *
 * <p><b>There are far fewer rows here than there are occurrences.</b> Most of an occurrence's life
 * is spent projected rather than stored (ADR-0032), so an empty result for a period is not an
 * absence of bills — it means nobody has touched that month, which reads identically to every
 * occurrence still being {@code DUE}. {@code BillService} is where that equivalence lives.
 */
public interface BillOccurrenceRepository extends Repository<BillOccurrence, UUID> {

    BillOccurrence saveAndFlush(BillOccurrence occurrence);

    /** Forgets what was recorded. The period goes back to reading as its projection. */
    void delete(BillOccurrence occurrence);

    /**
     * Everything recorded for one month, for every bill at once.
     *
     * <p>Batched deliberately: the Bills &amp; Income page asks about a whole month, and one query
     * per bill would be an N+1 on the only screen this feature has.
     */
    @Query(
            """
            select o from BillOccurrence o
            where o.householdId = :householdId
              and o.period = :period
            order by o.dueDate asc, o.id asc
            """)
    java.util.List<BillOccurrence> findForPeriod(
            @Param("householdId") UUID householdId, @Param("period") LocalDate period);

    /**
     * One recorded occurrence by its own id.
     *
     * <p>Added for the debt slice: a debt payment may name the occurrence that wrote it ({@code
     * debt_payments.bill_occurrence_id}), and an id arriving in a request body is a lookup key that
     * has to be resolved through the household before anything references it (ADR-0017). The
     * occurrence routes themselves still address the natural key {@code (billId, dueDate)} — see
     * {@link #findOne} — because that URL is valid before a row exists at it.
     */
    Optional<BillOccurrence> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * How many rows one bill has.
     *
     * <p>Scoped on the household as well as the bill, as everything here is, although the bill was
     * already resolved against the household before this is called.
     */
    @Query(
            """
            select count(o) from BillOccurrence o
            where o.householdId = :householdId
              and o.billId = :billId
            """)
    long countForBill(@Param("householdId") UUID householdId, @Param("billId") UUID billId);

    /**
     * The row for one bill on one due date, if anybody has recorded one.
     *
     * <p>{@code (billId, dueDate)} is the occurrence's real identity — see {@code
     * ux_bill_occurrences_bill_due_date}. The household id is still a parameter, and the bill's own
     * household is still checked before this is called: an id in a path is a lookup key.
     */
    @Query(
            """
            select o from BillOccurrence o
            where o.householdId = :householdId
              and o.billId = :billId
              and o.dueDate = :dueDate
            """)
    Optional<BillOccurrence> findOne(
            @Param("householdId") UUID householdId,
            @Param("billId") UUID billId,
            @Param("dueDate") LocalDate dueDate);
}
