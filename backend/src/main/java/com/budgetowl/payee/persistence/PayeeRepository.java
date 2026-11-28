package com.budgetowl.payee.persistence;

import com.budgetowl.payee.domain.Payee;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Payees.
 *
 * <p>Every method takes a {@code householdId} or the entity itself — see {@code AccountRepository}
 * for the reasoning, and {@code ArchitectureTest} for what fails the build if one does not.
 */
public interface PayeeRepository extends Repository<Payee, UUID> {

    Payee save(Payee payee);

    Payee saveAndFlush(Payee payee);

    Optional<Payee> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * Looks a payee up by name, case-insensitively — the lookup half of get-or-create.
     *
     * <p>{@code lower(name)} rather than an {@code ignoreCase} derived query so that it matches
     * {@code ux_payees_household_name}, which is on {@code (household_id, lower(name))}: a lookup
     * whose expression differs from the index's cannot use it, and worse, could disagree with it
     * about what counts as a duplicate.
     */
    @Query(
            """
            select p from Payee p
            where p.householdId = :householdId
              and lower(p.name) = lower(:name)
            """)
    Optional<Payee> findByName(@Param("householdId") UUID householdId, @Param("name") String name);

    /**
     * Names beginning with {@code prefix}, for the transaction form's autocomplete.
     *
     * <p>A prefix match rather than {@code contains}: it can use the index, and it is what a person
     * typing a name expects. The prefix is a bound parameter — never concatenated — and the caller
     * escapes {@code %} and {@code _} before passing it, since those are wildcards to {@code like}
     * and a payee called "100%" would otherwise match everything.
     */
    @Query(
            """
            select p from Payee p
            where p.householdId = :householdId
              and lower(p.name) like lower(:prefix) escape '\\'
            order by lower(p.name) asc, p.id asc
            """)
    List<Payee> findByNamePrefix(
            @Param("householdId") UUID householdId,
            @Param("prefix") String prefix,
            Pageable pageable);

    /**
     * The named payees, as far as they belong to this household. Batched for the ledger, which
     * needs each row's payee name.
     */
    List<Payee> findAllByHouseholdIdAndIdIn(UUID householdId, Collection<UUID> ids);

    long countByHouseholdId(UUID householdId);
}
