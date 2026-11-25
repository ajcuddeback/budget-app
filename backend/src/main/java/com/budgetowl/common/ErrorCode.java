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

    /**
     * The caller is not the instance administrator (ADR-0029).
     *
     * <p>A {@code 403} rather than a {@code 404}, unlike another household's row: the instance's
     * existence is not a secret from the people signed in to it, and "you are not the
     * administrator" is a different fact from "this does not exist". It is also not {@link
     * #OWNER_ONLY} — administrator governs the instance, {@code OWNER} governs the money, and an
     * {@code OWNER} is refused here like anyone else.
     */
    NOT_INSTANCE_ADMIN("not-instance-admin", 403, "Only the instance administrator may do this"),

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

    /**
     * A transfer is a pair and is changed as one (ADR-0033). Editing or deleting a single leg would
     * leave money that left one account and arrived nowhere, or — worse, because nothing downstream
     * would notice — a same-currency pair whose two halves no longer agree.
     */
    TRANSFER_LEG_NOT_SEPARATE("transfer-leg-not-separate", 409, "This is one half of a transfer"),

    /**
     * Password login cannot be turned off yet, because no {@code OWNER} has proved the other route
     * works (security-model.md, {@code ck_instance_settings_password_login_lockout}).
     *
     * <p>"Disable password login, then discover the OIDC config is wrong" locks the operator out of
     * their own financial records on their own hardware, with no support desk to call. The database
     * refuses it; this code is how the console explains it instead of showing a 500.
     */
    PASSWORD_LOGIN_LOCKED("password-login-locked", 409, "Password login cannot be turned off yet"),

    /**
     * A household is capped at {@code DebtService.MAXIMUM_PER_HOUSEHOLD} debts.
     *
     * <p>Not a tidiness rule: {@code GET /api/debt-plan} projects <b>both</b> strategies over every
     * debt, so an unbounded number of debts turns a loop of cheap {@code POST}s into an expensive
     * {@code GET} (docs/features/debt-plan.md, Security considerations).
     */
    DEBT_LIMIT_REACHED("debt-limit-reached", 409, "This household has too many debts"),

    /**
     * A debt whose balance has reached zero takes no new payments and no new terms. Confirming a
     * balance is the way back — a correction reopens it, which is why that one route is allowed.
     */
    DEBT_PAID_OFF("debt-paid-off", 409, "This debt is paid off"),

    /**
     * A household is capped at {@code GoalService.MAXIMUM_PER_HOUSEHOLD} goals, and a goal at
     * {@code GoalService.MAXIMUM_CONTRIBUTIONS_PER_GOAL} contributions.
     *
     * <p>A cost control, not a tidiness rule: every {@code GET /api/goals} and every period summary
     * projects every goal, so unbounded cheap writes would become an expensive read
     * (docs/features/goals.md, threat model attack 6).
     */
    GOAL_LIMIT_REACHED("goal-limit-reached", 409, "This household has too many goals"),

    /**
     * Two unarchived goals with the same name, compared case-insensitively. The check-in's "put the
     * surplus in Holiday" and Owl's "Japan trip" name a goal, and could not tell two apart.
     */
    GOAL_NAME_TAKEN("goal-name-taken", 409, "A goal with this name already exists"),

    /**
     * An archived goal takes no new contributions and no plan changes. Its history still reads.
     * Un-archiving it is the deliberate way back, as for an envelope.
     */
    GOAL_ARCHIVED("goal-archived", 409, "This goal is archived"),

    /**
     * The monthly plan changes from the current month forwards, never backwards. Setting a step in
     * a past month would rewrite what that month's left-over figure already said; a correction to
     * what happened then is a contribution, not a plan.
     */
    GOAL_PLAN_PERIOD_PAST(
            "goal-plan-period-past", 422, "The plan can only change from the current month"),

    /**
     * A plan change may start at most {@code GoalService.MAXIMUM_PLAN_LEAD_MONTHS} ahead. Without
     * it a plan could be chained out to year 9999, one step a month, and every read loads them all.
     */
    GOAL_PLAN_PERIOD_TOO_FAR(
            "goal-plan-period-too-far", 422, "The plan can only change up to two years ahead"),

    /**
     * A goal kept in an account is in that account's currency. Linking one in another currency
     * would leave the goal page showing two figures in two units side by side as if comparable.
     */
    GOAL_CURRENCY_MISMATCH(
            "goal-currency-mismatch", 422, "This account is in a different currency"),

    /** Optimistic lock conflict: somebody else saved first and the caller's copy is stale. */
    STALE_VERSION("stale-version", 409, "This record changed while you were editing it"),

    CONFLICT("conflict", 409, "Conflict"),

    /**
     * All three of {@code apr}, {@code payment} and the horizon were entered.
     *
     * <p>One equation among four quantities has three degrees of freedom, so a third entered value
     * over-determines it. A {@code 422} naming the fields rather than silently dropping one: a
     * number the household typed must never be quietly replaced by a computed one. The client is
     * what releases a field (docs/features/debt-plan.md).
     */
    TERMS_OVER_DETERMINED("terms-over-determined", 422, "These terms over-determine the equation"),

    /**
     * There is no answer: a horizon the balance cannot reach at any non-negative rate, a payment
     * that clears it at every rate, a payoff month in the past, or a search that hit one of the
     * solver's caps. The cap being reachable is the point — it is what keeps one request bounded.
     *
     * <p><b>Not</b> the same as {@code never-clears}, which is a reason reported beside a null
     * projection on terms that were accepted and stored.
     */
    TERMS_UNSOLVABLE("terms-unsolvable", 422, "These terms have no solution"),

    /**
     * A report's {@code from} is after its {@code to}, or the range (or the one before it, which
     * spending by category compares against) reaches before year 1. Both ends were well-formed
     * months, so this is a {@code 422} rather than a {@code 400}.
     */
    REPORT_RANGE_INVALID("report-range-invalid", 422, "This report range is not a range"),

    /**
     * A report range longer than {@code ReportRange.MAXIMUM_MONTHS}, which is in {@code params} as
     * {@code maximumMonths}. A threat-model control, not a display preference: it bounds the scan
     * and the response, which carry no pagination (docs/features/reporting.md, attack 4).
     */
    REPORT_RANGE_TOO_LONG("report-range-too-long", 422, "This report range is too long"),

    // --------------------------------------------------------------- file import (slice 12)
    // docs/features/file-import.md, "Error codes". Every refusal of a file carries a code and,
    // for import-file-malformed, a params.reason from a FIXED list — never text from a parser
    // exception, which quotes the bytes it choked on.

    /** Over the 5 MiB cap, at nginx, at the request filter or after decoding. */
    IMPORT_FILE_TOO_LARGE("import-file-too-large", 413, "The file is too large"),

    /**
     * A {@code Content-Encoding} other than {@code identity}. A compressed body decompresses to a
     * size nothing counted, so it is refused rather than inflated.
     */
    IMPORT_CONTENT_ENCODING_REFUSED(
            "import-content-encoding-refused", 415, "Compressed request bodies are not accepted"),

    IMPORT_FILE_EMPTY("import-file-empty", 400, "The file is empty"),

    /** A structural refusal. {@code params.reason} names which, from a fixed list. */
    IMPORT_FILE_MALFORMED("import-file-malformed", 400, "The file could not be read"),

    /** The declared format is not what the bytes are. The server never guesses instead. */
    IMPORT_FORMAT_MISMATCH("import-format-mismatch", 400, "The file is not in the declared format"),

    IMPORT_ENCODING_UNSUPPORTED(
            "import-encoding-unsupported", 422, "The file's character set is not supported"),

    /** One statement for one account per file. */
    IMPORT_MULTIPLE_ACCOUNTS(
            "import-multiple-accounts", 422, "The file holds more than one account's statement"),

    IMPORT_CURRENCY_MISMATCH(
            "import-currency-mismatch", 422, "The file is in a different currency"),

    /** Over the line or record cap. The whole file is refused, never truncated. */
    IMPORT_TOO_MANY_ROWS("import-too-many-rows", 422, "The file has too many rows"),

    /** The parse deadline passed. */
    IMPORT_FILE_TOO_COMPLEX("import-file-too-complex", 422, "The file took too long to read"),

    /**
     * The commit's digest is not the preview's — the ledger or the file changed in between, or a
     * concurrent commit won the unique index. Nothing was written.
     */
    IMPORT_PREVIEW_STALE("import-preview-stale", 409, "The preview is out of date"),

    /**
     * Two rows that cannot be one transfer (file-import.md rule 12). {@code params.reason} is
     * {@code same-account}, {@code same-sign} or {@code amounts-differ}.
     */
    TRANSFER_PAIR_MISMATCH("transfer-pair-mismatch", 422, "These two rows cannot be one transfer"),

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
