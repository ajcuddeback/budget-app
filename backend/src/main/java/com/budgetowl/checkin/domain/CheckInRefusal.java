package com.budgetowl.checkin.domain;

import com.budgetowl.common.DomainException;
import com.budgetowl.common.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A check-in refused, with params from a fixed vocabulary only: item keys (server-issued ids),
 * reasons from {@link StaleReason}, and a week. <b>Never an amount, a name or a category</b> — this
 * exception's params reach the response, and its message reaches the log through {@code
 * ApiExceptionHandler} (threat model, attack 13).
 */
public final class CheckInRefusal extends DomainException {

    private static final long serialVersionUID = 1L;

    private CheckInRefusal(ErrorCode code, Map<String, Object> params) {
        super(code, "check-in refused: " + code.code(), params);
    }

    /** {@code 422 check-in-week-out-of-range}. */
    public static CheckInRefusal weekOutOfRange() {
        return new CheckInRefusal(ErrorCode.CHECK_IN_WEEK_OUT_OF_RANGE, Map.of());
    }

    /** {@code 409 check-in-draft-open}, naming the week whose draft is open. */
    public static CheckInRefusal draftOpen(IsoWeek week) {
        return new CheckInRefusal(ErrorCode.CHECK_IN_DRAFT_OPEN, Map.of("week", week.toString()));
    }

    /** {@code 409 check-in-completed}. */
    public static CheckInRefusal completed() {
        return new CheckInRefusal(ErrorCode.CHECK_IN_COMPLETED, Map.of());
    }

    /** {@code 422 check-in-incomplete}: a surplus is offered and step 4 has no answer. */
    public static CheckInRefusal incomplete() {
        return new CheckInRefusal(
                ErrorCode.CHECK_IN_INCOMPLETE, Map.of("items", List.of(ItemKeys.ALLOCATION)));
    }

    /** {@code 409 check-in-stale}, with every affected item and its reason. */
    public static CheckInRefusal stale(List<Stale> items) {
        List<Map<String, Object>> listed = new ArrayList<>(items.size());
        for (Stale item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (item.itemKey() != null) {
                entry.put("itemKey", item.itemKey());
            }
            entry.put("reason", item.reason().code());
            listed.add(Map.copyOf(entry));
        }
        return new CheckInRefusal(ErrorCode.CHECK_IN_STALE, Map.of("items", List.copyOf(listed)));
    }

    /** One stale item. {@code itemKey} is null for {@link StaleReason#BASIS_CHANGED}. */
    public record Stale(String itemKey, StaleReason reason) {

        public Stale {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** No stack: a refusal is an answer, not a bug. */
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
