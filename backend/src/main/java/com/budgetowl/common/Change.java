package com.budgetowl.common;

/**
 * What a {@code PATCH} said about one nullable field: nothing, clear it, or set it to this.
 *
 * <p>JSON cannot distinguish "I did not mention this field" from "set this field to null" in a
 * single nullable value, and the two are different instructions. Collapsing them means a client
 * that PATCHes only the amount silently clears the note, the payee and the category — a data-loss
 * bug that passes every test written against a client that always sends every field.
 *
 * <p>Generic because three fields on a transaction need it. {@code LastFourChange} in the accounts
 * slice is the same shape written out by hand, and predates this; it is left alone rather than
 * migrated, because rewriting a merged, tested control to deduplicate twenty lines is a bad trade.
 * New fields use this.
 *
 * @param <T> the field's type
 */
public sealed interface Change<T> {

    /** The caller did not mention the field. */
    record Unchanged<T>() implements Change<T> {}

    /** Explicit JSON null: clear the field. */
    record Cleared<T>() implements Change<T> {}

    /** Set the field to this value. */
    record SetTo<T>(T value) implements Change<T> {}

    static <T> Change<T> unchanged() {
        return new Unchanged<>();
    }

    static <T> Change<T> cleared() {
        return new Cleared<>();
    }

    /** {@code null} is {@link #cleared()}, so a caller holding a nullable value need not branch. */
    static <T> Change<T> setTo(T value) {
        return value == null ? cleared() : new SetTo<>(value);
    }

    /**
     * Resolves the three-way answer from a value and whether the key was present — the shape every
     * request object ends up needing, kept here so each one does not re-derive it.
     */
    static <T> Change<T> of(T value, boolean mentioned) {
        return mentioned ? setTo(value) : unchanged();
    }
}
