package com.budgetowl.instance.service;

/**
 * What an unauthenticated visitor is allowed to know about this instance before signing in.
 *
 * <p>Deliberately four booleans. A fresh instance must show a create-first-account screen instead
 * of a login, and a client cannot know which to render without asking — but nothing here names the
 * operator, counts the members, or says anything about the household. The one fact given away is
 * that this is a Budget Owl instance, which is already obvious to anyone looking at it.
 */
public record SetupStatus(
        boolean setupComplete,
        boolean registrationOpen,
        boolean passwordLoginEnabled,
        boolean oidcEnabled) {}
