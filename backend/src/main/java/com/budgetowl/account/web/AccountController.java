package com.budgetowl.account.web;

import com.budgetowl.account.service.AccountChanges;
import com.budgetowl.account.service.AccountPage;
import com.budgetowl.account.service.AccountService;
import com.budgetowl.account.service.AccountView;
import com.budgetowl.account.service.NewAccount;
import com.budgetowl.account.web.AccountRequests.CreateAccountRequest;
import com.budgetowl.account.web.AccountRequests.UpdateAccountRequest;
import com.budgetowl.account.web.AccountResponses.AccountResponse;
import com.budgetowl.auth.service.TransportAuthentication;
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
 * The household's accounts.
 *
 * <p>There is no household in any of these paths. An instance holds exactly one (ADR-0026) and it
 * is resolved from the caller's verified membership inside {@link AccountService} — the parameter
 * an attacker would swap does not exist. The {@code id} in a path is a lookup key and nothing else:
 * an account belonging to nobody the caller is a member of returns {@code 404}, never {@code 403},
 * because a {@code 403} would confirm the row exists.
 *
 * <p>No role is checked here, and no error response is built here. Both live in the service and the
 * {@code @RestControllerAdvice} respectively, because a check in two places is a check that will
 * eventually disagree with itself.
 *
 * <p>Archive and un-archive are sub-resources rather than a field on {@code PATCH}: retiring an
 * account is a different act from correcting its name and reads differently in an audit log
 * (docs/features/accounts.md).
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /**
     * Paginated from day one — retrofitting pagination is a breaking change
     * (docs/guides/api-style.md). A size over the maximum is clamped rather than rejected.
     *
     * @param includeArchived default false: archived accounts disappear from the default list and
     *     from pickers, and each row carries {@code archived} so a client that asks for them can
     *     group them
     */
    @GetMapping
    PageResponse<AccountResponse> list(
            TransportAuthentication caller,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AccountPage found = accounts.list(caller.userId(), includeArchived, page, size);
        return new PageResponse<>(
                found.accounts().stream().map(AccountController::response).toList(),
                found.page(),
                found.size(),
                found.totalElements(),
                found.totalPages());
    }

    /** {@code OWNER} or {@code MEMBER}, enforced in the service. */
    @PostMapping
    ResponseEntity<AccountResponse> create(
            TransportAuthentication caller, @Valid @RequestBody CreateAccountRequest body) {
        AccountView created =
                accounts.create(
                        caller.userId(),
                        new NewAccount(
                                body.name(),
                                body.lastFour(),
                                body.type(),
                                body.currency(),
                                body.openingBalance()));
        // The id, and nothing else. A Location carrying the digits would put a partial financial
        // identifier in every access log between here and the client (ADR-0028).
        return ResponseEntity.created(URI.create("/api/accounts/" + created.id()))
                .body(response(created));
    }

    @GetMapping("/{id}")
    AccountResponse one(TransportAuthentication caller, @PathVariable UUID id) {
        return response(accounts.get(caller.userId(), id));
    }

    /**
     * A partial update. An absent field is left alone; {@code version} says which version the
     * caller read, and a stale one is {@code 409 stale-version} rather than an overwrite.
     */
    @PatchMapping("/{id}")
    AccountResponse update(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAccountRequest body) {
        return response(
                accounts.update(
                        caller.userId(),
                        id,
                        new AccountChanges(
                                body.name(),
                                body.lastFourChange(),
                                body.type(),
                                body.currency(),
                                body.openingBalance(),
                                body.version())));
    }

    /** Keeps its history, hides it from lists and pickers. Idempotent. */
    @PostMapping("/{id}/archive")
    AccountResponse archive(TransportAuthentication caller, @PathVariable UUID id) {
        return response(accounts.archive(caller.userId(), id));
    }

    @PostMapping("/{id}/unarchive")
    AccountResponse unarchive(TransportAuthentication caller, @PathVariable UUID id) {
        return response(accounts.unarchive(caller.userId(), id));
    }

    /** Only while the account is empty. With transactions it is {@code 409 account-not-empty}. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(TransportAuthentication caller, @PathVariable UUID id) {
        accounts.delete(caller.userId(), id);
    }

    private static AccountResponse response(AccountView account) {
        return new AccountResponse(
                account.id(),
                account.name(),
                account.lastFour(),
                account.type(),
                account.openingBalance().currency().getCurrencyCode(),
                account.openingBalance().amount(),
                account.balance().amount(),
                account.archived(),
                account.createdAt(),
                account.updatedAt(),
                account.version());
    }
}
