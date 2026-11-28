package com.budgetowl.category.web;

import com.budgetowl.category.domain.Category;
import com.budgetowl.common.Change;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request bodies for the category endpoints. Entities are never request bodies
 * (docs/guides/api-style.md).
 *
 * <p><b>No household id anywhere below.</b> It comes from the authenticated caller's verified
 * membership (ADR-0017, non-negotiable #2). A field for it would be a field an attacker could set.
 *
 * <p>Bean Validation carries the same 60-character bound as {@link Category} and {@code
 * ck_categories_name_length}. The edge produces the message that names the field; the entity holds
 * for an import path that never passes through a controller; the constraint holds for anything that
 * reaches a {@code Statement}.
 *
 * <p>{@code toString} is redacted on both, for the reason {@code AccountRequests} gives: Spring MVC
 * logs the deserialized body at {@code DEBUG}, and a category name is whatever the user typed.
 */
public final class CategoryRequests {

    /** Rejects a name that is only whitespace, which {@code @Size} alone permits. */
    private static final String NOT_BLANK = ".*\\S.*";

    private CategoryRequests() {}

    /**
     * @param kind required at the top level; for a child it must match the parent's or be omitted,
     *     and a contradiction is a {@code 400} rather than a silent preference
     * @param parentId omitted or null for a top-level category
     */
    public record CreateCategoryRequest(
            @NotBlank @Size(max = Category.NAME_MAX_LENGTH) String name,
            @Pattern(regexp = "INCOME|EXPENSE", message = "must be INCOME or EXPENSE") String kind,
            UUID parentId) {

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "CreateCategoryRequest[child=" + (parentId != null) + "]";
        }
    }

    /**
     * A partial update.
     *
     * <p>A class with setters rather than a record, because an absent {@code parentId} and an
     * explicit {@code null} mean different things and only a setter is told which happened — the
     * same mechanism {@code UpdateAccountRequest} uses for {@code lastFour}, resolved through
     * {@link Change}.
     *
     * <p>There is no {@code kind}: a category's kind is fixed at creation, so the contract cannot
     * express the change rather than accepting it and refusing.
     */
    public static final class UpdateCategoryRequest {

        @Size(max = Category.NAME_MAX_LENGTH)
        @Pattern(regexp = NOT_BLANK, flags = Pattern.Flag.DOTALL, message = "must not be blank")
        private String name;

        private UUID parentId;

        private boolean parentMentioned;

        /**
         * Required, and not an inconvenience: optimistic locking only works if the caller says
         * which version it read.
         */
        @NotNull private Long version;

        public void setName(String name) {
            this.name = name;
        }

        /** Called by Jackson only when the property is present — that is the whole mechanism. */
        public void setParentId(UUID parentId) {
            this.parentId = parentId;
            this.parentMentioned = true;
        }

        public void setVersion(Long version) {
            this.version = version;
        }

        public String name() {
            return name;
        }

        public Long version() {
            return version;
        }

        /**
         * The three-way answer, resolved here because this is the only object holding the presence
         * bit. Exposing the bit instead would invite a call site that forgets it.
         */
        public Change<UUID> parentChange() {
            return Change.of(parentId, parentMentioned);
        }

        /** Redacted — see the class Javadoc. */
        @Override
        public String toString() {
            return "UpdateCategoryRequest[version=" + version + "]";
        }
    }
}
