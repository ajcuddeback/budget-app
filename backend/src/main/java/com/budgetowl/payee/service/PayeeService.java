package com.budgetowl.payee.service;

import com.budgetowl.household.domain.Household;
import com.budgetowl.payee.domain.Payee;
import com.budgetowl.payee.persistence.PayeeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payees: get-or-create by name, and prefix search for the transaction form.
 *
 * <p>There is no create, rename, merge or delete endpoint in slice 5. A payee is a by-product of
 * recording a transaction — the person types a name, and that name either matches one the household
 * already has or becomes a new one. Managing payees as objects in their own right (merging
 * "STARBUCKS #1234" into "Starbucks", renaming one across a year of history) arrives with
 * reporting, which is the first feature that gives a merge a visible payoff.
 */
@Service
public class PayeeService {

    /** How many suggestions the transaction form's autocomplete asks for. */
    public static final int SUGGESTION_LIMIT = 10;

    private final PayeeRepository payees;

    public PayeeService(PayeeRepository payees) {
        this.payees = payees;
    }

    /**
     * The household's payee with this name, creating it if there is not one.
     *
     * <p><b>The uniqueness check is the index, not the lookup.</b> Two members recording a
     * transaction to the same new payee at the same moment both find nothing and both insert; one
     * of them loses on {@code ux_payees_household_name}, and the correct answer for the loser is
     * the row the winner just created, not an error — nobody asked to create a payee, they asked to
     * record a transaction. So the violation is caught and the lookup retried once.
     *
     * <p>Runs inside the caller's transaction so a payee is never created for a transaction that
     * then fails to save. That is also why the retry is a second {@code findByName} rather than a
     * nested transaction: by the time this runs, the competing insert has committed.
     *
     * @param name as the user typed it; matched case-insensitively and stored trimmed
     */
    @Transactional
    public Payee getOrCreate(Household household, String name) {
        UUID householdId = household.id();
        String trimmed = name.strip();

        Optional<Payee> existing = payees.findByName(householdId, trimmed);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return payees.saveAndFlush(Payee.named(household, trimmed));
        } catch (DataIntegrityViolationException lostTheRace) {
            return payees.findByName(householdId, trimmed)
                    .orElseThrow(() -> lostTheRace);
        }
    }

    /**
     * Payee names beginning with what has been typed, for the transaction form.
     *
     * <p>{@code %} and {@code _} are escaped before the value reaches {@code like}: they are
     * wildcards, so a household with a payee called "100% Chiropractic" would otherwise see every
     * payee suggested the moment somebody typed "100%". Not a security hole — the value is a bound
     * parameter and was never going to be injected — but a correctness one.
     */
    @Transactional(readOnly = true)
    public List<Payee> suggest(UUID householdId, String typed) {
        String trimmed = typed == null ? "" : typed.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        String escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return payees.findByNamePrefix(
                householdId, escaped + "%", PageRequest.of(0, SUGGESTION_LIMIT));
    }

    @Transactional(readOnly = true)
    public Optional<Payee> find(UUID householdId, UUID payeeId) {
        return payees.findByIdAndHouseholdId(payeeId, householdId);
    }
}
