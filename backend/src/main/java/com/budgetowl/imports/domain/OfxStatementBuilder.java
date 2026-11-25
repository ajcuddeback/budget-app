package com.budgetowl.imports.domain;

import com.budgetowl.common.ErrorCode;
import com.budgetowl.imports.domain.ImportAmountLexer.Direction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Turns the element events of an OFX file — from either reader — into a {@link RawStatement}.
 *
 * <p>Both OFX readers feed this the same three events, so what is read from a statement, and what
 * never leaves the parser, is decided once (docs/features/file-import.md, "What is read from OFX").
 * Read: {@code STMTTRN}'s {@code DTPOSTED}, {@code TRNAMT}, {@code FITID}, {@code NAME} (or {@code
 * PAYEE/NAME}) and {@code MEMO}; the statement's {@code CURDEF}; {@code LEDGERBAL/BALAMT}; and the
 * statement account's {@code ACCTID}, which is compared in memory and then dropped. <b>Everything
 * else is ignored without being kept</b>: {@code BANKID}, {@code BRANCHID}, {@code SIGNONMSGSRSV1},
 * {@code INTU.USERID}, {@code AVAILBAL}, {@code TRNTYPE}, {@code CHECKNUM} and the rest are never
 * assigned to anything.
 */
final class OfxStatementBuilder {

    private static final Set<String> STATEMENTS = Set.of("STMTRS", "CCSTMTRS");
    private static final Set<String> ACCOUNT_FROM = Set.of("BANKACCTFROM", "CCACCTFROM");

    private final ImportLimits limits;
    private final ParseDeadline deadline;
    private final Deque<String> path = new ArrayDeque<>();
    private final List<RawRow> rows = new ArrayList<>();

    private int statements;
    private String currency;
    private String ledgerBalance;
    private String accountIdentifier;

    private PendingRow current;

    OfxStatementBuilder(ImportLimits limits, ParseDeadline deadline) {
        this.limits = limits;
        this.deadline = deadline;
    }

    void open(String name) {
        if (STATEMENTS.contains(name)) {
            statements++;
            if (statements > 1) {
                throw ImportRefusal.of(ErrorCode.IMPORT_MULTIPLE_ACCOUNTS);
            }
        }
        if ("INVSTMTRS".equals(name)) {
            // Buys, sells and share quantities that this ledger cannot represent. Importing only
            // the cash column would be a silently wrong ledger, so the file is refused whole.
            throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
        }
        if ("STMTTRN".equals(name) && inStatement()) {
            int record = rows.size() + 1;
            if (record > limits.maximumRows()) {
                throw ImportRefusal.of(ErrorCode.IMPORT_TOO_MANY_ROWS);
            }
            deadline.check(record);
            current = new PendingRow(record);
        }
        path.push(name);
    }

    void close(String name) {
        path.pop();
        if ("STMTTRN".equals(name) && current != null) {
            rows.add(current.toRow());
            current = null;
        }
    }

    /**
     * @param entityProblem whether the text held an entity reference that was not decoded
     */
    void leaf(String name, String text, boolean entityProblem) {
        String parent = path.peek();
        if (parent == null) {
            return;
        }
        if (current != null && ("STMTTRN".equals(parent) || "PAYEE".equals(parent))) {
            current.read(parent, name, text, entityProblem);
            return;
        }
        if (entityProblem) {
            // Outside a transaction there is no row to mark, and the only fields read here are a
            // currency, a balance and an account number — none of which has a reason to carry one.
            return;
        }
        if (STATEMENTS.contains(parent) && "CURDEF".equals(name)) {
            currency = text;
        } else if ("LEDGERBAL".equals(parent) && "BALAMT".equals(name) && inStatement()) {
            ledgerBalance = text;
        } else if (ACCOUNT_FROM.contains(parent) && "ACCTID".equals(name) && statementIsParent()) {
            accountIdentifier = text;
        }
    }

    /** The statement, once every element has been seen. */
    RawStatement finish() {
        if (statements == 0 || !path.isEmpty()) {
            throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
        }
        if (currency == null || currency.isBlank()) {
            // CURDEF is required by the specification, and without it the account's currency
            // cannot be checked — so there is no honest way to read the amounts.
            throw ImportRefusal.malformed(MalformedReason.NOT_A_STATEMENT);
        }
        return new RawStatement(rows, currency, ledgerBalance, accountIdentifier);
    }

    private boolean inStatement() {
        for (String open : path) {
            if (STATEMENTS.contains(open)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the element enclosing the current one is the statement itself. */
    private boolean statementIsParent() {
        if (path.size() < 2) {
            return false;
        }
        var iterator = path.iterator();
        iterator.next();
        return STATEMENTS.contains(iterator.next());
    }

    /** One {@code STMTTRN} in progress. */
    private static final class PendingRow {

        private final int record;
        private String posted;
        private String amount;
        private String fitId;
        private String name;
        private String payeeName;
        private String memo;
        private boolean entityProblem;

        PendingRow(int record) {
            this.record = record;
        }

        void read(String parent, String element, String text, boolean problem) {
            entityProblem = entityProblem || problem;
            if ("PAYEE".equals(parent)) {
                if ("NAME".equals(element)) {
                    payeeName = text;
                }
                return;
            }
            switch (element) {
                case "DTPOSTED" -> posted = text;
                case "TRNAMT" -> amount = text;
                case "FITID" -> fitId = text;
                case "NAME" -> name = text;
                case "MEMO" -> memo = text;
                default -> {
                    // TRNTYPE, DTUSER, CHECKNUM, REFNUM, SIC, CURRENCY, ORIGCURRENCY and every
                    // INTU.* extension: read past, never kept.
                }
            }
        }

        RawRow toRow() {
            if (entityProblem) {
                return RawRow.unreadable(record, RowProblem.ENTITY_UNKNOWN, null, null);
            }
            if (posted == null || posted.isBlank() || amount == null || amount.isBlank()) {
                return RawRow.unreadable(record, RowProblem.MISSING_FIELD, null, null);
            }
            return new RawRow(
                    record,
                    posted,
                    amount,
                    null,
                    Direction.NONE,
                    name != null ? name : payeeName,
                    memo,
                    fitId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
