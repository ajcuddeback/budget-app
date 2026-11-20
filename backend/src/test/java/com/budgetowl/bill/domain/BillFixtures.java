package com.budgetowl.bill.domain;

import com.budgetowl.common.Money;
import com.budgetowl.common.schedule.Recurrence;
import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.util.Currency;

/**
 * Bills and households with the identity the database would have given them.
 *
 * <p>Public because the projection's tests live in {@code ..service..} and build the same objects.
 * See {@code Ids} for why an unsaved entity needs an id planted in it.
 */
public final class BillFixtures {

    public static final Currency GBP = Currency.getInstance("GBP");

    private BillFixtures() {}

    public static Household household() {
        return Ids.withId(Household.named("Ada and Grace", GBP));
    }

    public static Bill bill(Household household, String amount, Recurrence recurrence) {
        return Ids.withId(Bill.of(household, "Rent", Money.of(amount, "GBP"), recurrence, GBP));
    }
}
