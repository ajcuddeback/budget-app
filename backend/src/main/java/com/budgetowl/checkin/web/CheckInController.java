package com.budgetowl.checkin.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.checkin.domain.IsoWeek;
import com.budgetowl.checkin.domain.ItemKeys;
import com.budgetowl.checkin.service.AnswerCommand;
import com.budgetowl.checkin.service.CheckInService;
import com.budgetowl.checkin.service.CheckInService.Started;
import com.budgetowl.checkin.service.CheckInViews.Page;
import com.budgetowl.checkin.web.CheckInRequests.AnswerRequest;
import com.budgetowl.checkin.web.CheckInRequests.CompleteRequest;
import com.budgetowl.checkin.web.CheckInResponses.HistoryResponse;
import com.budgetowl.checkin.web.CheckInResponses.WeekResponse;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.common.web.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The weekly check-in ({@code docs/features/weekly-check-in.md}).
 *
 * <p>No household in any path (ADR-0026); it is resolved from the caller's verified membership in
 * {@link CheckInService}. Roles are enforced there, <b>before</b> the week or item is looked up.
 *
 * <p><b>The edge refuses malformed input with {@code 400}</b> before anything else runs (threat
 * model, attack 10): a {@code {week}} that is not exactly {@code ^\d{4}-W\d{2}$} and a real ISO
 * week, and an {@code {itemKey}} longer than 100 characters or in none of the five fixed shapes. A
 * well-formed key that does not derive to an item in this week is the service's {@code 404}.
 *
 * <p>Not {@code @Validated}, on purpose: see {@code ArchitectureTest
 * .nothing_is_validated_at_class_level}.
 */
@RestController
@RequestMapping("/api/check-ins")
public class CheckInController {

    private final CheckInService checkIns;

    public CheckInController(CheckInService checkIns) {
        this.checkIns = checkIns;
    }

    /** The history, newest week first. */
    @GetMapping
    PageResponse<HistoryResponse> history(
            TransportAuthentication caller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<com.budgetowl.checkin.service.CheckInViews.HistoryEntry> found =
                checkIns.history(caller.userId(), page, size);
        return new PageResponse<>(
                found.content().stream().map(HistoryResponse::of).toList(),
                found.page(),
                found.size(),
                found.totalElements(),
                found.totalPages());
    }

    /** This week, by the server's clock. */
    @GetMapping("/current")
    WeekResponse current(TransportAuthentication caller) {
        return WeekResponse.of(checkIns.current(caller.userId()));
    }

    @GetMapping("/{week}")
    WeekResponse one(TransportAuthentication caller, @PathVariable String week) {
        return WeekResponse.of(checkIns.get(caller.userId(), week(week)));
    }

    /** Starts the draft. {@code 201} the first time, {@code 200} with the same draft after. */
    @PutMapping("/{week}")
    ResponseEntity<WeekResponse> start(TransportAuthentication caller, @PathVariable String week) {
        IsoWeek parsed = week(week);
        Started started = checkIns.start(caller.userId(), parsed);
        WeekResponse body = WeekResponse.of(started.view());
        return started.created()
                ? ResponseEntity.created(URI.create("/api/check-ins/" + parsed)).body(body)
                : ResponseEntity.ok(body);
    }

    /** Discards a draft and its answers. */
    @DeleteMapping("/{week}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(TransportAuthentication caller, @PathVariable String week) {
        checkIns.discard(caller.userId(), week(week));
    }

    /** Records or replaces one answer, and returns the whole week so its figure settles. */
    @PutMapping("/{week}/answers/{itemKey}")
    WeekResponse answer(
            TransportAuthentication caller,
            @PathVariable String week,
            @PathVariable String itemKey,
            @Valid @RequestBody AnswerRequest body) {
        IsoWeek parsed = week(week);
        return WeekResponse.of(
                checkIns.answer(
                        caller.userId(),
                        parsed,
                        itemKey(itemKey),
                        new AnswerCommand(
                                body.choice(),
                                body.categoryId(),
                                body.billId(),
                                body.dueDate(),
                                body.goalId(),
                                body.debtId(),
                                body.amount(),
                                body.remember(),
                                body.recordDebtPayment(),
                                body.version())));
    }

    @DeleteMapping("/{week}/answers/{itemKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clearAnswer(
            TransportAuthentication caller,
            @PathVariable String week,
            @PathVariable String itemKey) {
        IsoWeek parsed = week(week);
        checkIns.clearAnswer(caller.userId(), parsed, itemKey(itemKey));
    }

    /** Everything, atomically. */
    @PostMapping("/{week}/complete")
    WeekResponse complete(
            TransportAuthentication caller,
            @PathVariable String week,
            @Valid @RequestBody CompleteRequest body) {
        return WeekResponse.of(
                checkIns.complete(caller.userId(), week(week), body.version(), body.basis()));
    }

    private static IsoWeek week(String text) {
        return IsoWeek.parse(text)
                .orElseThrow(
                        () ->
                                new InvalidRequestException(
                                        ErrorCode.VALIDATION_FAILED,
                                        "a week is written 2026-W34 and must exist",
                                        Map.of("field", "week")));
    }

    private static String itemKey(String text) {
        if (ItemKeys.parse(text).isEmpty()) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "not an item key this server issues",
                    Map.of("field", "itemKey"));
        }
        return text;
    }
}
