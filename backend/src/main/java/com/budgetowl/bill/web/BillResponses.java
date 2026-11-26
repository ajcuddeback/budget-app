package com.budgetowl.bill.web;

import com.budgetowl.bill.domain.OccurrenceStatus;
import com.budgetowl.bill.domain.StatusSource;
import com.budgetowl.bill.service.BillView;
import com.budgetowl.bill.service.BillsForPeriod;
import com.budgetowl.bill.service.OccurrenceView;
import com.budgetowl.common.schedule.web.ScheduleResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response bodies for the bill endpoints. The entity never appears in one. */
public final class BillResponses {

    private BillResponses() {}

    /**
     * One occurrence.
     *
     * @param recorded false for the common case: no row exists, and the occurrence is {@code DUE}
     *     because that is what the schedule implies rather than because anybody said so (ADR-0032)
     * @param overdue derived per request, never stored
     * @param surprising whether what was paid is further from what was budgeted than the bill says
     *     is unremarkable
     * @param version null for a projected occurrence, which has no row to be stale against
     */
    public record OccurrenceResponse(
            UUID billId,
            LocalDate dueDate,
            BigDecimal expectedAmount,
            OccurrenceStatus status,
            BigDecimal actualAmount,
            LocalDate paidDate,
            UUID transactionId,
            StatusSource statusSource,
            boolean recorded,
            boolean overdue,
            boolean surprising,
            Long version) {

        /** Redacted: a generated one would put what somebody pays for their home into a log. */
        @Override
        public String toString() {
            return "OccurrenceResponse[dueDate=" + dueDate + ", status=" + status + "]";
        }

        static OccurrenceResponse of(OccurrenceView view) {
            return new OccurrenceResponse(
                    view.billId(),
                    view.dueDate(),
                    view.expectedAmount().amount(),
                    view.status(),
                    view.actualAmount() == null ? null : view.actualAmount().amount(),
                    view.paidDate(),
                    view.transactionId(),
                    view.statusSource(),
                    view.recorded(),
                    view.overdue(),
                    view.surprising(),
                    view.version());
        }
    }

    /**
     * One bill, with the occurrences the requested month implies.
     *
     * @param occurrences empty on a write: which month the client is looking at is the client's
     *     question, and the list endpoint is where it asks
     */
    public record BillResponse(
            UUID id,
            String name,
            UUID categoryId,
            String categoryName,
            String categoryParentName,
            BigDecimal expectedAmount,
            String currency,
            ScheduleResponse schedule,
            boolean amountVaries,
            BigDecimal tolerance,
            boolean active,
            List<OccurrenceResponse> occurrences,
            List<MappingResponse> mappings,
            Instant createdAt,
            Instant updatedAt,
            long version) {

        /** Redacted — amounts are financial data. */
        @Override
        public String toString() {
            return "BillResponse[id=" + id + ", active=" + active + "]";
        }

        static BillResponse of(BillView view) {
            return of(view, List.of());
        }

        /**
         * @param mappings this bill's charge rules (slice 14), for <em>"Mapped · COMCAST XFINITY ·
         *     Chase •4412"</em> without a request per bill. Only the list fills them; a write
         *     answers with none, as it answers with no occurrences
         */
        static BillResponse of(BillView view, List<MappingResponse> mappings) {
            return new BillResponse(
                    view.id(),
                    view.name(),
                    view.categoryId(),
                    view.categoryName(),
                    view.categoryParentName(),
                    view.expectedAmount().amount(),
                    view.expectedAmount().currency().getCurrencyCode(),
                    ScheduleResponse.of(view.recurrence()),
                    view.amountVaries(),
                    view.tolerance(),
                    view.active(),
                    view.occurrences().stream().map(OccurrenceResponse::of).toList(),
                    List.copyOf(mappings),
                    view.createdAt(),
                    view.updatedAt(),
                    view.version());
        }
    }

    /**
     * One charge rule mapping a {@code (payee, account)} to this bill. The match kind is the bill's
     * own {@code amountVaries} and {@code tolerance}, so it is not repeated here.
     */
    public record MappingResponse(
            UUID ruleId,
            String payeeName,
            UUID accountId,
            String accountName,
            String accountLastFour) {

        /** Redacted: which payee settles which bill is sensitive. */
        @Override
        public String toString() {
            return "MappingResponse[ruleId=" + ruleId + "]";
        }
    }

    /**
     * One month's bills and what they come to.
     *
     * @param total the month's bills in all, paid ones included at what was really paid. See {@code
     *     BillsForPeriod} for why that and not only the unpaid ones
     */
    public record BillsResponse(
            List<BillResponse> content,
            BigDecimal total,
            BigDecimal outstanding,
            BigDecimal overdue,
            String currency) {

        /** Redacted, for the reason {@code LedgerResponse} is. */
        @Override
        public String toString() {
            return "BillsResponse[bills=" + content.size() + "]";
        }

        static BillsResponse of(BillsForPeriod found) {
            return of(found, Map.of());
        }

        static BillsResponse of(BillsForPeriod found, Map<UUID, List<MappingResponse>> mappings) {
            return new BillsResponse(
                    found.bills().stream()
                            .map(
                                    bill ->
                                            BillResponse.of(
                                                    bill,
                                                    mappings.getOrDefault(bill.id(), List.of())))
                            .toList(),
                    found.total().amount(),
                    found.outstanding().amount(),
                    found.overdue().amount(),
                    found.total().currency().getCurrencyCode());
        }
    }
}
