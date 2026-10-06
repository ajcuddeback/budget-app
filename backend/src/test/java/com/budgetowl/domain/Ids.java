package com.budgetowl.domain;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Gives an entity the identity the database would have given it.
 *
 * <p>Entity ids are generated on persist, so an unsaved instance has none — and equality, which is
 * the thing worth testing, is defined in terms of it. Rather than start a database for that, this
 * plants an id directly. Test-only, and the only reflection in the suite that is not proving a
 * point about secrecy.
 */
public final class Ids {

    private Ids() {}

    public static <T> T withId(T entity) {
        return withId(entity, UUID.randomUUID());
    }

    public static <T> T withId(T entity, UUID id) {
        return withField(entity, "id", id);
    }

    public static <T> T withUserId(T entity) {
        return withField(entity, "userId", UUID.randomUUID());
    }

    public static <T> T withUserId(T entity, UUID id) {
        return withField(entity, "userId", id);
    }

    public static UUID idOf(Object entity) {
        return read(entity, "id");
    }

    private static <T> T withField(T entity, String name, UUID value) {
        try {
            Field field = entity.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(entity, value);
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + name + " on " + entity.getClass(), e);
        }
    }

    private static UUID read(Object entity, String name) {
        try {
            Field field = entity.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (UUID) field.get(entity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no " + name + " on " + entity.getClass(), e);
        }
    }
}
