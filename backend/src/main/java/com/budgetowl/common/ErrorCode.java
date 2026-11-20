package com.budgetowl.common;

/**
 * The registry of error codes. <b>A code is part of the API contract</b>
 * (docs/guides/api-style.md): the client renders the sentence from the code and its params, so
 * renaming one is a breaking change and shipping a translation must not need a backend release.
 *
 * <p>{@code title} is an English, developer-facing fallback for logs — never the string a user
 * sees.
 *
 * <p>Several codes are deliberately vague. {@link #AUTHENTICATION_FAILED} covers an unknown email
 * and a wrong password because telling them apart is a user-enumeration oracle, and {@link
 * #INVITATION_UNUSABLE} covers expired, revoked, already-used and never-existed because otherwise a
 * link's history is disclosed to whoever holds it.
 */
public enum ErrorCode {
    VALIDATION_FAILED("validation-failed", 400, "Validation failed"),
    MALFORMED_REQUEST("malformed-request", 400, "Malformed request"),
    PASSWORD_UNACCEPTABLE("password-unacceptable", 400, "Password unacceptable"),

    /** Unknown email and wrong password are the same answer, on purpose. */
    AUTHENTICATION_FAILED("authentication-failed", 401, "Authentication failed"),
    NOT_AUTHENTICATED("not-authenticated", 401, "Not authenticated"),

    FORBIDDEN("forbidden", 403, "Forbidden"),
    NOT_A_MEMBER("not-a-member", 403, "Not a member of this household"),
    OWNER_ONLY("owner-only", 403, "Only an owner may do this"),
    READ_ONLY_ROLE("read-only-role", 403, "This role may not write"),
    OWN_ROLE_UNCHANGEABLE("own-role-unchangeable", 403, "Nobody may change their own role"),
    CSRF_TOKEN_REQUIRED("csrf-token-required", 403, "CSRF token missing or invalid"),
    CURRENT_PASSWORD_INCORRECT("current-password-incorrect", 403, "Current password incorrect"),

    NOT_FOUND("not-found", 404, "Not found"),
    /** Expired, revoked, already used, or never existed — one answer for all four. */
    INVITATION_UNUSABLE("invitation-unusable", 404, "This invitation cannot be used"),

    SETUP_ALREADY_COMPLETE("setup-already-complete", 409, "Setup has already been completed"),
    INVITATION_REFUSED("invitation-refused", 409, "This invitation cannot be created"),
    INVITATION_REQUIRES_SIGN_OUT(
            "invitation-requires-sign-out", 409, "Sign out before accepting an invitation"),
    LAST_OWNER("last-owner", 409, "A household must keep at least one owner"),

    /**
     * Changing an account's currency once it has a transaction would silently reinterpret every
     * amount already recorded — the figures do not move, so £100 becomes €100 and nothing admits
     * it. Refused rather than applied, and never a silent no-op.
     */
    ACCOUNT_CURRENCY_IMMUTABLE(
            "account-currency-immutable", 409, "This account's currency can no longer be changed"),

    /** Deleting an account with transactions would destroy financial history. Archive instead. */
    ACCOUNT_NOT_EMPTY("account-not-empty", 409, "This account still has transactions"),

    /**
     * An account that has been retired takes no new transactions. Un-archive it to record against
     * it again — which is a deliberate act, rather than an archived account quietly accepting money
     * and disappearing from the list it would have appeared in.
     */
    ACCOUNT_ARCHIVED("account-archived", 409, "This account is archived"),

    /**
     * Two categories with the same name at the same level are a data-entry mistake, not a choice:
     * nothing downstream could tell the user which "Groceries" a transaction is filed under.
     * Enforced by {@code ux_categories_household_parent_name} as well as checked here.
     */
    CATEGORY_NAME_TAKEN("category-name-taken", 409, "A category with this name already exists"),

    /**
     * The category hierarchy is two levels (docs/domain/model.md). Reported for a parent that is
     * itself a child, and for moving a category that has children of its own.
     */
    CATEGORY_DEPTH_EXCEEDED(
            "category-depth-exceeded", 409, "Categories can only be nested two levels deep"),

    /**
     * A household is capped at {@code CategoryService.MAXIMUM_PER_HOUSEHOLD} categories, which is
     * the API's maximum page size — so the whole tree always fits in one response and a picker
     * never has to page. A household that wants more has a different problem.
     */
    CATEGORY_LIMIT_REACHED("category-limit-reached", 409, "This household has too many categories"),

    /**
     * A category's kind is fixed at creation. Re-kinding one in use would reinterpret every
     * transaction already filed under it, turning a year of salary into a year of spending.
     */
    CATEGORY_KIND_IMMUTABLE("category-kind-immutable", 409, "A category's kind cannot be changed"),

    /**
     * Two envelopes with the same name in the same month are a data-entry mistake. The same name in
     * two different months is two different decisions, and both must exist.
     */
    ENVELOPE_NAME_TAKEN("envelope-name-taken", 409, "An envelope with this name already exists"),

    /**
     * An archived envelope takes no new entries. It is kept so its log still reads correctly, not
     * so spending can keep being filed against a plan somebody retired.
     */
    ENVELOPE_ARCHIVED("envelope-archived", 409, "This envelope is archived"),

    /**
     * A transaction may be attached to at most one envelope entry, ever (ADR-0031). The same
     * purchase counted in two envelopes would overstate spending in both and understate what is
     * left in each — and both figures would still look plausible.
     */
    TRANSACTION_ALREADY_IN_ENVELOPE(
            "transaction-already-in-envelope", 409, "This transaction is already in an envelope"),

    /**
     * Two bills with the same name are a data-entry mistake: nothing downstream could tell the user
     * which "Council tax" an occurrence belongs to.
     */
    BILL_NAME_TAKEN("bill-name-taken", 409, "A bill with this name already exists"),

    /**
     * A retired bill projects no occurrences and takes no new records. It is kept so the periods
     * somebody already settled still read correctly — not so a plan nobody has any more can keep
     * being ticked off.
     */
    BILL_INACTIVE("bill-inactive", 409, "This bill is retired"),

    /** Two income sources with the same name, for the reason {@link #BILL_NAME_TAKEN} gives. */
    INCOME_NAME_TAKEN("income-name-taken", 409, "An income source with this name already exists"),

    /** Optimistic lock conflict: somebody else saved first and the caller's copy is stale. */
    STALE_VERSION("stale-version", 409, "This record changed while you were editing it"),

    CONFLICT("conflict", 409, "Conflict"),

    RATE_LIMITED("rate-limited", 429, "Too many attempts"),
    INTERNAL_ERROR("internal-error", 500, "Internal error");

    private final String code;
    private final int status;
    private final String title;

    ErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }
}
