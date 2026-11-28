package com.budgetowl.category.persistence;

import com.budgetowl.category.domain.Category;
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
 * Categories.
 *
 * <p><b>Every method takes a {@code householdId}</b>, or takes the entity itself — see {@link
 * com.budgetowl.account.persistence.AccountRepository} for the full reasoning. {@code
 * ArchitectureTest.household_owned_data_is_unreachable_without_its_household} fails the build if an
 * unscoped lookup is added, and checks the query text rather than only the signature, so a
 * {@code @Query} that takes the parameter and forgets to use it fails too.
 *
 * <p>Sorting is fixed in the queries, never taken from a parameter.
 */
public interface CategoryRepository extends Repository<Category, UUID> {

    Category save(Category category);

    /** See {@code AccountRepository.saveAndFlush} — {@code @Version} is bumped at flush. */
    Category saveAndFlush(Category category);

    /**
     * Hard-deletes a category.
     *
     * <p>Its children go with it ({@code fk_categories_parent ON DELETE CASCADE}) and the
     * transactions underneath do <b>not</b>: {@code fk_transactions_category} is {@code ON DELETE
     * SET NULL (category_id)}, so they become "Needs a category" rather than disappearing. That
     * split is the whole reason deleting a category is allowed at all.
     */
    void delete(Category category);

    /** An empty {@code Optional} is a {@code 404}, whether or not the row exists elsewhere. */
    Optional<Category> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * The household's categories as a flat, paginated list; the client assembles the tree, since
     * every row carries its {@code parentId}.
     *
     * <p>Flat rather than nested on purpose. A nested response cannot be paginated coherently — a
     * page boundary could fall between a parent and its children — and the envelope is required
     * from day one (docs/guides/api-style.md). The service caps a household at {@link
     * #countByHouseholdId} 200 categories, which is the API's maximum page size, so one request
     * always returns the whole tree and a picker never pages even though it could.
     *
     * <p>Ordered by kind, then name case-insensitively, with a stable tiebreak on id so paging
     * cannot repeat or skip a row. {@code ix_categories_household_kind_name} carries {@code
     * (household_id, kind, lower(name), id)}, so this order is read out of the index.
     */
    @Query(
            value =
                    """
                    select c from Category c
                    where c.householdId = :householdId
                      and (:includeArchived = true or c.archived = false)
                    order by c.kind asc, lower(c.name) asc, c.id asc
                    """,
            countQuery =
                    """
                    select count(c) from Category c
                    where c.householdId = :householdId
                      and (:includeArchived = true or c.archived = false)
                    """)
    Page<Category> findAllByHouseholdId(
            @Param("householdId") UUID householdId,
            @Param("includeArchived") boolean includeArchived,
            Pageable pageable);

    /**
     * This category's children.
     *
     * <p>Takes the household as well as the parent even though {@code parentId} alone would
     * identify them: the rule is that no query reaches household-owned data without naming its
     * household, and "the parent was already checked" is exactly the kind of reasoning that stops
     * being true when a second call site appears.
     */
    @Query(
            """
            select c from Category c
            where c.householdId = :householdId
              and c.parentId = :parentId
            order by lower(c.name) asc, c.id asc
            """)
    List<Category> findChildren(
            @Param("householdId") UUID householdId, @Param("parentId") UUID parentId);

    /**
     * Whether this category has any child.
     *
     * <p>Needed before a move: a category with children may not itself become a child, because that
     * would push its children to a third level. Cheaper than loading them.
     */
    @Query(
            """
            select count(c) > 0 from Category c
            where c.householdId = :householdId
              and c.parentId = :parentId
            """)
    boolean hasChildren(@Param("householdId") UUID householdId, @Param("parentId") UUID parentId);

    /**
     * The named categories, as far as they belong to this household. Batched for the transaction
     * ledger, which needs each row's category name and would otherwise ask once per row.
     */
    List<Category> findAllByHouseholdIdAndIdIn(UUID householdId, Collection<UUID> ids);

    /** For the per-household cap, and for the empty state on first run. */
    long countByHouseholdId(UUID householdId);
}
