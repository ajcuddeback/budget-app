package com.budgetowl.imports.web;

import com.budgetowl.imports.domain.CsvColumnRole;
import com.budgetowl.imports.domain.CsvShape;
import com.budgetowl.imports.domain.CsvSuggestion;
import com.budgetowl.imports.domain.FileReading;
import com.budgetowl.imports.domain.ImportDateFormat;
import com.budgetowl.imports.domain.ImportFormat;
import com.budgetowl.imports.service.ImportBatchView;
import com.budgetowl.imports.service.ImportHistoryService;
import com.budgetowl.imports.service.ImportMappingView;
import com.budgetowl.imports.service.ImportPreview;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Response bodies for file import. No entity appears in one, and every {@code toString} prints
 * counts or ids only: a preview is a bank statement read back to the person who uploaded it.
 */
public final class ImportResponses {

    private ImportResponses() {}

    /** docs/features/file-import.md, "Response: preview". */
    public record PreviewResponse(
            String previewDigest,
            ImportPreview.AccountSummary account,
            ImportFormat format,
            int decodedBytes,
            String charset,
            FileReading.CharsetSource charsetSource,
            List<FileReading.CharsetCandidate> charsetCandidates,
            List<String> charsetsAllowed,
            String suggestedCharset,
            String dateFormat,
            FileReading.DateFormatSource dateFormatSource,
            List<String> dateFormatCandidates,
            Set<FileReading.Need> needs,
            ImportPreview.Statement statement,
            CountsResponse counts,
            ImportPreview.Totals totals,
            BigDecimal balanceNow,
            BigDecimal balanceAfter,
            BigDecimal balanceAfterWithSuspected,
            List<ImportPreview.Attention> attention,
            boolean attentionTruncated,
            List<ImportPreview.Sample> sample,
            List<ImportPreview.Identical> identical,
            boolean reusedIdentifier,
            boolean textCut,
            CsvResponse csv) {

        static PreviewResponse of(ImportPreview preview) {
            return new PreviewResponse(
                    preview.previewDigest(),
                    preview.account(),
                    preview.format(),
                    preview.decodedBytes(),
                    preview.charset(),
                    preview.charsetSource(),
                    preview.charsetCandidates(),
                    preview.charsetsAllowed(),
                    preview.charset() == null
                            ? com.budgetowl.imports.domain.ImportCharsets.SUGGESTED_FALLBACK
                            : null,
                    preview.dateFormat(),
                    preview.dateFormatSource(),
                    preview.dateFormatCandidates(),
                    preview.needs(),
                    preview.statement(),
                    preview.counts() == null ? null : CountsResponse.of(preview.counts()),
                    preview.totals(),
                    preview.balanceNow(),
                    preview.balanceAfter(),
                    preview.balanceAfterWithSuspected(),
                    preview.attention(),
                    preview.attentionTruncated(),
                    preview.sample(),
                    preview.identical(),
                    preview.reusedIdentifier(),
                    preview.textCut(),
                    preview.csv() == null
                            ? null
                            : CsvResponse.of(preview.csv(), preview.mappingMatchesSaved()));
        }

        @Override
        public String toString() {
            return "PreviewResponse[format=" + format + ", needs=" + needs + "]";
        }
    }

    /** {@code new} is a reserved word in Java and the natural key in JSON. */
    public record CountsResponse(
            int read,
            @JsonProperty("new") int fresh,
            int duplicate,
            int suspected,
            int unreadable,
            int zero) {

        static CountsResponse of(ImportPreview.Counts counts) {
            return new CountsResponse(
                    counts.read(),
                    counts.fresh(),
                    counts.duplicate(),
                    counts.suspected(),
                    counts.unreadable(),
                    counts.zero());
        }
    }

    /**
     * The mapping step's view of a CSV.
     *
     * @param mappingMatchesSaved whether the header is the one the account's saved mapping was made
     *     against; {@code null} when there is no saved mapping or no header to compare
     */
    public record CsvResponse(
            int columnCount,
            List<String> headerLabels,
            List<List<String>> firstRecords,
            SuggestionResponse suggested,
            Boolean mappingMatchesSaved) {

        static CsvResponse of(CsvShape shape, Boolean mappingMatchesSaved) {
            return new CsvResponse(
                    shape.columnCount(),
                    shape.headerLabels(),
                    shape.firstRecords(),
                    SuggestionResponse.of(shape.suggested()),
                    mappingMatchesSaved);
        }

        @Override
        public String toString() {
            return "CsvResponse[columns=" + columnCount + "]";
        }
    }

    public record SuggestionResponse(
            String delimiter,
            int headerRow,
            String dateFormat,
            List<String> dateFormatCandidates,
            String decimalSeparator,
            String groupingSeparator,
            Map<String, CsvColumnRole> columns) {

        static SuggestionResponse of(CsvSuggestion suggested) {
            Map<String, CsvColumnRole> columns = new LinkedHashMap<>();
            suggested.columns().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> columns.put(entry.getKey().toString(), entry.getValue()));
            return new SuggestionResponse(
                    String.valueOf(suggested.delimiter()),
                    suggested.headerRow(),
                    suggested.dateFormat() == null ? null : suggested.dateFormat().pattern(),
                    suggested.dateFormatCandidates().stream()
                            .map(ImportDateFormat::pattern)
                            .toList(),
                    String.valueOf(suggested.decimalSeparator()),
                    suggested.groupingSeparator() == null
                            ? null
                            : suggested.groupingSeparator().toString(),
                    columns);
        }
    }

    /** One committed import. No file name: none is stored (decision 4). */
    public record BatchResponse(
            UUID id,
            UUID accountId,
            String accountName,
            ImportFormat format,
            int imported,
            int duplicate,
            int excluded,
            int unreadable,
            int zero,
            UUID importedBy,
            Instant createdAt,
            long removable) {

        static BatchResponse of(ImportBatchView view) {
            return new BatchResponse(
                    view.id(),
                    view.accountId(),
                    view.accountName(),
                    view.format(),
                    view.counts().imported(),
                    view.counts().duplicate(),
                    view.counts().excluded(),
                    view.counts().unreadable(),
                    view.counts().zero(),
                    view.importedBy(),
                    view.createdAt(),
                    view.removable());
        }

        @Override
        public String toString() {
            return "BatchResponse[id=" + id + "]";
        }
    }

    public record HistoryResponse(
            List<BatchResponse> content, int page, int size, long totalElements, int totalPages) {

        static HistoryResponse of(ImportHistoryService.HistoryPage page) {
            return new HistoryResponse(
                    page.content().stream().map(BatchResponse::of).toList(),
                    page.page(),
                    page.size(),
                    page.totalElements(),
                    page.totalPages());
        }
    }

    public record MappingResponse(
            UUID accountId,
            String delimiter,
            String charset,
            int headerRow,
            String dateFormat,
            String decimalSeparator,
            String groupingSeparator,
            boolean invertSign,
            Map<String, CsvColumnRole> columns,
            String directionIn,
            String directionOut,
            Map<String, String> labels,
            Instant updatedAt,
            long version) {

        static MappingResponse of(ImportMappingView view) {
            return new MappingResponse(
                    view.accountId(),
                    view.delimiter(),
                    view.charset(),
                    view.headerRow(),
                    view.dateFormat(),
                    view.decimalSeparator(),
                    view.groupingSeparator(),
                    view.invertSign(),
                    view.columns(),
                    view.directionIn(),
                    view.directionOut(),
                    view.labels(),
                    view.updatedAt(),
                    view.version());
        }

        @Override
        public String toString() {
            return "MappingResponse[accountId=" + accountId + "]";
        }
    }
}
