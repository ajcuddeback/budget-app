package com.budgetowl.category.service;

import com.budgetowl.category.domain.Category;
import com.budgetowl.category.domain.CategoryKind;
import com.budgetowl.category.persistence.CategoryRepository;
import com.budgetowl.common.Change;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.ForbiddenException;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * Categories: how a household classifies what its money was for.
 *
 * <p>Scoping and roles work exactly as in {@link com.budgetowl.account.service.AccountService} —
 * every call resolves the caller's membership first, every repository call is given that
 * household's id, a {@code VIEWER} never writes, and an id from a path is a lookup key rather than
 * evidence of ownership, so another household's category is a {@code 404} and never a {@code 403}.
 *
 * <p>Three rules are specific to this service:
 *
 * <ul>
 *   <li><b>Two levels, no deeper.</b> Checked here for the message and by {@code
 *       trg_categories_two_levels} for the guarantee. Both directions matter: a parent that is
 *       itself a child, and a category with children being moved under someone.
 *   <li><b>A kind is fixed at creation.</b> Re-kinding a category in use would reinterpret every
 *       transaction filed under it, so {@link CategoryChanges} cannot express it and a request that
 *       tries is a {@code 409} rather than a silent no-op.
 *   <li><b>Deleting a category never deletes money.</b> Its children go with it, and its
 *       transactions are nulled to "Needs a category" by {@code fk_transactions_category}. That is
 *       a database behaviour rather than a loop here, so it holds for an import path too.
 * </ul>
 *
 * <p><b>Nothing here logs a name.</b> A category name is whatever the user typed; audit lines name
 * the category by id and the actor by user id.
 */
@Service
public class CategoryService {

    /**
     * The per-household cap, equal to the API's maximum page size on purpose: the whole tree then
     * always fits in one response, so a picker never has to page even though the endpoint is
     * paginated as every collection must be (docs/guides/api-style.md).
     */
    public static final int MAXIMUM_PER_HOUSEHOLD = PageResponse.MAXIMUM_SIZE;

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    private final CategoryRepository categories;
    private final MembershipService memberships;

    public CategoryService(CategoryRepository categories, MembershipService memberships) {
        this.categories = categories;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public CategoryPage list(UUID actorId, boolean includeArchived, int page, int size) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        UUID householdId = membership.household().id();

        Page<Category> found =
                categories.findAllByHouseholdId(householdId, includeArchived, pageable(page, size));

        return new CategoryPage(
                views(householdId, found.getContent()),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public CategoryView get(UUID actorId, UUID categoryId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        UUID householdId = membership.household().id();
        return view(householdId, require(categoryId, householdId));
    }

    /**
     * @throws ForbiddenException for a {@code VIEWER}
     * @throws InvalidRequestException for an unrecognised kind, or a kind that contradicts the
     *     named parent's
     * @throws ConflictException {@code category-name-taken}, {@code category-depth-exceeded} or
     *     {@code category-limit-reached}
     */
    @Transactional
    public CategoryView create(UUID actorId, NewCategory requested) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        Household household = membership.household();
        UUID householdId = household.id();

        if (categories.countByHouseholdId(householdId) >= MAXIMUM_PER_HOUSEHOLD) {
            throw new ConflictException(
                    ErrorCode.CATEGORY_LIMIT_REACHED,
                    "a household is limited to " + MAXIMUM_PER_HOUSEHOLD + " categories");
        }

        Category created;
        if (requested.parentId() == null) {
            if (requested.kind() == null) {
                throw new InvalidRequestException(
                        ErrorCode.VALIDATION_FAILED,
                        "a top-level category needs a kind",
                        Map.of("field", "kind"));
            }
            created = Category.topLevel(household, requested.name(), kind(requested.kind()));
        } else {
            Category parent = require(requested.parentId(), householdId);
            if (!parent.isTopLevel()) {
                throw new ConflictException(
                        ErrorCode.CATEGORY_DEPTH_EXCEEDED,
                        "the chosen parent is already a child category");
            }
            // Stated and contradictory is a 400, not a silent preference for the parent's kind.
            if (requested.kind() != null && kind(requested.kind()) != parent.kind()) {
                throw new InvalidRequestException(
                        ErrorCode.VALIDATION_FAILED,
                        "a child category takes its parent's kind",
                        Map.of("field", "kind"));
            }
            created = Category.childOf(parent, requested.name());
        }

        Category saved = saveDistinctly(created);
        log.info(
                "category created householdId={} categoryId={} byUserId={} kind={} child={}",
                householdId,
                saved.id(),
                actorId,
                saved.kind(),
                !saved.isTopLevel());
        return view(householdId, saved);
    }

    /**
     * @throws ConflictException {@code stale-version}, {@code category-name-taken} or {@code
     *     category-depth-exceeded}
     */
    @Transactional
    public CategoryView update(UUID actorId, UUID categoryId, CategoryChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Category category = require(categoryId, householdId);

        requireCurrentVersion(category, changes.version());

        if (changes.name() != null) {
            category.rename(changes.name());
        }

        switch (changes.parent()) {
            case Change.Unchanged<UUID> ignored -> {
                // Not mentioned, so the category keeps the parent it has.
            }
            case Change.Cleared<UUID> ignored -> category.moveUnder(null, false);
            case Change.SetTo<UUID> move -> moveUnder(householdId, category, move.value());
        }

        Category saved = saveDistinctly(category);
        log.info(
                "category updated householdId={} categoryId={} byUserId={}",
                householdId,
                categoryId,
                actorId);
        return view(householdId, saved);
    }

    @Transactional
    public CategoryView archive(UUID actorId, UUID categoryId) {
        return setArchived(actorId, categoryId, true);
    }

    @Transactional
    public CategoryView unarchive(UUID actorId, UUID categoryId) {
        return setArchived(actorId, categoryId, false);
    }

    /**
     * Deletes a category, its children with it, and <b>no transactions at all</b>.
     *
     * <p>Transactions filed under it become "Needs a category" — {@code fk_transactions_category}
     * is {@code ON DELETE SET NULL (category_id)}, so the re-assignment happens in the database
     * rather than in a loop here. That matters for more than tidiness: a loop would have to be
     * repeated by every other path that deletes a category, and the one that forgot would delete
     * somebody's financial history.
     *
     * <p>Deletion is offered alongside archiving rather than instead of it, because the two answer
     * different questions: archiving means "I no longer use this and want my history to still read
     * correctly", deleting means "this was a mistake".
     */
    @Transactional
    public void delete(UUID actorId, UUID categoryId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Category category = require(categoryId, householdId);

        categories.delete(category);
        log.info(
                "category deleted householdId={} categoryId={} byUserId={}",
                householdId,
                categoryId,
                actorId);
    }

    /**
     * Gives a brand-new household a starting set, so that the first transaction it records has
     * somewhere to go.
     *
     * <p>Called from household creation rather than written into a migration, because this is where
     * the household is known — and because a migration cannot know about a household that does not
     * exist yet. {@code V11} seeds the same list for an instance that was already running when
     * categories shipped; the two have to agree, and {@code DefaultCategoriesTest} is what keeps
     * them agreeing.
     *
     * <p>Not transactional on its own: it runs inside the caller's transaction, so a household is
     * never created with half a category list.
     */
    public void seedDefaultsFor(Household household) {
        List<Category> seeded = new ArrayList<>();
        for (DefaultCategories.Group group : DefaultCategories.ALL) {
            Category parent =
                    categories.save(Category.topLevel(household, group.name(), group.kind()));
            seeded.add(parent);
            for (String child : group.children()) {
                seeded.add(categories.save(Category.childOf(parent, child)));
            }
        }
        log.info(
                "default categories seeded householdId={} count={}", household.id(), seeded.size());
    }

    // ------------------------------------------------------------------------------- internals

    private CategoryView setArchived(UUID actorId, UUID categoryId, boolean archived) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Category category = require(categoryId, householdId);

        if (archived) {
            category.archive();
        } else {
            category.unarchive();
        }

        Category saved = categories.saveAndFlush(category);
        log.info(
                "category {} householdId={} categoryId={} byUserId={}",
                archived ? "archived" : "unarchived",
                householdId,
                categoryId,
                actorId);
        return view(householdId, saved);
    }

    /**
     * Re-parents, or refuses with the reason rather than letting the entity's {@code
     * IllegalArgumentException} become a 500.
     */
    private void moveUnder(UUID householdId, Category category, UUID parentId) {
        if (parentId.equals(category.id())) {
            throw new ConflictException(
                    ErrorCode.CATEGORY_DEPTH_EXCEEDED, "a category cannot be its own parent");
        }
        Category parent = require(parentId, householdId);
        if (!parent.isTopLevel()) {
            throw new ConflictException(
                    ErrorCode.CATEGORY_DEPTH_EXCEEDED,
                    "the chosen parent is already a child category");
        }
        if (parent.kind() != category.kind()) {
            // Not CATEGORY_KIND_IMMUTABLE: nobody asked to change a kind. The move is what is
            // being refused, because it would put an expense under income.
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "the parent's kind does not match this category's",
                    Map.of("field", "parentId"));
        }
        if (categories.hasChildren(householdId, category.id())) {
            throw new ConflictException(
                    ErrorCode.CATEGORY_DEPTH_EXCEEDED,
                    "this category has children, so it cannot itself become a child");
        }
        category.moveUnder(parent, false);
    }

    /**
     * Saves, turning the unique-index violation into {@code 409 category-name-taken}.
     *
     * <p>The check is the index, not a prior {@code SELECT}: two members adding "Groceries" at the
     * same moment both pass a look-before-you-leap check and one of them still has to be told.
     * Flushed here so the violation surfaces inside this method rather than at commit, where it
     * would already be past the point of being reportable as this field's problem.
     */
    private Category saveDistinctly(Category category) {
        try {
            return categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException violation) {
            if (isNameCollision(violation)) {
                throw new ConflictException(
                        ErrorCode.CATEGORY_NAME_TAKEN,
                        "a category with this name already exists at this level");
            }
            throw violation;
        }
    }

    /**
     * Whether this integrity violation is the name index and not something else.
     *
     * <p>Matched on the constraint name, which is ours and is stable, rather than on the driver's
     * message text. Anything else is re-thrown rather than reported as a name collision: telling a
     * user "that name is taken" because an unrelated constraint fired would send them round a loop
     * renaming a category that was never the problem.
     */
    private static boolean isNameCollision(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("ux_categories_household_parent_name")) {
                return true;
            }
        }
        return false;
    }

    private Category require(UUID categoryId, UUID householdId) {
        return categories
                .findByIdAndHouseholdId(categoryId, householdId)
                .orElseThrow(() -> new NotFoundException("no such category"));
    }

    /** See {@code AccountService.requireCurrentVersion} — the clear message, not the control. */
    private static void requireCurrentVersion(Category category, long expected) {
        if (category.version() != expected) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the category changed since it was read");
        }
    }

    /**
     * Builds the views, resolving each child's parent name from the rows already in hand where
     * possible and asking the repository only for parents a filtered page did not include.
     *
     * <p>One extra query at most, never one per row: an archived parent with unarchived children is
     * ordinary, and the default listing excludes the parent, so the names have to come from
     * somewhere.
     */
    private List<CategoryView> views(UUID householdId, List<Category> found) {
        Map<UUID, String> namesById = new HashMap<>();
        for (Category category : found) {
            namesById.put(category.id(), category.name());
        }

        List<CategoryView> views = new ArrayList<>(found.size());
        for (Category category : found) {
            UUID parentId = category.parentId().orElse(null);
            String parentName =
                    parentId == null
                            ? null
                            : namesById.computeIfAbsent(
                                    parentId,
                                    id ->
                                            categories
                                                    .findByIdAndHouseholdId(id, householdId)
                                                    .map(Category::name)
                                                    .orElse(null));
            views.add(view(category, parentName));
        }
        return views;
    }

    private CategoryView view(UUID householdId, Category category) {
        String parentName =
                category.parentId()
                        .flatMap(id -> categories.findByIdAndHouseholdId(id, householdId))
                        .map(Category::name)
                        .orElse(null);
        return view(category, parentName);
    }

    private static CategoryView view(Category category, String parentName) {
        return new CategoryView(
                category.id(),
                category.name(),
                category.kind(),
                category.parentId().orElse(null),
                parentName,
                category.isArchived(),
                category.createdAt(),
                category.updatedAt(),
                category.version());
    }

    private static Pageable pageable(int page, int size) {
        int clamped =
                Math.clamp(
                        size <= 0 ? PageResponse.DEFAULT_SIZE : size, 1, PageResponse.MAXIMUM_SIZE);
        return PageRequest.of(Math.max(page, 0), clamped);
    }

    private static CategoryKind kind(String name) {
        try {
            return CategoryKind.valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "unknown category kind", Map.of("field", "kind"));
        }
    }
}
