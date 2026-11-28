package com.budgetowl.checkin.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The five shapes an item key takes, issued by the server in every read and <b>parsed strictly</b>
 * (threat model, attack 10): at most 100 characters, one of five fixed patterns, a canonical
 * lower-case UUID and a real date. Anything else is not a key.
 *
 * <p>Parsing a key says nothing about whether the item exists. The service looks a parsed key up
 * among the items it derived for <b>this</b> household's <b>this</b> week, so a foreign, deleted or
 * out-of-window id is the same {@code 404} as a malformed one (attack 2).
 */
public final class ItemKeys {

    public static final int MAXIMUM_LENGTH = 100;

    public static final String ALLOCATION = "alloc";

    private static final String UUID_SHAPE =
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";

    private static final Pattern TRANSACTION_KEY =
            Pattern.compile("^(odd|cat|chg)\\." + UUID_SHAPE + "$");

    private static final Pattern OCCURRENCE_KEY =
            Pattern.compile("^bill\\." + UUID_SHAPE + "\\.(\\d{4}-\\d{2}-\\d{2})$");

    private ItemKeys() {}

    public static String unusual(UUID transactionId) {
        return "odd." + transactionId;
    }

    public static String categorize(UUID transactionId) {
        return "cat." + transactionId;
    }

    public static String charge(UUID transactionId) {
        return "chg." + transactionId;
    }

    public static String occurrence(UUID billId, LocalDate dueDate) {
        return "bill." + billId + "." + dueDate;
    }

    /** A key taken apart, or empty for anything that is not one. */
    public static Optional<Parsed> parse(String key) {
        if (key == null || key.isEmpty() || key.length() > MAXIMUM_LENGTH) {
            return Optional.empty();
        }
        if (ALLOCATION.equals(key)) {
            return Optional.of(new Parsed(ItemKind.ALLOCATION, null, null, null));
        }
        Matcher transaction = TRANSACTION_KEY.matcher(key);
        if (transaction.matches()) {
            ItemKind kind =
                    switch (transaction.group(1)) {
                        case "odd" -> ItemKind.UNUSUAL_CHARGE;
                        case "cat" -> ItemKind.CATEGORIZE;
                        default -> ItemKind.BILL_CHARGE;
                    };
            return Optional.of(new Parsed(kind, UUID.fromString(transaction.group(2)), null, null));
        }
        Matcher occurrence = OCCURRENCE_KEY.matcher(key);
        if (occurrence.matches()) {
            try {
                LocalDate date = LocalDate.parse(occurrence.group(2));
                return Optional.of(
                        new Parsed(
                                ItemKind.OVERDUE_BILL,
                                null,
                                UUID.fromString(occurrence.group(1)),
                                date));
            } catch (DateTimeParseException notADate) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** A key's parts. Which are set depends on the kind. */
    public record Parsed(ItemKind kind, UUID transactionId, UUID billId, LocalDate dueDate) {}
}
