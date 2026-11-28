package com.budgetowl.imports.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code external_id} of every importable row: how a re-import recognises what it already wrote
 * (docs/features/file-import.md rule 9, tier 1).
 *
 * <p>The form is {@code <scheme>:<64 hex>}, the hex being SHA-256 over a length-prefixed tuple:
 *
 * <ul>
 *   <li>{@code ofx1}: an OFX row whose {@code FITID} is unique within the file — account id, FITID;
 *   <li>{@code ref1}: a CSV row with a mapped reference unique within the file — account id,
 *       reference;
 *   <li>{@code fp1}: everything else — account id, date, amount at scale 4, case-folded payee, and
 *       the <b>occurrence ordinal</b>.
 * </ul>
 *
 * <p>Three properties, each a real choice:
 *
 * <ol>
 *   <li><b>The account id is inside the hash.</b> {@code ux_transactions_household_external_id} is
 *       unique per household, but a {@code FITID} is only unique per account at one bank. Storing
 *       it raw would make the second account's real transaction look like a duplicate of the
 *       first's and silently drop it.
 *   <li><b>The occurrence ordinal makes identical rows work.</b> It counts the earlier rows in the
 *       same file with the same date, amount and payee, plus one. Two £3.50 coffees on the same day
 *       at the same shop are ordinals 1 and 2, so both import; re-importing the file gives 1 and 2
 *       again, so both are skipped; and the ordinal counts identical tuples, not positions, so
 *       reordering the file changes nothing.
 *   <li><b>An identifier the bank repeats inside one file is not trusted</b>: every row sharing it
 *       falls back to {@code fp1}.
 * </ol>
 *
 * <p><b>Hashing is not a confidentiality control</b> — the inputs are low-entropy, and anyone with
 * the database has the transactions anyway. It gives a fixed length that fits {@code
 * ck_transactions_external_id}, and it stops a bank's identifier (which sometimes embeds an account
 * number) being stored as written.
 */
public final class RowIdentity {

    private RowIdentity() {}

    /**
     * @param externalIds one per row, in row order; {@code null} for a row that is not {@code
     *     READY}
     * @param reusedIdentifier whether the bank repeated an identifier within the file
     */
    public record Identities(List<String> externalIds, boolean reusedIdentifier) {

        public Identities {
            externalIds = java.util.Collections.unmodifiableList(new ArrayList<>(externalIds));
        }
    }

    public static Identities of(List<ReadRow> rows, UUID accountId, ImportFormat format) {
        Map<String, Integer> identifierUses = new HashMap<>();
        for (ReadRow row : rows) {
            if (row.identifier() != null) {
                identifierUses.merge(row.identifier(), 1, Integer::sum);
            }
        }
        boolean reused = identifierUses.values().stream().anyMatch(uses -> uses > 1);
        boolean identifiersAreTheBanks = format == ImportFormat.OFX || format == ImportFormat.CSV;

        Map<String, Integer> occurrences = new HashMap<>();
        List<String> ids = new ArrayList<>(rows.size());
        for (ReadRow row : rows) {
            if (row.kind() != ReadRow.Kind.READY) {
                ids.add(null);
                continue;
            }
            String identifier = row.identifier();
            if (identifiersAreTheBanks
                    && identifier != null
                    && identifierUses.getOrDefault(identifier, 0) == 1) {
                String scheme = format == ImportFormat.OFX ? "ofx1" : "ref1";
                ids.add(scheme + ":" + sha256(scheme, accountId.toString(), identifier));
                continue;
            }
            String date = row.date().toString();
            String amount = scaled(row.amount());
            String payee = row.payeeKey() == null ? "" : row.payeeKey();
            String tuple = date + '\u0000' + amount + '\u0000' + payee;
            int ordinal = occurrences.merge(tuple, 1, Integer::sum);
            ids.add(
                    "fp1:"
                            + sha256(
                                    "fp1",
                                    accountId.toString(),
                                    date,
                                    amount,
                                    payee,
                                    Integer.toString(ordinal)));
        }
        return new Identities(ids, reused);
    }

    /** Scale 4, so {@code 3.5} and {@code 3.5000} are one amount. Exact: no row exceeds scale 4. */
    static String scaled(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * SHA-256 over each part's UTF-8 length as four bytes, then its bytes. The length prefix is
     * what stops {@code ("ab","c")} and {@code ("a","bc")} hashing alike.
     */
    static String sha256(String... parts) {
        MessageDigest digest = sha256();
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            digest.update(
                    new byte[] {
                        (byte) (bytes.length >>> 24),
                        (byte) (bytes.length >>> 16),
                        (byte) (bytes.length >>> 8),
                        (byte) bytes.length
                    });
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every JDK has SHA-256", absent);
        }
    }
}
