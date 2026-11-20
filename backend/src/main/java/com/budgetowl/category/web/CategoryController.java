package com.budgetowl.category.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.category.service.CategoryChanges;
import com.budgetowl.category.service.CategoryPage;
import com.budgetowl.category.service.CategoryService;
import com.budgetowl.category.service.CategoryView;
import com.budgetowl.category.service.NewCategory;
import com.budgetowl.category.web.CategoryRequests.CreateCategoryRequest;
import com.budgetowl.category.web.CategoryRequests.UpdateCategoryRequest;
import com.budgetowl.category.web.CategoryResponses.CategoryResponse;
import com.budgetowl.common.web.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's categories.
 *
 * <p>No household in any of these paths: an instance holds exactly one (ADR-0026) and it is
 * resolved from the caller's verified membership inside {@link CategoryService}. The {@code id} in
 * a path is a lookup key and nothing else — a category belonging to another household returns
 * {@code 404}, never {@code 403}, because a {@code 403} would confirm the row exists.
 *
 * <p>No role is checked here and no error response is built here; both live in the service and the
 * {@code @RestControllerAdvice} respectively.
 *
 * <p>Archive and un-archive are sub-resources rather than a field on {@code PATCH}, matching the
 * accounts slice: retiring a category is a different act from correcting its name.
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categories;

    public CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    /**
     * The whole tree, flat and paginated.
     *
     * <p>Paginated because every collection is (docs/guides/api-style.md), and in practice always
     * one page: a household is capped at {@link CategoryService#MAXIMUM_PER_HOUSEHOLD} categories,
     * which equals the maximum page size. A client that wants the tree in one call asks for
     * {@code size=200} and is guaranteed to get it.
     *
     * @param includeArchived default false: archived categories disappear from pickers, but a
     *     client that asks for them can show history correctly
     */
    @GetMapping
    PageResponse<CategoryResponse> list(
            TransportAuthentication caller,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        CategoryPage found = categories.list(caller.userId(), includeArchived, page, size);
        return new PageResponse<>(
                found.categories().stream().map(CategoryController::response).toList(),
                found.page(),
                found.size(),
                found.totalElements(),
                found.totalPages());
    }

    /** {@code OWNER} or {@code MEMBER}, enforced in the service. */
    @PostMapping
    ResponseEntity<CategoryResponse> create(
            TransportAuthentication caller, @Valid @RequestBody CreateCategoryRequest body) {
        CategoryView created =
                categories.create(
                        caller.userId(),
                        new NewCategory(body.name(), body.kind(), body.parentId()));
        return ResponseEntity.created(URI.create("/api/categories/" + created.id()))
                .body(response(created));
    }

    @GetMapping("/{id}")
    CategoryResponse one(TransportAuthentication caller, @PathVariable UUID id) {
        return response(categories.get(caller.userId(), id));
    }

    /**
     * A partial update: name, parent, or both.
     *
     * <p>An absent {@code parentId} leaves the parent alone; an explicit {@code null} moves the
     * category to the top level. {@code version} says which version the caller read, and a stale
     * one is {@code 409 stale-version} rather than an overwrite.
     */
    @PatchMapping("/{id}")
    CategoryResponse update(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCategoryRequest body) {
        return response(
                categories.update(
                        caller.userId(),
                        id,
                        new CategoryChanges(body.name(), body.parentChange(), body.version())));
    }

    /** Keeps history readable, hides the category from pickers. Idempotent. */
    @PostMapping("/{id}/archive")
    CategoryResponse archive(TransportAuthentication caller, @PathVariable UUID id) {
        return response(categories.archive(caller.userId(), id));
    }

    @PostMapping("/{id}/unarchive")
    CategoryResponse unarchive(TransportAuthentication caller, @PathVariable UUID id) {
        return response(categories.unarchive(caller.userId(), id));
    }

    /**
     * Deletes the category and its children. <b>No transactions are deleted</b> — those filed under
     * it become "Needs a category". Always permitted, which is why there is no {@code
     * category-in-use} conflict: the database re-assigns rather than refusing.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(TransportAuthentication caller, @PathVariable UUID id) {
        categories.delete(caller.userId(), id);
    }

    private static CategoryResponse response(CategoryView category) {
        return new CategoryResponse(
                category.id(),
                category.name(),
                category.kind(),
                category.parentId(),
                category.parentName(),
                category.archived(),
                category.createdAt(),
                category.updatedAt(),
                category.version());
    }
}
