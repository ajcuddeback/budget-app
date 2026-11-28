package com.budgetowl.category.service;

import com.budgetowl.category.domain.CategoryKind;
import java.util.List;

/**
 * The starting set of categories a new household gets.
 *
 * <p><b>This list is duplicated in {@code V11__categories.sql}, deliberately and unavoidably.</b>
 * New households are seeded from here, because that is where a household is known; the instance
 * that was already running when categories shipped is seeded by the migration, because no
 * application code runs for a household that already exists. Neither can read the other, so the two
 * copies are kept honest by a test ({@code DefaultCategoriesTest}) that parses the migration and
 * asserts the lists match, rather than by a comment asking the next person to remember.
 *
 * <p>The vocabulary comes from the Money screen in the design system — {@code Groceries}, {@code
 * Transport}, {@code Income}, {@code Bills → Utilities}, {@code Shopping} — so the app a household
 * opens on day one matches the screens the product was designed around. It is intentionally short:
 * a list somebody has to prune before they can use it is worse than a list they add to.
 *
 * <p>Not localised, and that is a known gap rather than an oversight: ADR-0023 puts runtime locale
 * on the display layer, and these are user-editable <em>data</em>, not labels. A household whose
 * language is not English renames them, which is one of the two things the categories screen is
 * for. Seeding in the household's own language needs the locale at creation time and a translation
 * per category; it is worth doing and is written down in the feature doc rather than here.
 */
public final class DefaultCategories {

    /** One top-level category and its children. */
    public record Group(String name, CategoryKind kind, List<String> children) {}

    public static final List<Group> ALL =
            List.of(
                    new Group("Income", CategoryKind.INCOME, List.of("Salary", "Other income")),
                    new Group(
                            "Bills",
                            CategoryKind.EXPENSE,
                            List.of(
                                    "Utilities",
                                    "Rent or mortgage",
                                    "Phone and internet",
                                    "Insurance",
                                    "Subscriptions")),
                    new Group("Groceries", CategoryKind.EXPENSE, List.of()),
                    new Group("Transport", CategoryKind.EXPENSE, List.of("Fuel", "Transit", "Car")),
                    new Group("Eating Out", CategoryKind.EXPENSE, List.of()),
                    new Group("Shopping", CategoryKind.EXPENSE, List.of()),
                    new Group("Health", CategoryKind.EXPENSE, List.of("Pharmacy", "Medical")),
                    new Group("Entertainment", CategoryKind.EXPENSE, List.of()),
                    new Group("Home", CategoryKind.EXPENSE, List.of()),
                    new Group("Other", CategoryKind.EXPENSE, List.of()));

    private DefaultCategories() {}
}
