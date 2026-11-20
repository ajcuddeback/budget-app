package com.budgetowl.category.domain;

import com.budgetowl.common.HouseholdOwned;
import com.budgetowl.household.domain.Household;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A category: how a household classifies what its money was for.
 *
 * <p><b>The hierarchy is two levels and this class cannot see far enough to enforce that.</b> A
 * child's parent must itself be top-level and must share the child's kind — both are facts about
 * another row, so they are checked by {@code CategoryService} (for the message) and by {@code
 * trg_categories_two_levels} in {@code V11} (for the guarantee). The entity holds the parent as a
 * plain {@code UUID} rather than a {@code @ManyToOne Category} for the same reason {@link
 * com.budgetowl.account.domain.Account} holds a plain {@code householdId}: the association would
 * invite lazy traversal from a row the caller never proved they could reach, and the scoping
 * predicate has to stay a single condition on the row itself.
 *
 * <p>This class validates what it <em>can</em> see — the name's bounds and that a category is not
 * its own parent — rather than trusting the edge, so the same rules hold for an import path that
 * never passes through a controller.
 */
@Entity
@Table(name = "categories")
public class Category implements HouseholdOwned {

    /** 1–60 characters after trimming, matching {@code ck_categories_name_length}. */
    public static final int NAME_MAX_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Fixed at creation. Re-kinding a category in use would silently reinterpret every transaction
     * already filed under it — a "Salary" that becomes an expense turns a year of income into
     * spending — so the service refuses it rather than offering it as an edit.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private CategoryKind kind;

    /** {@code null} for a top-level category. */
    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Two people renaming one category is a normal household; the loser is told, not overwritten. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Category() {
        // Hibernate.
    }

    private Category(UUID householdId, String name, CategoryKind kind, UUID parentId) {
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.parentId = parentId;
        rename(name);
    }

    /**
     * A top-level category.
     *
     * @param household the owning household, required — a {@code UUID} parameter here would accept
     *     an id a caller supplied, which is never evidence of ownership
     */
    public static Category topLevel(Household household, String name, CategoryKind kind) {
        return new Category(Objects.requireNonNull(household, "household").id(), name, kind, null);
    }

    /**
     * A child of {@code parent}, taking the parent's household and kind rather than accepting its
     * own: the two must agree, and the only way they cannot disagree is for there to be one source.
     *
     * @throws IllegalArgumentException if {@code parent} is itself a child — the caller is expected
     *     to have turned that into a {@code 400} with a field name first
     */
    public static Category childOf(Category parent, String name) {
        Objects.requireNonNull(parent, "parent");
        if (!parent.isTopLevel()) {
            throw new IllegalArgumentException("the category hierarchy is limited to two levels");
        }
        return new Category(parent.householdId(), name, parent.kind(), parent.id());
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public String name() {
        return name;
    }

    public CategoryKind kind() {
        return kind;
    }

    /** Absent for a top-level category. */
    public Optional<UUID> parentId() {
        return Optional.ofNullable(parentId);
    }

    public boolean isTopLevel() {
        return parentId == null;
    }

    public boolean isArchived() {
        return archived;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    /** 1–60 characters once trimmed; stored trimmed, so the raw and trimmed bounds agree. */
    public void rename(String newName) {
        String trimmed = Objects.requireNonNull(newName, "name").strip();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a category name must be 1–" + NAME_MAX_LENGTH + " characters");
        }
        this.name = trimmed;
    }

    /**
     * Moves this category under {@code parent}, or to the top level when {@code parent} is null.
     *
     * <p>Refuses the two shapes that would make a third level: a parent that is itself a child, and
     * a move of a category that has children of its own. The second needs a count this entity
     * cannot take, so the service supplies it.
     */
    public void moveUnder(Category parent, boolean hasChildrenOfItsOwn) {
        if (parent == null) {
            this.parentId = null;
            return;
        }
        if (!parent.isTopLevel()) {
            throw new IllegalArgumentException("the category hierarchy is limited to two levels");
        }
        if (hasChildrenOfItsOwn) {
            throw new IllegalArgumentException(
                    "a category with children cannot itself become a child");
        }
        if (parent.id().equals(id)) {
            throw new IllegalArgumentException("a category cannot be its own parent");
        }
        if (!parent.householdId().equals(householdId)) {
            throw new IllegalArgumentException("the parent belongs to a different household");
        }
        if (parent.kind() != kind) {
            throw new IllegalArgumentException("the parent's kind does not match");
        }
        this.parentId = parent.id();
    }

    /** Keeps history readable, removes the category from pickers. Idempotent. */
    public void archive() {
        this.archived = true;
    }

    public void unarchive() {
        this.archived = false;
    }
}
