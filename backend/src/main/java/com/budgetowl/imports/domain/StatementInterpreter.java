package com.budgetowl.imports.domain;

import com.budgetowl.imports.domain.ImportAmountLexer.Lexed;
import com.budgetowl.imports.domain.ImportDateParser.Parsed;
import com.budgetowl.payee.domain.Payee;
import com.budgetowl.transaction.domain.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Decides what each row a reader found <em>means</em>, once, for every format
 * (docs/features/file-import.md rules 6, 7 and 8).
 *
 * <p>Dates through {@link ImportDateParser}, amounts through {@link ImportAmountLexer}, and every
 * text value through {@link ImportText}: normalised, then cut to what the ledger stores, with the
 * cut recorded so the preview can say so. A note that only repeats the payee is dropped. Zero is
 * never imported, and is counted rather than silently missing.
 */
final class StatementInterpreter {

    /** Characters of an offending cell shown back to the person (rule "Response: preview"). */
    static final int PROBLEM_TEXT = 64;

    private final ImportDateParser dates;
    private final ParseDeadline deadline;

    StatementInterpreter(ImportDateParser dates, ParseDeadline deadline) {
        this.dates = dates;
        this.deadline = deadline;
    }

    /**
     * @param dateFormat required for CSV and QIF, ignored for OFX
     * @param style how amounts are written
     */
    List<ReadRow> interpret(
            RawStatement statement,
            ImportFormat format,
            ImportDateFormat dateFormat,
            ImportAmountLexer.Style style,
            Currency currency,
            boolean invertSign) {
        List<ReadRow> read = new ArrayList<>(statement.rows().size());
        int index = 0;
        for (RawRow row : statement.rows()) {
            deadline.check(++index);
            read.add(interpret(row, format, dateFormat, style, currency, invertSign));
        }
        return List.copyOf(read);
    }

    private ReadRow interpret(
            RawRow row,
            ImportFormat format,
            ImportDateFormat dateFormat,
            ImportAmountLexer.Style style,
            Currency currency,
            boolean invertSign) {
        if (row.problem() != null) {
            return unreadable(row, row.problem(), row.problemColumn(), row.problemText());
        }

        Parsed parsedDate =
                switch (format) {
                    case OFX -> dates.parseOfx(row.date());
                    case QIF -> dates.parseQif(row.date(), dateFormat);
                    case CSV -> dates.parse(row.date(), dateFormat);
                };
        if (parsedDate instanceof Parsed.Refused refused) {
            return unreadable(row, refused.problem(), row.dateColumn(), row.date());
        }
        LocalDate date = ((Parsed.Day) parsedDate).date();

        if (row.currency() != null
                && !row.currency().strip().equalsIgnoreCase(currency.getCurrencyCode())) {
            return unreadable(row, RowProblem.CURRENCY_MISMATCH, null, row.currency());
        }

        Lexed lexed = ImportAmountLexer.lex(row.amount(), style, currency, row.direction());
        if (lexed instanceof Lexed.Refused refused) {
            return unreadable(row, refused.problem(), row.amountColumn(), row.amount());
        }
        BigDecimal amount = ((Lexed.Amount) lexed).value();
        if (row.alternateAmount() != null && !row.alternateAmount().isBlank()) {
            Lexed other =
                    ImportAmountLexer.lex(row.alternateAmount(), style, currency, row.direction());
            if (!(other instanceof Lexed.Amount alternate)
                    || alternate.value().compareTo(amount) != 0) {
                // QIF's T and U disagree: two answers to one question, and no way to know which.
                return unreadable(row, RowProblem.AMOUNT_UNREADABLE, null, row.alternateAmount());
            }
        }
        if (invertSign) {
            amount = amount.negate();
        }

        String payeeFull = ImportText.normalise(row.payee());
        String payee = ImportText.cut(payeeFull, Payee.NAME_MAX_LENGTH);
        String noteFull = ImportText.normalise(row.note());
        if (noteFull.equalsIgnoreCase(payeeFull)) {
            noteFull = "";
        }
        String note = ImportText.cut(noteFull, Transaction.NOTE_MAX_LENGTH);
        String identifier = row.identifier() == null ? null : row.identifier().strip();

        return new ReadRow(
                row.record(),
                amount.signum() == 0 ? ReadRow.Kind.ZERO : ReadRow.Kind.READY,
                date,
                amount,
                payee.isEmpty() ? null : payee,
                payee.length() < payeeFull.length(),
                note.isEmpty() ? null : note,
                note.length() < noteFull.length(),
                payeeFull.toLowerCase(Locale.ROOT),
                identifier == null || identifier.isEmpty() ? null : identifier,
                null,
                null,
                null);
    }

    private static ReadRow unreadable(RawRow row, RowProblem problem, Integer column, String text) {
        String shown =
                text == null ? null : ImportText.cut(ImportText.normalise(text), PROBLEM_TEXT);
        return new ReadRow(
                row.record(),
                ReadRow.Kind.UNREADABLE,
                null,
                null,
                null,
                false,
                null,
                false,
                null,
                null,
                problem,
                column,
                shown == null || shown.isEmpty() ? null : shown);
    }
}
