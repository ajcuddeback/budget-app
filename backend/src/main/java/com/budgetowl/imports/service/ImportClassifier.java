package com.budgetowl.imports.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.imports.domain.FileReading;
import com.budgetowl.imports.domain.PreviewDigest;
import com.budgetowl.imports.domain.ReadRow;
import com.budgetowl.imports.domain.RowIdentity;
import com.budgetowl.imports.persistence.ImportLedgerRepository;
import com.budgetowl.imports.persistence.ImportedRow;
import com.budgetowl.imports.persistence.LedgerRow;
import com.budgetowl.imports.service.Classified.Classification;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets every row of a file against the ledger: new, a suspected duplicate, or one already imported
 * (docs/features/file-import.md rule 9), and computes the digest of the result (ADR-0037).
 *
 * <p><b>The same method serves preview and commit</b>, which is what makes the digest mean
 * anything: the commit re-reads the file, re-classifies it against the ledger as it is at that
 * moment, and only writes if the result is what the person was shown.
 *
 * <p><b>Tier 1</b> is exact: a row whose {@code external_id} is already in the household. <b>Tier
 * 2</b> is a resemblance: an existing row in the <b>same account</b> with an equal amount ({@code
 * compareTo}) within three days. Matching is one to one, closest date first, so one typed coffee
 * cannot suppress two imported ones — and a ledger row already claimed by a tier-1 duplicate in
 * this same file is not available to resemble anything else, which is what lets an overlapping
 * statement import "the second coffee" exactly once.
 */
@Service
public class ImportClassifier {

    /** Days either side for a suspected duplicate (rule 9, tier 2). */
    static final int RESEMBLANCE_DAYS = 3;

    /** External ids per {@code IN} query. */
    private static final int CHUNK = 1000;

    private final ImportLedgerRepository ledger;

    public ImportClassifier(ImportLedgerRepository ledger) {
        this.ledger = ledger;
    }

    /** The classification, the digest of it, and what the bank's identifiers were worth. */
    public record Result(List<Classified> rows, String digest, boolean reusedIdentifier) {

        public Result {
            rows = List.copyOf(rows);
        }

        @Override
        public String toString() {
            return "Result[rows=" + rows.size() + "]";
        }
    }

    @Transactional(readOnly = true)
    public Result classify(UUID householdId, Account account, FileReading reading) {
        List<ReadRow> rows = reading.rows();
        RowIdentity.Identities identities = RowIdentity.of(rows, account.id(), reading.format());
        List<String> ids = identities.externalIds();

        Map<String, UUID> imported = existing(householdId, ids);
        Set<UUID> claimed = new HashSet<>(imported.values());

        Classification[] kinds = new Classification[rows.size()];
        UUID[] resembles = new UUID[rows.size()];
        LocalDate[] resemblesDate = new LocalDate[rows.size()];
        List<Integer> fresh = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            ReadRow row = rows.get(index);
            switch (row.kind()) {
                case UNREADABLE -> kinds[index] = Classification.UNREADABLE;
                case ZERO -> kinds[index] = Classification.ZERO;
                case READY -> {
                    UUID already = imported.get(ids.get(index));
                    if (already != null) {
                        kinds[index] = Classification.DUPLICATE;
                        resembles[index] = already;
                    } else {
                        kinds[index] = Classification.NEW;
                        fresh.add(index);
                    }
                }
            }
        }

        markSuspected(
                householdId, account.id(), rows, fresh, claimed, kinds, resembles, resemblesDate);

        List<Classified> classified = new ArrayList<>(rows.size());
        List<PreviewDigest.Line> lines = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            ReadRow row = rows.get(index);
            classified.add(
                    new Classified(
                            row,
                            kinds[index],
                            ids.get(index),
                            resembles[index],
                            resemblesDate[index]));
            lines.add(
                    new PreviewDigest.Line(
                            row.record(),
                            kinds[index].name(),
                            row.date(),
                            row.amount(),
                            row.payee(),
                            row.note(),
                            ids.get(index)));
        }
        String digest = PreviewDigest.of(account.id(), account.currency(), lines);
        return new Result(classified, digest, identities.reusedIdentifier());
    }

    /** The account's balance in the ledger now: its opening balance plus every row in it. */
    @Transactional(readOnly = true)
    public BigDecimal balanceOf(UUID householdId, Account account) {
        return account.openingBalance()
                .amount()
                .add(ledger.sumForAccount(householdId, account.id()));
    }

    /** Which of these identities are already in the household, and the row carrying each. */
    private Map<String, UUID> existing(UUID householdId, List<String> ids) {
        List<String> wanted = ids.stream().filter(id -> id != null).distinct().toList();
        Map<String, UUID> found = new HashMap<>();
        for (int start = 0; start < wanted.size(); start += CHUNK) {
            List<String> chunk = wanted.subList(start, Math.min(wanted.size(), start + CHUNK));
            for (ImportedRow row : ledger.findImported(householdId, chunk)) {
                found.put(row.externalId(), row.transactionId());
            }
        }
        return found;
    }

    /** Tier 2, one to one, closest date first; ties broken by file order, then ledger order. */
    private void markSuspected(
            UUID householdId,
            UUID accountId,
            List<ReadRow> rows,
            List<Integer> fresh,
            Set<UUID> claimed,
            Classification[] kinds,
            UUID[] resembles,
            LocalDate[] resemblesDate) {
        if (fresh.isEmpty()) {
            return;
        }
        LocalDate from = null;
        LocalDate to = null;
        for (int index : fresh) {
            LocalDate date = rows.get(index).date();
            from = from == null || date.isBefore(from) ? date : from;
            to = to == null || date.isAfter(to) ? date : to;
        }
        List<LedgerRow> candidates =
                ledger.findInAccountBetween(
                        householdId,
                        accountId,
                        from.minusDays(RESEMBLANCE_DAYS),
                        to.plusDays(RESEMBLANCE_DAYS));
        if (candidates.isEmpty()) {
            return;
        }

        Map<BigDecimal, List<Integer>> byAmount = new HashMap<>();
        for (int position = 0; position < candidates.size(); position++) {
            LedgerRow candidate = candidates.get(position);
            if (!claimed.contains(candidate.transactionId())) {
                byAmount.computeIfAbsent(
                                candidate.amount().stripTrailingZeros(), key -> new ArrayList<>())
                        .add(position);
            }
        }

        List<long[]> pairs = new ArrayList<>();
        for (int index : fresh) {
            ReadRow row = rows.get(index);
            List<Integer> sameAmount = byAmount.get(row.amount().stripTrailingZeros());
            if (sameAmount == null) {
                continue;
            }
            for (int position : sameAmount) {
                long days =
                        Math.abs(
                                ChronoUnit.DAYS.between(
                                        candidates.get(position).date(), row.date()));
                if (days <= RESEMBLANCE_DAYS) {
                    pairs.add(new long[] {days, index, position});
                }
            }
        }
        pairs.sort(
                Comparator.<long[]>comparingLong(pair -> pair[0])
                        .thenComparingLong(pair -> pair[1])
                        .thenComparingLong(pair -> pair[2]));

        Set<Integer> rowsTaken = new HashSet<>();
        Set<Integer> candidatesTaken = new HashSet<>();
        for (long[] pair : pairs) {
            int index = (int) pair[1];
            int position = (int) pair[2];
            if (rowsTaken.contains(index) || candidatesTaken.contains(position)) {
                continue;
            }
            rowsTaken.add(index);
            candidatesTaken.add(position);
            kinds[index] = Classification.SUSPECTED;
            resembles[index] = candidates.get(position).transactionId();
            resemblesDate[index] = candidates.get(position).date();
        }
    }
}
