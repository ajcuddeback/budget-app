package com.budgetowl.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.account.domain.Account;
import com.budgetowl.imports.domain.FileReading;
import com.budgetowl.imports.domain.ImportFormat;
import com.budgetowl.imports.domain.ImportLimits;
import com.budgetowl.imports.domain.ImportReader;
import com.budgetowl.imports.domain.ReadOptions;
import com.budgetowl.imports.domain.ReadRow;
import com.budgetowl.imports.persistence.ImportLedgerRepository;
import com.budgetowl.imports.persistence.LedgerRow;
import com.budgetowl.imports.service.Classified.Classification;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The suspected-duplicate match (tier 2), against a stubbed ledger.
 *
 * <p>Slice-12 security audit, high: the match listed every (row, candidate) pair within the window
 * and sorted them, so two 10,000-row files of one amount on one date built 100 million pairs and
 * ran the instance out of memory. These tests hold the bound, and hold that the bounded match
 * chooses exactly what the old one chose — the preview's digest is built from that choice.
 */
class ImportClassifierTest {

    private static final LocalDate FIRST = LocalDate.of(2026, 8, 1);

    private final UUID household = UUID.randomUUID();
    private final Account account = account();

    @Test
    @Timeout(20)
    void ten_thousand_rows_against_ten_thousand_identical_candidates_match_one_to_one_quickly() {
        int count = 10_000;
        FileReading reading = read(rowsOf(count, FIRST, "-1.00"));
        List<LedgerRow> ledger = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ledger.add(new LedgerRow(UUID.randomUUID(), FIRST, new BigDecimal("-1.0000")));
        }

        Instant started = Instant.now();
        ImportClassifier.Result result =
                new ImportClassifier(repository(ledger)).classify(household, account, reading);
        Duration took = Duration.between(started, Instant.now());

        assertThat(result.rows()).allMatch(row -> row.classification() == Classification.SUSPECTED);
        Set<UUID> matched = new HashSet<>();
        result.rows().forEach(row -> matched.add(row.resembles()));
        assertThat(matched).hasSize(count);
        // The old match took over ten seconds and 2 GB before failing; this is milliseconds.
        assertThat(took).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void the_bounded_match_chooses_exactly_what_the_pairwise_one_chose() {
        Random random = new Random(20261125L);
        String[] amounts = {"-1.00", "-2.00", "3.00"};
        for (int trial = 0; trial < 300; trial++) {
            List<String[]> fileRows = new ArrayList<>();
            int rowCount = 1 + random.nextInt(25);
            for (int i = 0; i < rowCount; i++) {
                fileRows.add(
                        new String[] {
                            FIRST.plusDays(random.nextInt(10)).toString(),
                            amounts[random.nextInt(amounts.length)]
                        });
            }
            List<LedgerRow> ledger = new ArrayList<>();
            int candidateCount = random.nextInt(25);
            for (int i = 0; i < candidateCount; i++) {
                ledger.add(
                        new LedgerRow(
                                UUID.randomUUID(),
                                FIRST.plusDays(random.nextInt(10)),
                                new BigDecimal(amounts[random.nextInt(amounts.length)])
                                        .setScale(4)));
            }
            FileReading reading = read(ofx(fileRows));

            ImportClassifier.Result result =
                    new ImportClassifier(repository(ledger)).classify(household, account, reading);
            UUID[] expected = pairwise(reading.rows(), ledger);

            for (int index = 0; index < reading.rows().size(); index++) {
                Classified row = result.rows().get(index);
                assertThat(row.resembles())
                        .as("trial %d, row %d", trial, index)
                        .isEqualTo(expected[index]);
                assertThat(row.classification() == Classification.SUSPECTED)
                        .as("trial %d, row %d", trial, index)
                        .isEqualTo(expected[index] != null);
            }
        }
    }

    /** The match as it was before the audit: every pair, sorted, taken greedily. */
    private static UUID[] pairwise(List<ReadRow> rows, List<LedgerRow> candidates) {
        List<long[]> pairs = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            for (int position = 0; position < candidates.size(); position++) {
                ReadRow row = rows.get(index);
                LedgerRow candidate = candidates.get(position);
                long days = Math.abs(ChronoUnit.DAYS.between(candidate.date(), row.date()));
                if (candidate.amount().compareTo(row.amount()) == 0
                        && days <= ImportClassifier.RESEMBLANCE_DAYS) {
                    pairs.add(new long[] {days, index, position});
                }
            }
        }
        pairs.sort(
                Comparator.<long[]>comparingLong(pair -> pair[0])
                        .thenComparingLong(pair -> pair[1])
                        .thenComparingLong(pair -> pair[2]));
        UUID[] chosen = new UUID[rows.size()];
        Set<Long> candidatesTaken = new HashSet<>();
        for (long[] pair : pairs) {
            int index = (int) pair[1];
            if (chosen[index] == null && candidatesTaken.add(pair[2])) {
                chosen[index] = candidates.get((int) pair[2]).transactionId();
            }
        }
        return chosen;
    }

    private static List<String[]> rowsOf(int count, LocalDate date, String amount) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new String[] {date.toString(), amount});
        }
        return rows;
    }

    private static String ofx(List<String[]> rows) {
        StringBuilder file =
                new StringBuilder(
                        "<?xml version=\"1.0\"?><?OFX OFXHEADER=\"200\" VERSION=\"211\""
                                + " SECURITY=\"NONE\"?><OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS>"
                                + "<CURDEF>USD</CURDEF><BANKTRANLIST>");
        for (String[] row : rows) {
            file.append("<STMTTRN><DTPOSTED>")
                    .append(row[0].replace("-", ""))
                    .append("</DTPOSTED><TRNAMT>")
                    .append(row[1])
                    .append("</TRNAMT><NAME>Payee</NAME></STMTTRN>");
        }
        return file.append("</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>").toString();
    }

    private static FileReading read(List<String[]> rows) {
        return read(ofx(rows));
    }

    private static FileReading read(String file) {
        ImportReader reader =
                new ImportReader(
                        new ImportLimits(
                                5 * 1024 * 1024,
                                12_000,
                                200_000,
                                4_096,
                                10_000,
                                64,
                                32,
                                200_000,
                                64,
                                1_024,
                                Duration.ofSeconds(15)),
                        Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC),
                        System::nanoTime);
        return reader.read(
                file.getBytes(StandardCharsets.UTF_8),
                ImportFormat.OFX,
                Currency.getInstance("USD"),
                new ReadOptions(null, null, false, null, null, null, null));
    }

    private static ImportLedgerRepository repository(List<LedgerRow> ledger) {
        return (ImportLedgerRepository)
                Proxy.newProxyInstance(
                        ImportClassifierTest.class.getClassLoader(),
                        new Class<?>[] {ImportLedgerRepository.class},
                        (proxy, method, arguments) ->
                                switch (method.getName()) {
                                    case "findImported" -> List.of();
                                    case "findInAccountBetween" -> ledger;
                                    default ->
                                            throw new UnsupportedOperationException(
                                                    method.getName());
                                });
    }

    /** Ids are assigned by JPA, so a stand-alone account has them set the way JPA would. */
    private static Account account() {
        try {
            Constructor<Account> constructor = Account.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Account account = constructor.newInstance();
            set(account, "id", UUID.randomUUID());
            set(account, "householdId", UUID.randomUUID());
            set(account, "currency", "USD");
            return account;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Account account, String name, Object value)
            throws ReflectiveOperationException {
        Field field = Account.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(account, value);
    }
}
