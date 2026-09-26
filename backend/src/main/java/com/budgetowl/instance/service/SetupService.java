package com.budgetowl.instance.service;

import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.service.UserRegistrationService;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.InvalidRequestException;
import com.budgetowl.household.domain.Household;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.domain.HouseholdRole;
import com.budgetowl.household.persistence.HouseholdMemberRepository;
import com.budgetowl.household.persistence.HouseholdRepository;
import com.budgetowl.instance.persistence.InstanceSettingsRepository;
import java.time.Clock;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * First run: one user, one household, one transaction.
 *
 * <p>This endpoint is the instance's most exposed moment. A self-hosted box may be reachable from
 * the internet with nobody watching, and a stranger who finds a <em>fresh-looking</em> one and
 * POSTs here first becomes its administrator — with the household's financial record behind it.
 *
 * <p>So the check is not "are there any users yet?". That question cannot be answered safely:
 * between reading it and inserting, a second caller does the same and both proceed. The claim is a
 * <b>conditional UPDATE of a single row</b> ({@code
 * InstanceSettingsRepository.claimFirstUserSetup}), taken first and in the same transaction as the
 * insert. The second caller blocks on the row lock, re-evaluates {@code setup_completed_at IS NULL}
 * against the committed tuple, matches nothing and is refused. Two further schema-level backstops
 * stand behind it: {@code uq_households_singleton} and {@code uq_users_single_instance_admin}.
 *
 * <p>The household is created and given its {@code OWNER} <em>here</em>, not in a follow-up
 * request. It has to be: {@code ck_households_at_least_one_owner} re-reads the owner count at
 * {@code COMMIT} and aborts a household that has none.
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private final InstanceSettingsRepository settings;
    private final HouseholdRepository households;
    private final HouseholdMemberRepository members;
    private final UserRegistrationService registration;
    private final Clock clock;

    public SetupService(
            InstanceSettingsRepository settings,
            HouseholdRepository households,
            HouseholdMemberRepository members,
            UserRegistrationService registration,
            Clock clock) {
        this.settings = settings;
        this.households = households;
        this.members = members;
        this.registration = registration;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SetupStatus status() {
        return settings.findCurrent()
                .map(
                        current ->
                                new SetupStatus(
                                        current.isSetupComplete(),
                                        current.isRegistrationOpen(),
                                        current.isPasswordLoginEnabled(),
                                        current.isOidcEnabled()))
                .orElseGet(() -> new SetupStatus(false, false, true, false));
    }

    /**
     * @throws ConflictException the instant setup has been claimed, by anyone, ever
     */
    @Transactional
    public FirstUserCreated createFirstUser(
            String email,
            String displayName,
            String rawPassword,
            String householdName,
            String baseCurrency) {
        if (settings.claimFirstUserSetup(clock.instant()) == 0) {
            throw new ConflictException(
                    ErrorCode.SETUP_ALREADY_COMPLETE, "setup has already been completed");
        }

        Currency currency = currency(baseCurrency);
        UserAccount owner = registration.register(email, displayName, rawPassword, true);
        Household household = households.save(Household.named(householdName.strip(), currency));
        members.save(HouseholdMember.of(household, owner, HouseholdRole.OWNER));

        log.info("instance setup completed userId={} householdId={}", owner.id(), household.id());
        return new FirstUserCreated(
                owner.id(),
                owner.email(),
                owner.displayName(),
                household.id(),
                household.name(),
                household.baseCurrency().getCurrencyCode());
    }

    private static Currency currency(String code) {
        try {
            return Currency.getInstance(code.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "unknown currency",
                    Map.of("field", "baseCurrency"));
        }
    }
}
