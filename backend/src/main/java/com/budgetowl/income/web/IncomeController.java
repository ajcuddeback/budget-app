package com.budgetowl.income.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.income.service.IncomeChanges;
import com.budgetowl.income.service.IncomeService;
import com.budgetowl.income.service.IncomeView;
import com.budgetowl.income.service.NewIncome;
import com.budgetowl.income.web.IncomeRequests.CreateIncomeRequest;
import com.budgetowl.income.web.IncomeRequests.UpdateIncomeRequest;
import com.budgetowl.income.web.IncomeResponses.IncomeListResponse;
import com.budgetowl.income.web.IncomeResponses.IncomeResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.YearMonth;
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
 * The household's income sources.
 *
 * <p>No household in any path (ADR-0026), and no occurrences anywhere: nobody ticks their salary
 * off. A month's expected income is the schedule counted and multiplied.
 */
@RestController
@RequestMapping("/api/income")
public class IncomeController {

    private final IncomeService income;

    public IncomeController(IncomeService income) {
        this.income = income;
    }

    /** One month's sources, each with what it is worth that month. */
    @GetMapping
    IncomeListResponse list(
            TransportAuthentication caller,
            @RequestParam YearMonth period,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return IncomeListResponse.of(income.list(caller.userId(), period, includeInactive));
    }

    /** {@code OWNER} or {@code MEMBER}, enforced in the service. */
    @PostMapping
    ResponseEntity<IncomeResponse> create(
            TransportAuthentication caller, @Valid @RequestBody CreateIncomeRequest body) {
        IncomeView created =
                income.create(
                        caller.userId(),
                        new NewIncome(
                                body.name(),
                                body.amount(),
                                body.schedule().toRecurrence(),
                                body.accountId()));
        return ResponseEntity.created(URI.create("/api/income/" + created.id()))
                .body(IncomeResponse.of(created));
    }

    @GetMapping("/{id}")
    IncomeResponse one(
            TransportAuthentication caller, @PathVariable UUID id, @RequestParam YearMonth period) {
        return IncomeResponse.of(income.get(caller.userId(), id, period));
    }

    @PatchMapping("/{id}")
    IncomeResponse update(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateIncomeRequest body) {
        return IncomeResponse.of(
                income.update(
                        caller.userId(),
                        id,
                        new IncomeChanges(
                                body.name(),
                                body.amount(),
                                body.schedule() == null ? null : body.schedule().toRecurrence(),
                                body.accountIdChange(),
                                body.active(),
                                body.version())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(TransportAuthentication caller, @PathVariable UUID id) {
        income.delete(caller.userId(), id);
    }
}
