package com.budgetowl.envelope.domain;

import static com.budgetowl.envelope.domain.EnvelopeTest.envelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.budgetowl.common.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvelopeEntryTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final LocalDate DATE = LocalDate.of(2026, 8, 3);

    @Test
    void aTypedEntryTakesItsHouseholdAndEnvelopeFromTheEnvelope() {
        Envelope envelope = envelope("400.00");

        EnvelopeEntry entry =
                EnvelopeEntry.typedInto(envelope, DATE, Money.of("86.40", "GBP"), GBP);

        // Never from a parameter a caller could supply: an id is not evidence of ownership.
        assertThat(entry.householdId()).isEqualTo(envelope.householdId());
        assertThat(entry.envelopeId()).isEqualTo(envelope.id());
        assertThat(entry.amount()).isEqualByComparingTo("86.40");
        assertThat(entry.source()).isEqualTo(EntrySource.MANUAL);
        assertThat(entry.transactionId()).isEmpty();
        assertThat(entry.payeeId()).isEmpty();
        assertThat(entry.note()).isEmpty();
    }

    @Test
    void anAttachedEntryIsSyncedAndRemembersItsTransaction() {
        Envelope envelope = envelope("400.00");
        UUID transaction = UUID.randomUUID();

        EnvelopeEntry entry =
                EnvelopeEntry.attachedTo(
                        envelope, transaction, DATE, Money.of("86.40", "GBP"), GBP);

        assertThat(entry.source()).isEqualTo(EntrySource.SYNCED);
        assertThat(entry.transactionId()).contains(transaction);
    }

    @Test
    void anEntryMustBePositiveBecauseItRecordsHowMuchWasUsed() {
        Envelope envelope = envelope("400.00");

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> EnvelopeEntry.typedInto(envelope, DATE, Money.of("0.00", "GBP"), GBP))
                .withMessageContaining("positive");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                EnvelopeEntry.typedInto(
                                        envelope, DATE, Money.of("-5.00", "GBP"), GBP));
    }

    @Test
    void anEntryInAnotherCurrencyIsRefused() {
        Envelope envelope = envelope("400.00");

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                EnvelopeEntry.typedInto(
                                        envelope, DATE, Money.of("10.00", "EUR"), GBP))
                .withMessageContaining("base currency");
    }

    @Test
    void aNoteIsTrimmedBoundedAndClearedByBlank() {
        EnvelopeEntry entry = typed();

        entry.annotate("  big shop  ");
        assertThat(entry.note()).contains("big shop");

        entry.annotate("   ");
        assertThat(entry.note()).isEmpty();

        entry.annotate(null);
        assertThat(entry.note()).isEmpty();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> entry.annotate("x".repeat(EnvelopeEntry.NOTE_MAX_LENGTH + 1)));
    }

    @Test
    void thePayeeTheDateAndTheAmountCanAllBeCorrected() {
        EnvelopeEntry entry = typed();
        UUID payee = UUID.randomUUID();

        entry.payTo(payee);
        assertThat(entry.payeeId()).contains(payee);
        entry.payTo(null);
        assertThat(entry.payeeId()).isEmpty();

        entry.on(LocalDate.of(2026, 8, 9));
        assertThat(entry.date()).isEqualTo(LocalDate.of(2026, 8, 9));

        entry.correctAmount(Money.of("96.40", "GBP"), GBP);
        assertThat(entry.amount()).isEqualByComparingTo("96.40");

        assertThatNullPointerException().isThrownBy(() -> entry.on(null));
        assertThatNullPointerException().isThrownBy(() -> entry.correctAmount(null, GBP));
    }

    @Test
    void anEntryNeedsAnEnvelopeADateAndAnAmount() {
        assertThatNullPointerException()
                .isThrownBy(
                        () -> EnvelopeEntry.typedInto(null, DATE, Money.of("1.00", "GBP"), GBP));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                EnvelopeEntry.typedInto(
                                        envelope("400.00"), null, Money.of("1.00", "GBP"), GBP));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                EnvelopeEntry.attachedTo(
                                        envelope("400.00"),
                                        null,
                                        DATE,
                                        Money.of("1.00", "GBP"),
                                        GBP));
    }

    private static EnvelopeEntry typed() {
        return EnvelopeEntry.typedInto(envelope("400.00"), DATE, Money.of("86.40", "GBP"), GBP);
    }
}
