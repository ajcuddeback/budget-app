package com.budgetowl.goal.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.goal.service.GoalCommands.ContributionChanges;
import com.budgetowl.goal.service.GoalCommands.DraftGoal;
import com.budgetowl.goal.service.GoalCommands.GoalChanges;
import com.budgetowl.goal.service.GoalCommands.GoalPreview;
import com.budgetowl.goal.service.GoalCommands.NewContribution;
import com.budgetowl.goal.service.GoalCommands.NewGoal;
import com.budgetowl.goal.service.GoalService;
import com.budgetowl.goal.service.GoalViews.ContributionView;
import com.budgetowl.goal.service.GoalViews.GoalView;
import com.budgetowl.goal.service.GoalViews.Page;
import com.budgetowl.goal.service.PlanStep;
import com.budgetowl.goal.web.GoalRequests.CreateContributionRequest;
import com.budgetowl.goal.web.GoalRequests.CreateGoalRequest;
import com.budgetowl.goal.web.GoalRequests.DraftPreviewRequest;
import com.budgetowl.goal.web.GoalRequests.GoalPreviewRequest;
import com.budgetowl.goal.web.GoalRequests.SetPlanRequest;
import com.budgetowl.goal.web.GoalRequests.UpdateContributionRequest;
import com.budgetowl.goal.web.GoalRequests.UpdateGoalRequest;
import com.budgetowl.goal.web.GoalResponses.ContributionResponse;
import com.budgetowl.goal.web.GoalResponses.DraftResponse;
import com.budgetowl.goal.web.GoalResponses.GoalResponse;
import com.budgetowl.goal.web.GoalResponses.PlanStepResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.YearMonth;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's goals, their plans and their contributions ({@code docs/features/goals.md}).
 *
 * <p>No household in any path (ADR-0026); it is resolved from the caller's verified membership
 * inside {@link GoalService}. An id in a path is a lookup key: another household's is a {@code
 * 404}, never a {@code 403}. Roles are enforced in the service, and <b>before</b> the goal is
 * looked up, so a {@code VIEWER} learns nothing about which ids exist.
 *
 * <p><b>Not {@code @Validated}</b>, on purpose: see {@code ArchitectureTest
 * .nothing_is_validated_at_class_level}. Spring validates the bodies natively and a malformed path
 * variable is already a {@code 400}.
 *
 * <p><b>The previews are {@code POST}s</b> so an amount travels in a body and never in a URL, the
 * one part of a request every layer logs. That makes them writes as far as roles go.
 */
@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalService goals;

    public GoalController(GoalService goals) {
        this.goals = goals;
    }

    /** Every goal with its figures: target month ascending with none last, then name, then id. */
    @GetMapping
    PageResponse<GoalResponse> list(
            TransportAuthentication caller,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return page(goals.list(caller.userId(), includeArchived, page, size), GoalResponse::of);
    }

    /** {@code OWNER} or {@code MEMBER}. {@code Location} names the id, never the name. */
    @PostMapping
    ResponseEntity<GoalResponse> create(
            TransportAuthentication caller, @Valid @RequestBody CreateGoalRequest body) {
        GoalView created =
                goals.create(
                        caller.userId(),
                        new NewGoal(
                                body.name(),
                                body.targetAmount(),
                                body.targetMonth(),
                                body.startingAmount(),
                                body.monthlyContribution(),
                                body.currency(),
                                body.accountId()));
        return ResponseEntity.created(URI.create("/api/goals/" + created.id()))
                .body(GoalResponse.of(created));
    }

    /** Pure arithmetic for the add form. Reads no household data and writes nothing. */
    @PostMapping("/projection-preview")
    DraftResponse previewDraft(
            TransportAuthentication caller, @Valid @RequestBody DraftPreviewRequest body) {
        return DraftResponse.of(
                goals.previewDraft(
                        caller.userId(),
                        new DraftGoal(
                                body.currency(),
                                body.targetAmount(),
                                body.startingAmount(),
                                body.targetMonth(),
                                body.monthlyAmount())));
    }

    @GetMapping("/{id}")
    GoalResponse one(TransportAuthentication caller, @PathVariable UUID id) {
        return GoalResponse.of(goals.get(caller.userId(), id));
    }

    @PatchMapping("/{id}")
    GoalResponse update(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateGoalRequest body) {
        return GoalResponse.of(
                goals.update(
                        caller.userId(),
                        id,
                        new GoalChanges(
                                body.name(),
                                body.targetAmount(),
                                body.targetMonthChange(),
                                body.startingAmount(),
                                body.accountIdChange(),
                                body.version())));
    }

    /** Takes the goal's plan and contributions with it, and no transaction. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(TransportAuthentication caller, @PathVariable UUID id) {
        goals.delete(caller.userId(), id);
    }

    @PostMapping("/{id}/archive")
    GoalResponse archive(TransportAuthentication caller, @PathVariable UUID id) {
        return GoalResponse.of(goals.archive(caller.userId(), id));
    }

    @PostMapping("/{id}/unarchive")
    GoalResponse unarchive(TransportAuthentication caller, @PathVariable UUID id) {
        return GoalResponse.of(goals.unarchive(caller.userId(), id));
    }

    /**
     * What this goal would report with the plan, target, month or starting amount in the body
     * substituted. Writes nothing.
     */
    @PostMapping("/{id}/projection-preview")
    GoalResponse preview(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody GoalPreviewRequest body) {
        return GoalResponse.of(
                goals.preview(
                        caller.userId(),
                        id,
                        new GoalPreview(
                                body.monthlyAmount(),
                                body.targetAmount(),
                                body.startingAmount(),
                                body.targetMonthChange())));
    }

    // ------------------------------------------------------------------------------------ plan

    /** The plan's steps, oldest first. */
    @GetMapping("/{id}/contribution-plan")
    PageResponse<PlanStepResponse> plan(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<PlanStep> found = goals.plan(caller.userId(), id, page, size);
        return page(found, PlanStepResponse::of);
    }

    /**
     * From {@code fromPeriod} on, the plan is this amount, replacing later steps. Idempotent.
     *
     * @param fromPeriod as {@code 2026-09}, bound as a {@code YearMonth}, so a malformed one is a
     *     {@code 400} before anything here runs. A past month is {@code 422 goal-plan-period-past}
     */
    @PutMapping("/{id}/contribution-plan/{fromPeriod}")
    GoalResponse setPlan(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @PathVariable YearMonth fromPeriod,
            @Valid @RequestBody SetPlanRequest body) {
        return GoalResponse.of(
                goals.setPlan(
                        caller.userId(), id, fromPeriod, body.monthlyAmount(), body.version()));
    }

    // --------------------------------------------------------------------------- contributions

    /** Newest first, ties broken by when each was recorded. The order is the server's. */
    @GetMapping("/{id}/contributions")
    PageResponse<ContributionResponse> history(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<ContributionView> found = goals.history(caller.userId(), id, page, size);
        return page(found, ContributionResponse::of);
    }

    /** Writes no transaction and moves no account balance (ADR-0035). */
    @PostMapping("/{id}/contributions")
    ResponseEntity<ContributionResponse> contribute(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @Valid @RequestBody CreateContributionRequest body) {
        ContributionView created =
                goals.contribute(
                        caller.userId(),
                        id,
                        new NewContribution(
                                body.amount(), body.date(), body.note(), body.transactionId()));
        return ResponseEntity.created(
                        URI.create("/api/goals/" + id + "/contributions/" + created.id()))
                .body(ContributionResponse.of(created));
    }

    @PatchMapping("/{id}/contributions/{contributionId}")
    ContributionResponse correct(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @PathVariable UUID contributionId,
            @Valid @RequestBody UpdateContributionRequest body) {
        return ContributionResponse.of(
                goals.correct(
                        caller.userId(),
                        id,
                        contributionId,
                        new ContributionChanges(
                                body.amount(),
                                body.date(),
                                body.noteChange(),
                                body.transactionIdChange(),
                                body.version())));
    }

    @DeleteMapping("/{id}/contributions/{contributionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeContribution(
            TransportAuthentication caller,
            @PathVariable UUID id,
            @PathVariable UUID contributionId) {
        goals.removeContribution(caller.userId(), id, contributionId);
    }

    private static <T, R> PageResponse<R> page(Page<T> found, Function<T, R> mapper) {
        return new PageResponse<>(
                found.content().stream().map(mapper).toList(),
                found.page(),
                found.size(),
                found.totalElements(),
                found.totalPages());
    }
}
