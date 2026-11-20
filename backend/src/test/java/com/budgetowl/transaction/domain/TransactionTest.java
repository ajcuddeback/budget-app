package com.budgetowl.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.domain.AccountType;
import com.budgetowl.common.Money;
import com.budgetowl.domain.Ids;
import com.budgetowl.household.domain.Household;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The guards {@code Transaction} enforces on its own, for a caller that never passes through a
 * controller.
 */
class TransactionTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 11);

    @Test
    void aTransactionTakesItsHouseholdAndAccountFromTheAccount() {
        Account account = account();

        Transaction transaction =
                Transaction.of(account, DATE, Money.of("-42.50", "GBP"), TransactionStatus.CLEARED);

        // Never from a parameter a caller could supply: an id is not evidence of ownership.
        assertThat(transaction.householdId()).isEqualTo(account.householdId());
        assertThat(transaction.accountId()).isEqualTo(account.id());
        assertThat(transaction.date()).isEqualTo(DATE);
        assertThat(transaction.amount()).isEqualByComparingTo("-42.50");
        assertThat(transaction.status()).isEqualTo(TransactionStatus.CLEARED);
        assertThat(transaction.categoryId()).isEmpty();
        assertThat(transaction.payeeId()).isEmpty();
        assertThat(transaction.note()).isEmpty();
        assertThat(transaction.isTransfer()).isFalse();
    }

    @Test
    void anAmountInAnotherCurrencyIsRefusedBecauseTheAccountDenominatesIt() {
        Account account = account();

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                Transaction.of(
                                        account,
                                        DATE,
                                        Money.of("-42.50", "EUR"),
                                        TransactionStatus.CLEARED))
                .withMessageContaining("account's currency");
    }

    @Test
    void aZeroAmountIsRefusedBecauseItMovesNoMoney() {
        Account account = account();

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                Transaction.of(
                                        account,
                                        DATE,
                                        Money.of("0.00", "GBP"),
                                        TransactionStatus.CLEARED))
                .withMessageContaining("zero");
    }

    @Test
    void aSignedAmountCarriesTheDirectionWithNoSeparateFlag() {
        Transaction out = transaction("-42.50");
        Transaction in = transaction("3200.00");

        assertThat(out.amount().signum()).isNegative();
        assertThat(in.amount().signum()).isPositive();
    }

    @Test
    void correctingAnAmountKeepsItInTheAccountsCurrency() {
        Transaction transaction = transaction("-42.50");

        transaction.correctAmount(Money.of("-45.00", "GBP"), GBP);
        assertThat(transaction.amount()).isEqualByComparingTo("-45.00");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> transaction.correctAmount(Money.of("-45.00", "EUR"), GBP));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> transaction.correctAmount(Money.of("0.00", "GBP"), GBP));
        assertThatNullPointerException().isThrownBy(() -> transaction.correctAmount(null, GBP));
    }

    @Test
    void aNoteIsTrimmedBoundedAndClearedByBlank() {
        Transaction transaction = transaction("-42.50");

        transaction.annotate("  weekly shop  ");
        assertThat(transaction.note()).contains("weekly shop");

        // Blank is not an empty note: it is a cleared one. ck_transactions_note refuses the empty
        // string, so storing it would be a constraint violation at flush rather than here.
        transaction.annotate("   ");
        assertThat(transaction.note()).isEmpty();

        transaction.annotate(null);
        assertThat(transaction.note()).isEmpty();

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> transaction.annotate("x".repeat(Transaction.NOTE_MAX_LENGTH + 1)));

        transaction.annotate("x".repeat(Transaction.NOTE_MAX_LENGTH));
        assertThat(transaction.note())
                .hasValueSatisfying(note -> assertThat(note).hasSize(Transaction.NOTE_MAX_LENGTH));
    }

    @Test
    void aCategoryAndAPayeeAreOptionalAndClearable() {
        Transaction transaction = transaction("-42.50");
        UUID categoryId = UUID.randomUUID();
        UUID payeeId = UUID.randomUUID();

        transaction.fileUnder(categoryId);
        transaction.payTo(payeeId);
        assertThat(transaction.categoryId()).contains(categoryId);
        assertThat(transaction.payeeId()).contains(payeeId);

        transaction.fileUnder(null);
        transaction.payTo(null);
        // Back to "Needs a category", which is a state somebody can choose and not only arrive at.
        assertThat(transaction.categoryId()).isEmpty();
        assertThat(transaction.payeeId()).isEmpty();
    }

    @Test
    void linkingToATransferMakesItATransfer() {
        Transaction transaction = transaction("-500.00");
        UUID group = UUID.randomUUID();

        transaction.linkToTransfer(group);

        assertThat(transaction.isTransfer()).isTrue();
        assertThat(transaction.transferGroupId()).contains(group);
    }

    @Test
    void anImportedTransactionRemembersItsSourceIdentifier() {
        Transaction transaction = transaction("-42.50");

        transaction.importedAs("ofx-12345");

        assertThat(transaction.externalId()).contains("ofx-12345");
    }

    @Test
    void theStatusAndDateCanBeCorrectedButNeverToNull() {
        Transaction transaction = transaction("-42.50");

        transaction.markAs(TransactionStatus.PENDING);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);

        transaction.on(LocalDate.of(2026, 12, 1));
        assertThat(transaction.date()).isEqualTo(LocalDate.of(2026, 12, 1));

        assertThatNullPointerException().isThrownBy(() -> transaction.markAs(null));
        assertThatNullPointerException().isThrownBy(() -> transaction.on(null));
    }

    @Test
    void aTransactionNeedsAnAccountADateAndAStatus() {
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                Transaction.of(
                                        null,
                                        DATE,
                                        Money.of("-1.00", "GBP"),
                                        TransactionStatus.CLEARED));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                Transaction.of(
                                        account(),
                                        null,
                                        Money.of("-1.00", "GBP"),
                                        TransactionStatus.CLEARED));
        assertThatNullPointerException()
                .isThrownBy(() -> Transaction.of(account(), DATE, Money.of("-1.00", "GBP"), null));
    }

    private static Transaction transaction(String amount) {
        return Transaction.of(account(), DATE, Money.of(amount, "GBP"), TransactionStatus.CLEARED);
    }

    private static Account account() {
        Household household = Ids.withId(Household.named("Ada and Grace", GBP));
        return Ids.withId(
                Account.of(household, "Current", AccountType.CHECKING, Money.of("100.00", "GBP")));
    }
}
