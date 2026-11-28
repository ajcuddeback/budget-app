package com.budgetowl.payee.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class PayeeTest {

    @Test
    void aPayeeBelongsToItsHouseholdAndKeepsTheNameAsTyped() {
        Household household = household();

        Payee payee = Payee.named(household, "  Greenfield Market  ");

        assertThat(payee.householdId()).isEqualTo(household.id());
        // Trimmed, but not otherwise reshaped: the household's own spelling is what they see back.
        assertThat(payee.name()).isEqualTo("Greenfield Market");
    }

    @Test
    void aNameIsBounded() {
        Payee payee = Payee.named(household(), "Tesco");

        assertThatIllegalArgumentException().isThrownBy(() -> payee.rename("   "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> payee.rename("x".repeat(Payee.NAME_MAX_LENGTH + 1)));
        assertThatNullPointerException().isThrownBy(() -> payee.rename(null));

        payee.rename("x".repeat(Payee.NAME_MAX_LENGTH));
        assertThat(payee.name()).hasSize(Payee.NAME_MAX_LENGTH);
    }

    @Test
    void aPayeeNeedsAHousehold() {
        assertThatNullPointerException().isThrownBy(() -> Payee.named(null, "Tesco"));
    }

    private static Household household() {
        return Ids.withId(Household.named("Ada and Grace", Currency.getInstance("GBP")));
    }
}
