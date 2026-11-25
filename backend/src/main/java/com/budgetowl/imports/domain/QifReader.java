package com.budgetowl.imports.domain;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.imports.domain.ImportAmountLexer.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads QIF (docs/features/file-import.md rule 5, "QIF").
 *
 * <p>Records end with {@code ^}. {@code D} is the date, {@code T} or {@code U} the amount (both
 * present must agree), {@code P} the payee and {@code M} the memo. {@code C}, {@code N} and {@code
 * L} are read past; <b>{@code A} address lines are dropped</b>, because a payee's postal address is
 * personal data the ledger has no use for; and the split lines {@code S}, {@code E} and {@code $}
 * are ignored, so a split record imports as one row for its total. A final record with no closing
 * {@code ^} is accepted; unknown field letters are ignored.
 *
 * <p>Exactly one transaction section, for one account. {@code !Type:Invst} is refused outright —
 * investment QIF carries buys, sells and share quantities this ledger cannot represent — and the
 * list sections ({@code !Type:Cat}, {@code !Type:Class}, {@code !Type:Memorized}) are skipped.
 */
final class QifReader {

    private static final Set<String> TRANSACTION_TYPES =
            Set.of("BANK", "CCARD", "CASH", "OTH A", "OTH L");

    private static final Set<String> LIST_TYPES = Set.of("CAT", "CLASS", "MEMORIZED", "MEMORISED");

    private enum Section {
        NONE,
        TRANSACTIONS,
        ACCOUNT,
        SKIPPED
    }

    private final ImportLimits limits;
    private final ParseDeadline deadline;

    QifReader(ImportLimits limits, ParseDeadline deadline) {
        this.limits = limits;
        this.deadline = deadline;
    }

    RawStatement read(String text) {
        List<RawRow> rows = new ArrayList<>();
        Section section = Section.NONE;
        int transactionSections = 0;
        int accounts = 0;
        boolean accountHasContent = false;
        Pending pending = new Pending();

        for (String line : text.split("\\r\\n|\\n|\\r", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.charAt(0) == '!') {
                if (section == Section.TRANSACTIONS && pending.hasContent()) {
                    rows.add(pending.finish(rows.size() + 1));
                    pending = new Pending();
                }
                String header = trimmed.substring(1).strip().toUpperCase(Locale.ROOT);
                if (header.startsWith("TYPE:")) {
                    String type = header.substring(5).strip();
                    if (TRANSACTION_TYPES.contains(type)) {
                        transactionSections++;
                        if (transactionSections > 1) {
                            throw ImportRefusal.of(ErrorCode.IMPORT_MULTIPLE_ACCOUNTS);
                        }
                        section = Section.TRANSACTIONS;
                    } else if (LIST_TYPES.contains(type)) {
                        section = Section.SKIPPED;
                    } else {
                        // INVST, and anything else this reader does not know how to be honest
                        // about.
                        throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
                    }
                } else if (header.equals("ACCOUNT")) {
                    section = Section.ACCOUNT;
                } else if (header.startsWith("OPTION:") || header.startsWith("CLEAR:")) {
                    // Quicken's switches (AutoSwitch). They change nothing this reader does.
                    continue;
                } else {
                    throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
                }
                continue;
            }

            switch (section) {
                case NONE -> throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
                case SKIPPED -> {
                    // A category, class or memorised-transaction list. Not ours to read.
                }
                case ACCOUNT -> {
                    if (trimmed.charAt(0) == '^') {
                        if (accountHasContent) {
                            accounts++;
                            if (accounts > 1) {
                                throw ImportRefusal.of(ErrorCode.IMPORT_MULTIPLE_ACCOUNTS);
                            }
                        }
                        accountHasContent = false;
                    } else {
                        accountHasContent = true;
                    }
                }
                case TRANSACTIONS -> {
                    if (trimmed.charAt(0) == '^') {
                        if (pending.hasContent()) {
                            int record = rows.size() + 1;
                            if (record > limits.maximumRows()) {
                                throw ImportRefusal.of(ErrorCode.IMPORT_TOO_MANY_ROWS);
                            }
                            deadline.check(record);
                            rows.add(pending.finish(record));
                        }
                        pending = new Pending();
                    } else {
                        pending.read(trimmed.charAt(0), trimmed.substring(1));
                    }
                }
            }
        }
        if (section == Section.TRANSACTIONS && pending.hasContent()) {
            int record = rows.size() + 1;
            if (record > limits.maximumRows()) {
                throw ImportRefusal.of(ErrorCode.IMPORT_TOO_MANY_ROWS);
            }
            rows.add(pending.finish(record));
        }
        if (transactionSections == 0) {
            throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
        }
        return new RawStatement(rows, null, null, null);
    }

    /** One record in progress. The first occurrence of a field wins. */
    private static final class Pending {

        private String date;
        private String total;
        private String alternate;
        private String payee;
        private String memo;
        private boolean content;

        void read(char field, String value) {
            content = true;
            switch (field) {
                case 'D' -> date = date == null ? value : date;
                case 'T' -> total = total == null ? value : total;
                case 'U' -> alternate = alternate == null ? value : alternate;
                case 'P' -> payee = payee == null ? value : payee;
                case 'M' -> memo = memo == null ? value : memo;
                default -> {
                    // C cleared, N number, L category, A address (dropped), S/E/$ split lines,
                    // and any letter this reader does not know.
                }
            }
        }

        boolean hasContent() {
            return content;
        }

        RawRow finish(int record) {
            String amount = total != null ? total : alternate;
            String other = total != null ? alternate : null;
            if (date == null || date.isBlank() || amount == null || amount.isBlank()) {
                return RawRow.unreadable(record, RowProblem.MISSING_FIELD, null, null);
            }
            return new RawRow(
                    record,
                    date,
                    amount,
                    other,
                    Direction.NONE,
                    payee,
                    memo,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
