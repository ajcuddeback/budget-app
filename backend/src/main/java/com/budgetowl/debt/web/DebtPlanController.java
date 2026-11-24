package com.budgetowl.debt.web;

import com.budgetowl.auth.service.TransportAuthentication;
import com.budgetowl.debt.service.PayoffPlanService;
import com.budgetowl.debt.service.PlanChanges;
import com.budgetowl.debt.web.DebtRequests.UpdatePlanRequest;
import com.budgetowl.debt.web.DebtResponses.PlanResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payoff plan.
 *
 * <p><b>Singular</b>, because there is exactly one per household (ADR-0026), like {@code
 * /api/periods/{period}/summary}. There is no plan id in any path and none in any response body: a
 * second row would be a second answer to "what is my strategy".
 *
 * <p>The what-if is a <b>{@code GET} with query parameters and it writes nothing</b> — not the
 * amount it was asked about, not a snapshot. <em>"Adding $50/mo moves the whole ladder up by 3
 * months"</em> is the plan re-run, not a plan saved.
 *
 * <p>A {@code VIEWER} may read both and may {@code PUT} neither: setting a strategy changes every
 * projection the household acts on, so it is a write even though it stores two values.
 */
@RestController
@RequestMapping("/api/debt-plan")
@Validated
public class DebtPlanController {

    private final PayoffPlanService plans;

    public DebtPlanController(PayoffPlanService plans) {
        this.plans = plans;
    }

    /** The strategy, the amount, the order, <b>both</b> projections and the comparison. */
    @GetMapping
    PlanResponse plan(TransportAuthentication caller) {
        return PlanResponse.of(plans.plan(caller.userId()));
    }

    /**
     * Sets the strategy, the monthly amount and the custom order. Requires the plan's version.
     *
     * <p>Writes a snapshot when something actually changes, so <em>"4 months earlier than last
     * month's plan"</em> has something to compare against — it cannot be backfilled.
     */
    @PutMapping
    PlanResponse update(
            TransportAuthentication caller, @Valid @RequestBody UpdatePlanRequest body) {
        return PlanResponse.of(
                plans.update(
                        caller.userId(),
                        new PlanChanges(
                                body.strategy(),
                                body.monthlyAmount(),
                                body.customOrder(),
                                body.version())));
    }

    /**
     * The what-if. Writes nothing.
     *
     * @param strategy omitted to keep the selected one
     * @param monthlyAmount a decimal <b>string</b> (ADR-0006), omitted to keep the plan's
     */
    @GetMapping("/projection")
    PlanResponse projection(
            TransportAuthentication caller,
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false)
                    @Pattern(
                            regexp = "^\\d{1,15}(\\.\\d{1,4})?$",
                            message = "must be a decimal with at most 4 decimal places")
                    String monthlyAmount) {
        return PlanResponse.of(plans.projection(caller.userId(), strategy, monthlyAmount));
    }
}
