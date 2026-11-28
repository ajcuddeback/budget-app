package com.budgetowl.debt.persistence;

import com.budgetowl.debt.domain.PayoffPlan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * The household's one payoff plan (ADR-0026, {@code uq_payoff_plans_household}).
 *
 * <p>There is no {@code findById}, and not only because ADR-0008 forbids one for financial data:
 * the plan has no id anybody addresses. {@code /api/debt-plan} is singular, so the household is the
 * key, and {@link #findByHouseholdId} returning empty is the ordinary state of a household that has
 * not set a plan yet rather than an error.
 */
public interface PayoffPlanRepository extends Repository<PayoffPlan, UUID> {

    /** See {@code AccountRepository.saveAndFlush} — {@code @Version} is bumped at flush. */
    PayoffPlan saveAndFlush(PayoffPlan plan);

    Optional<PayoffPlan> findByHouseholdId(UUID householdId);
}
