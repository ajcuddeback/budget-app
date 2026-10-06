package com.budgetowl.household.persistence;

import com.budgetowl.household.domain.Household;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * The household. Singular — an instance holds exactly one (ADR-0026), and {@code
 * uq_households_singleton} enforces that in the schema rather than in hope.
 *
 * <p>There is no {@code findAll} and no way to create a second one that the database will accept.
 */
public interface HouseholdRepository extends Repository<Household, UUID> {

    /**
     * Saves the household.
     *
     * <p>A brand-new household must be given its {@code OWNER} in the same transaction. The
     * deferred constraint trigger {@code ck_households_at_least_one_owner} re-checks at {@code
     * COMMIT} and aborts a household that has none, so "insert the household now, add the owner in
     * a second request" is not a thing that can happen.
     */
    Household save(Household household);

    /**
     * The one household, if setup has run. Every {@code /api/households/current} endpoint resolves
     * its scope from the caller's verified membership, not from here — this is for the setup and
     * instance-status paths, where there is no membership yet.
     */
    @Query("select h from Household h")
    Optional<Household> findCurrent();

    Optional<Household> findById(UUID id);

    long count();
}
