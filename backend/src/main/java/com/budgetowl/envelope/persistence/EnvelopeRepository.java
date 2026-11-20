package com.budgetowl.envelope.persistence;

import com.budgetowl.envelope.domain.Envelope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Envelopes.
 *
 * <p>Every method takes a {@code householdId} or the entity itself — see {@code AccountRepository}
 * for the reasoning and {@code ArchitectureTest} for what fails the build if one does not. Sorting
 * is fixed in the queries, never taken from a parameter.
 */
public interface EnvelopeRepository extends Repository<Envelope, UUID> {

    Envelope save(Envelope envelope);

    /** See {@code AccountRepository.saveAndFlush} — {@code @Version} is bumped at flush. */
    Envelope saveAndFlush(Envelope envelope);

    /**
     * Deletes an envelope and, by {@code fk_envelope_entries_envelope}, its entries.
     *
     * <p>CASCADE here where transactions use RESTRICT, deliberately: an entry has no meaning
     * without its envelope, while a transaction is a record of something that happened in the world
     * and outlives any plan it was filed under.
     */
    void delete(Envelope envelope);

    Optional<Envelope> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * One household's envelopes for one month.
     *
     * <p>{@code period} is the first day of the labelled month, and matching is on equality rather
     * than a range: the period is a label, not a boundary (ADR-0031).
     */
    @Query(
            value =
                    """
                    select e from Envelope e
                    where e.householdId = :householdId
                      and e.period = :period
                      and (:includeArchived = true or e.archived = false)
                    order by lower(e.name) asc, e.id asc
                    """,
            countQuery =
                    """
                    select count(e) from Envelope e
                    where e.householdId = :householdId
                      and e.period = :period
                      and (:includeArchived = true or e.archived = false)
                    """)
    Page<Envelope> findForPeriod(
            @Param("householdId") UUID householdId,
            @Param("period") LocalDate period,
            @Param("includeArchived") boolean includeArchived,
            Pageable pageable);

    /** The months this household has envelopes for, newest first — for a period picker. */
    @Query(
            """
            select distinct e.period from Envelope e
            where e.householdId = :householdId
            order by e.period desc
            """)
    List<LocalDate> periodsWithEnvelopes(@Param("householdId") UUID householdId);

    long countByHouseholdId(UUID householdId);

    /**
     * What one month's envelopes set aside in all.
     *
     * <p>A sum in the database rather than a page of views summed in Java: the Bills &amp; Income
     * page needs this one figure and nothing else about the envelopes, and loading two hundred rows
     * with their spend to add up one column would be the wrong shape for a headline.
     *
     * <p><b>Archived envelopes are excluded</b>, matching {@code EnvelopeService.list}: a retired
     * envelope is not part of the plan, and counting one would overstate what is set aside and
     * understate what is left — by a figure that would look perfectly reasonable.
     *
     * @return null when the household has no envelopes that month. {@code coalesce} is deliberately
     *     not used: "no envelopes" and "envelopes totalling nothing" are different facts, and the
     *     caller decides what to do about it
     */
    @Query(
            """
            select sum(e.amount) from Envelope e
            where e.householdId = :householdId
              and e.period = :period
              and e.archived = false
            """)
    BigDecimal allocatedForPeriod(
            @Param("householdId") UUID householdId, @Param("period") LocalDate period);

    /**
     * How much has been logged against each of these envelopes, and how many entries that is.
     *
     * <p><b>Batched by design.</b> {@code remaining} is the headline on every envelope surface, so
     * the list endpoint asks about every row at once; asking per envelope would be an N+1 on the
     * hot path of the screen this feature exists for.
     *
     * <p>The count is returned alongside the sum because they are different facts: an envelope with
     * one entry of zero cannot exist ({@code ck_envelope_entries_amount_positive}), but an envelope
     * with no entries and one whose entries sum to nothing would otherwise be indistinguishable in
     * the response.
     */
    @Query(
            """
            select new com.budgetowl.envelope.persistence.EnvelopeSpent(
                       n.envelopeId, sum(n.amount), count(n))
            from EnvelopeEntry n
            where n.householdId = :householdId
              and n.envelopeId in :envelopeIds
            group by n.envelopeId
            """)
    List<EnvelopeSpent> spentByEnvelope(
            @Param("householdId") UUID householdId,
            @Param("envelopeIds") Collection<UUID> envelopeIds);
}
