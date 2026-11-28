package com.budgetowl.checkin.service;

import com.budgetowl.checkin.domain.PayeeRule;
import com.budgetowl.checkin.persistence.CategoryRow;
import com.budgetowl.checkin.persistence.CheckInLedgerRepository;
import com.budgetowl.checkin.persistence.IdName;
import com.budgetowl.checkin.persistence.PayeeRuleRepository;
import com.budgetowl.checkin.service.CheckInViews.Page;
import com.budgetowl.checkin.service.CheckInViews.PayeeRuleView;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.common.web.PageResponse;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payee rules: listed and removed from the categories page, and <b>written only by completing a
 * check-in</b> (ADR-0041). There is no create or edit endpoint; a rule is replaced by answering
 * differently.
 *
 * <p>The role is checked before the rule is looked up, so a {@code VIEWER} sending an unknown id
 * gets {@code 403} and learns nothing about which exist.
 */
@Service
public class PayeeRuleService {

    /**
     * A cost control (threat model, attack 11): every check-in reads the window's payees' rules.
     */
    public static final int MAXIMUM_PER_HOUSEHOLD = 1_000;

    private static final Logger log = LoggerFactory.getLogger(PayeeRuleService.class);

    private final MembershipService memberships;
    private final PayeeRuleRepository rules;
    private final CheckInLedgerRepository ledger;

    public PayeeRuleService(
            MembershipService memberships,
            PayeeRuleRepository rules,
            CheckInLedgerRepository ledger) {
        this.memberships = memberships;
        this.rules = rules;
        this.ledger = ledger;
    }

    /** By payee name. A {@code VIEWER} may read. */
    @Transactional(readOnly = true)
    public Page<PayeeRuleView> list(UUID actorId, int page, int size) {
        UUID householdId = memberships.requireMembership(actorId).household().id();
        org.springframework.data.domain.Page<PayeeRule> found =
                rules.findPage(householdId, PageRequest.of(Math.max(page, 0), clamp(size)));
        Set<UUID> payees = new HashSet<>();
        for (PayeeRule rule : found.getContent()) {
            payees.add(rule.payeeId());
        }
        Map<UUID, String> payeeNames = new HashMap<>();
        if (!payees.isEmpty()) {
            for (IdName payee : ledger.payeeNames(householdId, payees)) {
                payeeNames.put(payee.id(), payee.name());
            }
        }
        Map<UUID, CategoryRow> categories = new HashMap<>();
        for (CategoryRow category : ledger.categories(householdId)) {
            categories.put(category.id(), category);
        }
        List<PayeeRuleView> views = new ArrayList<>(found.getNumberOfElements());
        for (PayeeRule rule : found.getContent()) {
            CategoryRow category = categories.get(rule.categoryId());
            views.add(
                    new PayeeRuleView(
                            rule.id(),
                            rule.payeeId(),
                            payeeNames.get(rule.payeeId()),
                            rule.categoryId(),
                            category == null ? null : category.name(),
                            category != null && category.archived()));
        }
        return new Page<>(
                views,
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /**
     * @throws com.budgetowl.common.ForbiddenException for a {@code VIEWER}, before any lookup
     * @throws NotFoundException for a rule that is not this household's
     */
    @Transactional
    public void remove(UUID actorId, UUID ruleId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        PayeeRule rule =
                rules.findByIdAndHouseholdId(ruleId, householdId)
                        .orElseThrow(() -> new NotFoundException("no such payee rule"));
        rules.delete(rule);
        log.info(
                "payee rule removed householdId={} ruleId={} byUserId={}",
                householdId,
                ruleId,
                actorId);
    }

    /**
     * "Always file this payee here", from a check-in being completed: one rule per payee, a
     * different answer replacing it.
     *
     * <p>Called only by {@code CheckInCompletion}, with ids it has already resolved through the
     * household; the composite foreign keys are the backstop.
     *
     * @throws ConflictException {@code payee-rule-limit-reached} for a new rule past the cap
     */
    @Transactional
    public void remember(UUID actorId, UUID payeeId, UUID categoryId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        List<PayeeRule> existing = rules.findForPayees(householdId, List.of(payeeId));
        PayeeRule rule;
        if (existing.isEmpty()) {
            if (rules.countByHouseholdId(householdId) >= MAXIMUM_PER_HOUSEHOLD) {
                throw new ConflictException(
                        ErrorCode.PAYEE_RULE_LIMIT_REACHED,
                        "a household is limited to " + MAXIMUM_PER_HOUSEHOLD + " payee rules");
            }
            rule = PayeeRule.of(householdId, payeeId, categoryId, actorId);
        } else {
            rule = existing.getFirst();
            rule.fileUnder(categoryId, actorId);
        }
        try {
            rules.saveAndFlush(rule);
        } catch (DataIntegrityViolationException violation) {
            throw new ConflictException(
                    ErrorCode.CONFLICT, "a payee rule was written a moment ago");
        }
        log.info(
                "payee rule remembered householdId={} ruleId={} byUserId={}",
                householdId,
                rule.id(),
                actorId);
    }

    private static int clamp(int size) {
        return Math.clamp(
                size <= 0 ? PageResponse.DEFAULT_SIZE : size, 1, PageResponse.MAXIMUM_SIZE);
    }
}
