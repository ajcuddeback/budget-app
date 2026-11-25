package com.budgetowl.imports.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * Re-importing a file adds nothing, and two genuinely identical rows in one file both survive
 * (docs/features/file-import.md rule 9 and its testing notes).
 *
 * <p>Modelled on the multiset the unique index sees: a row is written exactly when its {@code
 * external_id} is not already in the ledger. The generator deliberately produces many identical
 * rows — a small set of dates, amounts and payees — because the ordinal is what is under test.
 */
class RowIdentityPropertyTest {

    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000a001");

    @Provide
    Arbitrary<List<ReadRow>> statements() {
        Arbitrary<ReadRow> row =
                Combinators.combine(
                                Arbitraries.integers().between(1, 4),
                                Arbitraries.of("-3.5000", "-3.50", "12.00", "-12.0000"),
                                Arbitraries.of("BLUE BOTTLE", "blue bottle", "CORNER SHOP", ""))
                        .as(
                                (day, amount, payee) ->
                                        new ReadRow(
                                                0,
                                                ReadRow.Kind.READY,
                                                LocalDate.of(2026, 8, day),
                                                new BigDecimal(amount),
                                                payee.isEmpty() ? null : payee,
                                                false,
                                                null,
                                                false,
                                                payee.toLowerCase(java.util.Locale.ROOT),
                                                null,
                                                null,
                                                null,
                                                null));
        return row.list().ofMinSize(0).ofMaxSize(40);
    }

    @Property(tries = 500)
    void importingAnyPermutationOfAnImportedFileAddsNothing(
            @ForAll("statements") List<ReadRow> rows, @ForAll long seed) {
        Map<String, Integer> ledger = new HashMap<>();
        int first = importInto(ledger, rows, ImportFormat.QIF);

        List<ReadRow> shuffled = new ArrayList<>(rows);
        Collections.shuffle(shuffled, new Random(seed));
        int second = importInto(ledger, shuffled, ImportFormat.QIF);

        assertThat(first).as("every row of a first import is written").isEqualTo(rows.size());
        assertThat(second).as("a re-import, in any order, writes nothing").isZero();
    }

    @Property(tries = 500)
    void importingAPrefixThenTheWholeFileAddsExactlyTheSuffix(
            @ForAll("statements") List<ReadRow> rows,
            @ForAll @IntRange(min = 0, max = 40) int cut) {
        int split = Math.min(cut, rows.size());
        Map<String, Integer> ledger = new HashMap<>();

        importInto(ledger, rows.subList(0, split), ImportFormat.QIF);
        int added = importInto(ledger, rows, ImportFormat.QIF);

        assertThat(added).isEqualTo(rows.size() - split);
    }

    @Test
    void twoIdenticalCoffeesBothImportAndAThirdArrivesOnlyWhenItIsNew() {
        ReadRow coffee = coffee();
        Map<String, Integer> ledger = new HashMap<>();

        assertThat(importInto(ledger, List.of(coffee, coffee), ImportFormat.QIF)).isEqualTo(2);
        assertThat(importInto(ledger, List.of(coffee, coffee), ImportFormat.QIF)).isZero();
        assertThat(importInto(ledger, List.of(coffee, coffee, coffee), ImportFormat.QIF))
                .isEqualTo(1);
    }

    @Test
    void theAccountIsInsideTheHashSoTwoAccountsShareNoIdentity() {
        ReadRow withFitId = withIdentifier(coffee(), "20260801001");

        String one =
                RowIdentity.of(List.of(withFitId), ACCOUNT, ImportFormat.OFX).externalIds().get(0);
        String other =
                RowIdentity.of(List.of(withFitId), UUID.randomUUID(), ImportFormat.OFX)
                        .externalIds()
                        .get(0);

        assertThat(one).startsWith("ofx1:").hasSize(69).doesNotContain("20260801001");
        assertThat(other).isNotEqualTo(one);
    }

    @Test
    void aFitIdTheBankRepeatsFallsBackToFingerprints() {
        ReadRow a = withIdentifier(coffee(), "SAME");
        ReadRow b =
                withIdentifier(
                        new ReadRow(
                                0,
                                ReadRow.Kind.READY,
                                LocalDate.of(2026, 8, 2),
                                new BigDecimal("-9.00"),
                                "OTHER",
                                false,
                                null,
                                false,
                                "other",
                                null,
                                null,
                                null,
                                null),
                        "SAME");

        RowIdentity.Identities ids = RowIdentity.of(List.of(a, b), ACCOUNT, ImportFormat.OFX);

        assertThat(ids.reusedIdentifier()).isTrue();
        assertThat(ids.externalIds()).allMatch(id -> id.startsWith("fp1:"));
    }

    @Test
    void scaleDoesNotMakeTwoAmountsTwoIdentities() {
        ReadRow short1 = coffee();
        ReadRow long1 =
                new ReadRow(
                        0,
                        ReadRow.Kind.READY,
                        short1.date(),
                        new BigDecimal("-3.5000"),
                        short1.payee(),
                        false,
                        null,
                        false,
                        short1.payeeKey(),
                        null,
                        null,
                        null,
                        null);

        assertThat(RowIdentity.of(List.of(short1), ACCOUNT, ImportFormat.QIF).externalIds())
                .isEqualTo(RowIdentity.of(List.of(long1), ACCOUNT, ImportFormat.QIF).externalIds());
    }

    /** What the unique index does: write a row whose identity is not there yet. */
    private static int importInto(
            Map<String, Integer> ledger, List<ReadRow> rows, ImportFormat format) {
        int written = 0;
        for (String id : RowIdentity.of(rows, ACCOUNT, format).externalIds()) {
            if (ledger.putIfAbsent(id, 1) == null) {
                written++;
            }
        }
        return written;
    }

    private static ReadRow coffee() {
        return new ReadRow(
                0,
                ReadRow.Kind.READY,
                LocalDate.of(2026, 8, 3),
                new BigDecimal("-3.50"),
                "BLUE BOTTLE",
                false,
                null,
                false,
                "blue bottle",
                null,
                null,
                null,
                null);
    }

    private static ReadRow withIdentifier(ReadRow row, String identifier) {
        return new ReadRow(
                row.record(),
                row.kind(),
                row.date(),
                row.amount(),
                row.payee(),
                false,
                null,
                false,
                row.payeeKey(),
                identifier,
                null,
                null,
                null);
    }
}
