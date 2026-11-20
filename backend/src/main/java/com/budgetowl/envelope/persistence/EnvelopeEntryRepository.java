package com.budgetowl.envelope.persistence;

import com.budgetowl.envelope.domain.EnvelopeEntry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Envelope entries.
 *
 * <p>Every method names a household, and the log also names its envelope: an entry id from a path
 * is a lookup key, and an entry belonging to another envelope of the same household is still the
 * wrong entry for the request that asked for it.
 */
public interface EnvelopeEntryRepository extends Repository<EnvelopeEntry, UUID> {

    EnvelopeEntry save(EnvelopeEntry entry);

    EnvelopeEntry saveAndFlush(EnvelopeEntry entry);

    void delete(EnvelopeEntry entry);

    /**
     * One entry, if it belongs to this envelope and this household.
     *
     * <p>Both are checked rather than just the household: an entry of the right household but the
     * wrong envelope would otherwise be editable through a URL naming an envelope it is not in, and
     * the response would then show it moving between remaining figures.
     */
    @Query(
            """
            select n from EnvelopeEntry n
            where n.id = :id
              and n.envelopeId = :envelopeId
              and n.householdId = :householdId
            """)
    Optional<EnvelopeEntry> findInEnvelope(
            @Param("id") UUID id,
            @Param("envelopeId") UUID envelopeId,
            @Param("householdId") UUID householdId);

    /** The log: one envelope's entries, newest first, with a stable tiebreak on id. */
    @Query(
            value =
                    """
                    select n from EnvelopeEntry n
                    where n.householdId = :householdId
                      and n.envelopeId = :envelopeId
                    order by n.date desc, n.id desc
                    """,
            countQuery =
                    """
                    select count(n) from EnvelopeEntry n
                    where n.householdId = :householdId
                      and n.envelopeId = :envelopeId
                    """)
    Page<EnvelopeEntry> findLog(
            @Param("householdId") UUID householdId,
            @Param("envelopeId") UUID envelopeId,
            Pageable pageable);

    /**
     * Whether this transaction is already attached to an envelope.
     *
     * <p>{@code ux_envelope_entries_transaction_id} is the control — one transaction, at most one
     * entry, ever (ADR-0031). This is the good message in front of it, and the reason the service
     * can say <em>which</em> envelope has it rather than only that something does.
     */
    @Query(
            """
            select n from EnvelopeEntry n
            where n.householdId = :householdId
              and n.transactionId = :transactionId
            """)
    Optional<EnvelopeEntry> findByTransaction(
            @Param("householdId") UUID householdId, @Param("transactionId") UUID transactionId);
}
