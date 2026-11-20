package com.budgetowl.account.persistence;

import com.budgetowl.account.domain.Account;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Accounts — the first repository over genuinely financial data, so the first one the ownership
 * rule is load-bearing for.
 *
 * <p><b>Every method takes a {@code householdId}</b>, or takes the account itself. There is no
 * {@code findById}, no {@code findAll} and no {@code existsById}: an id from a path or a body is a
 * lookup key and never evidence of ownership (ADR-0008 as amended by ADR-0017, non-negotiable #2).
 * An account belonging to a household the caller is not a verified member of must be
 * indistinguishable from one that never existed, and the only way to keep that true at every call
 * site is for the unscoped call to be unwritable. {@code
 * ArchitectureTest.household_owned_data_is_unreachable_without_its_household} fails the build if
 * one is added.
 *
 * <p>Extends the bare {@link Repository} marker rather than {@code CrudRepository}, which would
 * bring an unscoped {@code findById} along with it whether anyone called it or not.
 *
 * <p>Sorting is fixed in the queries, never taken from a parameter: an {@code ORDER BY} assembled
 * from caller input is SQL injection with extra steps (docs/guides/database-style.md). A sort field
 * the client may choose arrives later as an allowlist enum, not as a string.
 */
public interface AccountRepository extends Repository<Account, UUID> {

    /** Insert and update. The entity carries its own {@code household_id}, set at construction. */
    Account save(Account account);

    /**
     * Save, and make the database agree before returning.
     *
     * <p>Needed because {@code @Version} is incremented by Hibernate at <b>flush</b>, not at the
     * moment a field is set — so a write endpoint that built its response straight after mutating
     * the entity reported the version the caller already had. The next {@code PATCH} would then
     * carry a stale version and be refused with {@code 409}, which is the optimistic lock firing
     * against a conflict that does not exist. An integration test caught exactly that.
     *
     * <p>Scoped by taking the entity: the caller can only have obtained it from {@link
     * #findByIdAndHouseholdId}, which is what {@code
     * ArchitectureTest.household_owned_data_is_unreachable_without_its_household} accepts in place
     * of a {@code householdId} parameter.
     */
    Account saveAndFlush(Account account);

    /**
     * Hard-deletes an account.
     *
     * <p>Only legal while the account has no transactions — the service enforces that and answers
     * {@code 409 account-not-empty} otherwise, because deleting would orphan or silently destroy
     * financial history. Scoping is implicit: the caller can only have obtained this entity from
     * {@link #findByIdAndHouseholdId}.
     */
    void delete(Account account);

    /**
     * One account, if it belongs to this household.
     *
     * <p>An empty {@code Optional} is a {@code 404} regardless of whether the row exists elsewhere.
     * A {@code 403} would confirm it does.
     */
    Optional<Account> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * The default listing: this household's unarchived accounts, ordered by name
     * case-insensitively.
     *
     * <p>The tiebreak on {@code id} is not decoration. Two accounts may share a name — real
     * households have two pots called "Savings" — so without a unique final sort key, paging over
     * them can repeat or skip a row between requests. {@code
     * ix_accounts_household_id_archived_name} carries {@code (household_id, archived, lower(name),
     * id)} so this order is read straight out of the index.
     *
     * <p>Pages in the query rather than in memory. A financial collection is not allowed to
     * materialise everything and slice it (see {@code PageResponse.of}).
     */
    @Query(
            value =
                    """
                    select a from Account a
                    where a.householdId = :householdId
                      and a.archived = false
                    order by lower(a.name) asc, a.id asc
                    """,
            countQuery =
                    """
                    select count(a) from Account a
                    where a.householdId = :householdId
                      and a.archived = false
                    """)
    Page<Account> findUnarchivedByHouseholdId(
            @Param("householdId") UUID householdId, Pageable pageable);

    /**
     * Archived and unarchived together, for {@code ?includeArchived=true}. The caller groups them
     * by the {@code archived} flag on each row.
     */
    @Query(
            value =
                    """
                    select a from Account a
                    where a.householdId = :householdId
                    order by lower(a.name) asc, a.id asc
                    """,
            countQuery =
                    """
                    select count(a) from Account a
                    where a.householdId = :householdId
                    """)
    Page<Account> findAllByHouseholdId(@Param("householdId") UUID householdId, Pageable pageable);

    /**
     * How many accounts the household has, archived included.
     *
     * <p>For the empty state, which is first-run and the common case rather than an error — and
     * cheaper than asking for a page in order to read its total.
     */
    long countByHouseholdId(UUID householdId);
}
