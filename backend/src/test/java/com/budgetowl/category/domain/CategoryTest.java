package com.budgetowl.category.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * The guards {@code Category} enforces on its own.
 *
 * <p>These are not duplicates of {@code CategoryIT}. That test proves the rules hold over HTTP
 * against a real database; this one proves they hold for a caller who never passes through a
 * controller — an importer, a future admin task — which is the whole reason the entity validates
 * rather than trusting the edge.
 */
class CategoryTest {

    @Test
    void aTopLevelCategoryKnowsWhereItLivesAndWhatItIsFor() {
        Household household = household();

        Category category = Category.topLevel(household, "Groceries", CategoryKind.EXPENSE);

        assertThat(category.householdId()).isEqualTo(household.id());
        assertThat(category.name()).isEqualTo("Groceries");
        assertThat(category.kind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(category.isTopLevel()).isTrue();
        assertThat(category.parentId()).isEmpty();
        assertThat(category.isArchived()).isFalse();
    }

    @Test
    void aChildTakesItsParentsHouseholdAndKindRatherThanAcceptingItsOwn() {
        Category parent = parent("Bills", CategoryKind.EXPENSE);

        Category child = Category.childOf(parent, "Utilities");

        // One source for the fact, so the two cannot disagree.
        assertThat(child.kind()).isEqualTo(CategoryKind.EXPENSE);
        assertThat(child.householdId()).isEqualTo(parent.householdId());
        assertThat(child.parentId()).contains(parent.id());
        assertThat(child.isTopLevel()).isFalse();
    }

    @Test
    void aChildOfAChildIsRefusedBecauseTheHierarchyIsTwoLevels() {
        Category child =
                Ids.withId(Category.childOf(parent("Bills", CategoryKind.EXPENSE), "Utilities"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Category.childOf(child, "Electricity"))
                .withMessageContaining("two levels");
    }

    @Test
    void aNameIsTrimmedAndBounded() {
        Category category = parent("Bills", CategoryKind.EXPENSE);

        category.rename("  Household bills  ");
        assertThat(category.name()).isEqualTo("Household bills");

        assertThatIllegalArgumentException().isThrownBy(() -> category.rename("   "));
        assertThatIllegalArgumentException().isThrownBy(() -> category.rename(""));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> category.rename("x".repeat(Category.NAME_MAX_LENGTH + 1)));
        assertThatNullPointerException().isThrownBy(() -> category.rename(null));

        // The boundary itself is allowed, not off by one.
        category.rename("x".repeat(Category.NAME_MAX_LENGTH));
        assertThat(category.name()).hasSize(Category.NAME_MAX_LENGTH);
    }

    @Test
    void movingUnderNullPromotesToTheTopLevel() {
        Category child = Category.childOf(parent("Bills", CategoryKind.EXPENSE), "Utilities");

        child.moveUnder(null, false);

        assertThat(child.isTopLevel()).isTrue();
    }

    @Test
    void aCategoryWithChildrenCannotItselfBecomeAChild() {
        Category hasChildren = parent("Bills", CategoryKind.EXPENSE);
        Category other = parent("Groceries", CategoryKind.EXPENSE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> hasChildren.moveUnder(other, true))
                .withMessageContaining("children");
    }

    @Test
    void aParentThatIsItselfAChildIsRefused() {
        Category child =
                Ids.withId(Category.childOf(parent("Bills", CategoryKind.EXPENSE), "Utilities"));
        Category moving = parent("Groceries", CategoryKind.EXPENSE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> moving.moveUnder(child, false))
                .withMessageContaining("two levels");
    }

    @Test
    void aCategoryCannotBeItsOwnParent() {
        Category category = parent("Bills", CategoryKind.EXPENSE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> category.moveUnder(category, false))
                .withMessageContaining("own parent");
    }

    @Test
    void aParentFromAnotherHouseholdIsRefused() {
        Category mine = parent("Groceries", CategoryKind.EXPENSE);
        Category theirs = parent("Bills", CategoryKind.EXPENSE);

        // Different households: the scoping rule holds in the entity as well as in the query.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> mine.moveUnder(theirs, false))
                .withMessageContaining("different household");
    }

    @Test
    void aParentOfAnotherKindIsRefused() {
        Household household = household();
        Category expense =
                Ids.withId(Category.topLevel(household, "Groceries", CategoryKind.EXPENSE));
        Category income = Ids.withId(Category.topLevel(household, "Income", CategoryKind.INCOME));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> expense.moveUnder(income, false))
                .withMessageContaining("kind");
    }

    @Test
    void archivingIsReversibleAndIdempotent() {
        Category category = parent("Bills", CategoryKind.EXPENSE);

        category.archive();
        category.archive();
        assertThat(category.isArchived()).isTrue();

        category.unarchive();
        assertThat(category.isArchived()).isFalse();
    }

    @Test
    void aCategoryNeedsAHouseholdAndAKind() {
        assertThatNullPointerException()
                .isThrownBy(() -> Category.topLevel(null, "Bills", CategoryKind.EXPENSE));
        assertThatNullPointerException()
                .isThrownBy(() -> Category.topLevel(household(), "Bills", null));
        assertThatNullPointerException().isThrownBy(() -> Category.childOf(null, "Utilities"));
    }

    private static Category parent(String name, CategoryKind kind) {
        return Ids.withId(Category.topLevel(household(), name, kind));
    }

    private static Household household() {
        return Ids.withId(Household.named("Ada and Grace", Currency.getInstance("GBP")));
    }
}
