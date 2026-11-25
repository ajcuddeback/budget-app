package com.budgetowl.imports.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code previewDigest}: proof that what a commit is about to write is what the person was
 * shown (ADR-0037).
 *
 * <p>SHA-256 over a canonical, length-prefixed serialisation of the target account id, the
 * account's currency, and for every row in file order its record number, classification, date,
 * amount at scale 4, payee, note and {@code external_id}. The account's balance is deliberately not
 * in it, so a member typing an unrelated transaction between preview and commit does not make the
 * preview stale; the classification is, so a row somebody else imported in the meantime does.
 *
 * <p><b>An integrity check for an honest screen, not a security boundary.</b> It has no key and
 * needs none: a client that forges it can only cause the server to write what the server itself
 * parsed from the uploaded file, into an account the caller may already write to.
 */
public final class PreviewDigest {

    /** Bumped if the serialisation ever changes, so an old digest can never match a new one. */
    private static final String VERSION = "budgetowl-import-digest-v1";

    private PreviewDigest() {}

    /** One row as the digest sees it. */
    public record Line(
            int record,
            String classification,
            java.time.LocalDate date,
            java.math.BigDecimal amount,
            String payee,
            String note,
            String externalId) {

        @Override
        public String toString() {
            return "Line[record=" + record + "]";
        }
    }

    public static String of(UUID accountId, Currency currency, List<Line> lines) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currency, "currency");
        MessageDigest digest = RowIdentity.sha256();
        update(digest, VERSION);
        update(digest, accountId.toString());
        update(digest, currency.getCurrencyCode());
        update(digest, Integer.toString(lines.size()));
        for (Line line : lines) {
            update(digest, Integer.toString(line.record()));
            update(digest, line.classification());
            update(digest, line.date() == null ? "" : line.date().toString());
            update(digest, line.amount() == null ? "" : RowIdentity.scaled(line.amount()));
            update(digest, line.payee() == null ? "" : line.payee());
            update(digest, line.note() == null ? "" : line.note());
            update(digest, line.externalId() == null ? "" : line.externalId());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String part) {
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
}
