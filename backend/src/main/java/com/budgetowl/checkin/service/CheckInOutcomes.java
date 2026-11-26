package com.budgetowl.checkin.service;

import com.budgetowl.checkin.domain.CheckIn;
import com.budgetowl.checkin.domain.CheckInAnswer;
import com.budgetowl.checkin.persistence.CategoryRow;
import com.budgetowl.checkin.persistence.CheckInLedgerRepository;
import com.budgetowl.checkin.persistence.IdName;
import com.budgetowl.checkin.persistence.LedgerRow;
import com.budgetowl.checkin.service.CheckInViews.OutcomeView;
import com.budgetowl.checkin.service.CheckInViews.RuleCounts;
import com.budgetowl.checkin.service.CheckInViews.SnapshotView;
import com.budgetowl.checkin.service.CheckInViews.WeekView;
import com.budgetowl.common.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A completed check-in as it is read back: <b>its snapshot, not a recomputation</b>, and what each
 * answer wrote, with the names a summary needs. Every lookup is household-scoped and batched, one
 * query per kind of name.
 */
@Component
public class CheckInOutcomes {

    private final CheckInLedgerRepository ledger;
    private final CheckInSources sources;

    public CheckInOutcomes(CheckInLedgerRepository ledger, CheckInSources sources) {
        this.ledger = ledger;
        this.sources = sources;
    }

    static SnapshotView snapshot(CheckIn checkIn) {
        Currency currency = checkIn.currency().orElseThrow();
        return new SnapshotView(
                checkIn.weekTarget().map(amount -> Money.of(amount, currency)).orElse(null),
                checkIn.weekSpending().map(amount -> Money.of(amount, currency)).orElse(null),
                checkIn.rolledOver().map(amount -> Money.of(amount, currency)).orElse(null),
                checkIn.nextWeekTarget().map(amount -> Money.of(amount, currency)).orElse(null),
                checkIn.debtFreeMonth().orElse(null));
    }

    @Transactional(readOnly = true)
    public WeekView completed(UUID actorId, CheckIn checkIn, List<CheckInAnswer> answers) {
        UUID householdId = checkIn.householdId();
        Currency currency = checkIn.currency().orElseThrow();
        Set<UUID> transactions = new HashSet<>();
        Set<UUID> bills = new HashSet<>();
        Set<UUID> goals = new HashSet<>();
        Set<UUID> debts = new HashSet<>();
        for (CheckInAnswer answer : answers) {
            answer.transactionId().ifPresent(transactions::add);
            answer.billId().ifPresent(bills::add);
            answer.goalId().ifPresent(goals::add);
            answer.debtId().ifPresent(debts::add);
        }
        Map<UUID, String> payees = new HashMap<>();
        if (!transactions.isEmpty()) {
            for (LedgerRow row : ledger.rows(householdId, transactions)) {
                payees.put(row.id(), row.payeeName());
            }
        }
        Map<UUID, String> billNames =
                names(bills.isEmpty() ? List.of() : ledger.billNames(householdId, bills));
        Map<UUID, String> goalNames =
                names(goals.isEmpty() ? List.of() : ledger.goalNames(householdId, goals));
        Map<UUID, String> debtNames =
                names(debts.isEmpty() ? List.of() : ledger.debtNames(householdId, debts));
        Map<UUID, String> categoryNames = new HashMap<>();
        for (CategoryRow category : ledger.categories(householdId)) {
            categoryNames.put(category.id(), category.name());
        }

        List<OutcomeView> outcome = new ArrayList<>(answers.size());
        int categorizedByRule = 0;
        int chargesByRule = 0;
        for (CheckInAnswer answer : answers) {
            if (answer.isByRule()) {
                if (answer.transactionId().isPresent() && answer.categoryId().isPresent()) {
                    categorizedByRule++;
                } else {
                    chargesByRule++;
                }
            }
            outcome.add(
                    new OutcomeView(
                            answer.itemKind(),
                            answer.choice(),
                            answer.isByRule(),
                            answer.transactionId().orElse(null),
                            answer.transactionId().map(payees::get).orElse(null),
                            answer.categoryId().orElse(null),
                            answer.categoryId().map(categoryNames::get).orElse(null),
                            answer.billId().orElse(null),
                            answer.billId().map(billNames::get).orElse(null),
                            answer.dueDate().orElse(null),
                            answer.goalId().orElse(null),
                            answer.goalId().map(goalNames::get).orElse(null),
                            answer.debtId().orElse(null),
                            answer.debtId().map(debtNames::get).orElse(null),
                            answer.amount().map(amount -> Money.of(amount, currency)).orElse(null),
                            answer.remember(),
                            answer.recordDebtPayment()));
        }
        Map<UUID, String> members = sources.memberNames(actorId);
        return new WeekView(
                checkIn.week(),
                checkIn.windowStart(),
                checkIn.status().name(),
                checkIn.version(),
                null,
                CheckInPictures.person(checkIn.startedBy().orElse(null), members),
                checkIn.completedAt().orElse(null),
                CheckInPictures.person(checkIn.completedBy().orElse(null), members),
                currency.getCurrencyCode(),
                0,
                new RuleCounts(categorizedByRule, chargesByRule),
                List.of(),
                snapshot(checkIn),
                outcome);
    }

    private static Map<UUID, String> names(List<IdName> found) {
        Map<UUID, String> names = new HashMap<>();
        for (IdName entry : found) {
            names.put(entry.id(), entry.name());
        }
        return names;
    }
}
