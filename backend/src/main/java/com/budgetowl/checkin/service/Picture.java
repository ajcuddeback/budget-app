package com.budgetowl.checkin.service;

import com.budgetowl.bill.domain.OccurrenceStatus;
import com.budgetowl.checkin.domain.ItemKind;
import com.budgetowl.checkin.persistence.CategoryRow;
import com.budgetowl.checkin.persistence.LedgerRow;
import com.budgetowl.checkin.service.CheckInViews.ChargeReason;
import com.budgetowl.checkin.service.CheckInViews.WeekView;
import com.budgetowl.common.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything one read of a week derived: the items (asked and pre-answered), the step 4 figures,
 * the choices offered for the surplus, the basis, and the view built from them.
 *
 * <p>Answering and completing are checked against a picture <b>derived inside the same request</b>,
 * never against one a client sent back: an item key resolves only among {@link #items()}, so a
 * foreign, deleted or out-of-window id is not found (threat model, attack 2).
 *
 * @param items every derived item by key, in step order
 * @param surplus {@code weekTarget − weekSpending}, or null with no target
 * @param offeredDebts and {@code offeredGoals}: the surplus may go only to one of these
 * @param attachTargets per {@code chg.} key, the {@code (bill, date)} pairs an attach may name
 * @param debtFreeMonth the plan's debt-free month now, for the snapshot
 */
record Picture(
        Map<String, Item> items,
        Money weekTarget,
        Money weekSpending,
        Money surplus,
        WeeklyFigure.Result figure,
        Set<UUID> offeredDebts,
        Set<UUID> offeredGoals,
        Map<String, Set<Target>> attachTargets,
        YearMonth debtFreeMonth,
        Map<UUID, UUID> debtByBill,
        Set<UUID> activeBills,
        Map<Target, OccurrenceStatus> occurrenceStatuses,
        Map<Target, Long> occurrenceVersions,
        Map<UUID, CategoryRow> categories,
        String basis,
        WeekView view) {

    Picture {
        items = Map.copyOf(items);
        offeredDebts = Set.copyOf(offeredDebts);
        offeredGoals = Set.copyOf(offeredGoals);
        attachTargets = Map.copyOf(attachTargets);
        debtByBill = Map.copyOf(debtByBill);
        activeBills = Set.copyOf(activeBills);
        occurrenceStatuses = Map.copyOf(occurrenceStatuses);
        occurrenceVersions = Map.copyOf(occurrenceVersions);
        categories = Map.copyOf(categories);
    }

    /** An occurrence row's version, or null for one only projected. */
    Long versionOf(Target target) {
        return occurrenceVersions.get(target);
    }

    /** Whether step 4 asks where a surplus goes. */
    boolean offersAllocation() {
        return items.containsKey(com.budgetowl.checkin.domain.ItemKeys.ALLOCATION);
    }

    /** A bill occurrence by its natural key. */
    record Target(UUID billId, LocalDate dueDate) {}

    /**
     * One derived item. Which fields are set depends on the kind.
     *
     * @param question false for a rule's pre-answer
     * @param row the transaction, for {@code odd.}, {@code cat.} and {@code chg.}
     * @param subjectVersion what an answer records as {@code subject_version}: the transaction's
     *     for steps 1 and 2, the occurrence row's for an overdue bill (null when projected)
     * @param target the occurrence a prepaid offer or a charge attaches to, server-chosen
     * @param targetVersion that occurrence's row version, null when projected
     * @param skips the later occurrences prepaid would skip
     * @param ruleCategoryId for {@code cat.}: the payee's rule, which pre-answers the row when its
     *     category is unarchived
     */
    record Item(
            String key,
            ItemKind kind,
            boolean question,
            LedgerRow row,
            UUID billId,
            String billName,
            LocalDate dueDate,
            Money expected,
            Long subjectVersion,
            Money usual,
            Integer multiple,
            List<LocalDate> skips,
            ChargeReason reason,
            Target target,
            Long targetVersion,
            Target suggested,
            UUID ruleCategoryId,
            boolean rulePreAnswers) {

        Item {
            skips = skips == null ? List.of() : List.copyOf(skips);
        }

        @Override
        public String toString() {
            return "Item[key=" + key + "]";
        }
    }

    @Override
    public String toString() {
        return "Picture[items=" + items.size() + "]";
    }
}
