package com.budgetowl.checkin.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.checkin.domain.ChargeRule;
import com.budgetowl.checkin.persistence.ChargeRuleRepository;
import com.budgetowl.checkin.persistence.CheckInLedgerRepository;
import com.budgetowl.checkin.persistence.IdName;
import com.budgetowl.checkin.service.CheckInViews.ChargeRuleView;
import com.budgetowl.checkin.service.CheckInViews.Page;
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
 * Charge rules: listed and removed (<em>Unmap</em>) on Bills &amp; Income, and <b>written only by
 * completing a check-in</b> (ADR-0041). Nothing matches at import or sync.
 */
@Service
public class ChargeRuleService {

    /** A cost control: every check-in reads every rule. Matches the bill cap. */
    public static final int MAXIMUM_PER_HOUSEHOLD = 200;

    private static final Logger log = LoggerFactory.getLogger(ChargeRuleService.class);

    private final MembershipService memberships;
    private final ChargeRuleRepository rules;
    private final CheckInLedgerRepository ledger;
    private final AccountRepository accounts;

    public ChargeRuleService(
            MembershipService memberships,
            ChargeRuleRepository rules,
            CheckInLedgerRepository ledger,
            AccountRepository accounts) {
        this.memberships = memberships;
        this.rules = rules;
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /** By bill name. A {@code VIEWER} may read. */
    @Transactional(readOnly = true)
    public Page<ChargeRuleView> list(UUID actorId, int page, int size) {
        UUID householdId = memberships.requireMembership(actorId).household().id();
        org.springframework.data.domain.Page<ChargeRule> found =
                rules.findPage(householdId, PageRequest.of(Math.max(page, 0), clamp(size)));
        return new Page<>(
                views(householdId, found.getContent()),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /**
     * Every rule for these bills, for the Bills &amp; Income rows' <em>"Mapped · COMCAST XFINITY ·
     * Chase •4412"</em> without a request per bill. A {@code VIEWER} may read.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<ChargeRuleView>> forBills(UUID actorId, Set<UUID> billIds) {
        UUID householdId = memberships.requireMembership(actorId).household().id();
        Map<UUID, List<ChargeRuleView>> byBill = new HashMap<>();
        if (billIds.isEmpty()) {
            return byBill;
        }
        for (ChargeRuleView view : views(householdId, rules.findForBills(householdId, billIds))) {
            byBill.computeIfAbsent(view.billId(), id -> new ArrayList<>()).add(view);
        }
        return byBill;
    }

    /**
     * @throws com.budgetowl.common.ForbiddenException for a {@code VIEWER}, before any lookup
     * @throws NotFoundException for a rule that is not this household's
     */
    @Transactional
    public void remove(UUID actorId, UUID ruleId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        ChargeRule rule =
                rules.findByIdAndHouseholdId(ruleId, householdId)
                        .orElseThrow(() -> new NotFoundException("no such charge rule"));
        rules.delete(rule);
        log.info(
                "charge rule removed householdId={} ruleId={} byUserId={}",
                householdId,
                ruleId,
                actorId);
    }

    /**
     * "This charge pays that bill", from a check-in being completed. One rule per {@code (payee,
     * account)}; a different mapping replaces it. Called only by {@code CheckInCompletion}, with
     * ids already resolved through the household.
     *
     * @throws ConflictException {@code charge-rule-limit-reached} for a new rule past the cap
     */
    @Transactional
    public void remember(UUID actorId, UUID payeeId, UUID accountId, UUID billId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        ChargeRule rule = null;
        for (ChargeRule existing : rules.findAllForHousehold(householdId)) {
            if (existing.payeeId().equals(payeeId) && existing.accountId().equals(accountId)) {
                rule = existing;
                break;
            }
        }
        if (rule == null) {
            if (rules.countByHouseholdId(householdId) >= MAXIMUM_PER_HOUSEHOLD) {
                throw new ConflictException(
                        ErrorCode.CHARGE_RULE_LIMIT_REACHED,
                        "a household is limited to " + MAXIMUM_PER_HOUSEHOLD + " charge rules");
            }
            rule = ChargeRule.of(householdId, payeeId, accountId, billId, actorId);
        } else {
            rule.mapTo(billId, actorId);
        }
        try {
            rules.saveAndFlush(rule);
        } catch (DataIntegrityViolationException violation) {
            throw new ConflictException(
                    ErrorCode.CONFLICT, "a charge rule was written a moment ago");
        }
        log.info(
                "charge rule remembered householdId={} ruleId={} byUserId={}",
                householdId,
                rule.id(),
                actorId);
    }

    private List<ChargeRuleView> views(UUID householdId, List<ChargeRule> found) {
        Set<UUID> bills = new HashSet<>();
        Set<UUID> payees = new HashSet<>();
        Set<UUID> accountIds = new HashSet<>();
        for (ChargeRule rule : found) {
            bills.add(rule.billId());
            payees.add(rule.payeeId());
            accountIds.add(rule.accountId());
        }
        Map<UUID, String> billNames = new HashMap<>();
        Map<UUID, String> payeeNames = new HashMap<>();
        Map<UUID, Account> accountsById = new HashMap<>();
        if (!found.isEmpty()) {
            for (IdName bill : ledger.billNames(householdId, bills)) {
                billNames.put(bill.id(), bill.name());
            }
            for (IdName payee : ledger.payeeNames(householdId, payees)) {
                payeeNames.put(payee.id(), payee.name());
            }
            for (Account account : accounts.findAllByHouseholdIdAndIdIn(householdId, accountIds)) {
                accountsById.put(account.id(), account);
            }
        }
        List<ChargeRuleView> views = new ArrayList<>(found.size());
        for (ChargeRule rule : found) {
            Account account = accountsById.get(rule.accountId());
            views.add(
                    new ChargeRuleView(
                            rule.id(),
                            rule.billId(),
                            billNames.get(rule.billId()),
                            rule.payeeId(),
                            payeeNames.get(rule.payeeId()),
                            rule.accountId(),
                            account == null ? null : account.name(),
                            account == null ? null : account.lastFour().orElse(null)));
        }
        return views;
    }

    private static int clamp(int size) {
        return Math.clamp(
                size <= 0 ? PageResponse.DEFAULT_SIZE : size, 1, PageResponse.MAXIMUM_SIZE);
    }
}
