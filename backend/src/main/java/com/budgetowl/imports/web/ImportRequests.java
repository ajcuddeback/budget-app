package com.budgetowl.imports.web;

import com.budgetowl.imports.domain.CsvColumnRole;
import com.budgetowl.imports.domain.ImportFormat;
import com.budgetowl.imports.service.ImportSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request bodies for file import (docs/features/file-import.md, "Request").
 *
 * <p><b>The file is a base64 string in JSON</b>, never {@code multipart/form-data}: Spring's
 * multipart support spools parts to {@code java.io.tmpdir} by default, and an imported statement
 * must never touch the disk (ADR-0038). <b>There is no file-name field</b> (decision 4), and an
 * unknown property is refused, so a client cannot send one.
 *
 * <p><b>The {@code file} field has no {@code @Size} and no {@code @Pattern}.</b> A constraint
 * violation puts the rejected value into {@code ExceptionHandlerExceptionResolver}'s log line, and
 * a regex over seven megabytes is its own cost. The size is enforced on the body before it is read
 * ({@code ImportBodyLimitFilter}) and again after decoding; decoding uses the strict base64
 * decoder.
 *
 * <p><b>Every {@code toString} is redacted</b> and prints the file's length, never its content:
 * Spring MVC logs the deserialised body at {@code DEBUG}.
 */
public final class ImportRequests {

    private ImportRequests() {}

    /** One character from a fixed set — validated again, as a whole, by the service. */
    private static final String SINGLE = "^.$";

    /**
     * The person's settings. All optional; which apply depends on the format, and the service
     * refuses any that do not rather than ignoring them.
     */
    public record ImportOptionsRequest(
            @Size(max = 32) String charset,
            @Pattern(regexp = "^[,;\\t|]$", message = "must be one of , ; tab |") String delimiter,
            @Min(0) @Max(20) Integer headerRow,
            @Size(max = 16) String dateFormat,
            @Pattern(regexp = "^[.,]$", message = "must be . or ,") String decimalSeparator,
            @Pattern(regexp = SINGLE, message = "must be a single character")
                    String groupingSeparator,
            Boolean invertSign,
            @Size(max = 64)
                    Map<@Pattern(regexp = "^[0-9]{1,2}$") String, @NotNull CsvColumnRole> columns,
            @Size(max = 32) String directionIn,
            @Size(max = 32) String directionOut,
            Boolean saveMapping) {

        ImportSettings settings() {
            return new ImportSettings(
                    charset,
                    delimiter,
                    headerRow,
                    dateFormat,
                    decimalSeparator,
                    groupingSeparator,
                    invertSign,
                    columns,
                    directionIn,
                    directionOut,
                    saveMapping);
        }

        @Override
        public String toString() {
            return "ImportOptionsRequest[columns=" + (columns == null ? 0 : columns.size()) + "]";
        }
    }

    public record PreviewImportRequest(
            @NotNull UUID accountId,
            @NotNull ImportFormat format,
            @NotNull String file,
            @Valid ImportOptionsRequest options) {

        /** The file's length, never the file. */
        @Override
        public String toString() {
            return "PreviewImportRequest[format="
                    + format
                    + ", fileLength="
                    + (file == null ? 0 : file.length())
                    + "]";
        }
    }

    /** What to do with suspected duplicates by default (decision 3: exclude). */
    public enum SuspectedChoice {
        EXCLUDE,
        INCLUDE
    }

    /** One suspected duplicate, flipped from the default. */
    public record OverrideRequest(@NotNull @Min(1) Integer row, @NotNull Boolean include) {}

    /**
     * The same file and options again, plus the decisions and the digest of the preview the person
     * saw (ADR-0037).
     *
     * @param overrides at most the row cap, and only for suspected rows — naming an exact duplicate
     *     or an unreadable row is {@code 400}
     */
    public record CommitImportRequest(
            @NotNull UUID accountId,
            @NotNull ImportFormat format,
            @NotNull String file,
            @Valid ImportOptionsRequest options,
            @NotNull
                    @Pattern(
                            regexp = "^[0-9a-f]{64}$",
                            message = "must be 64 lowercase hex characters")
                    String previewDigest,
            SuspectedChoice suspectedDuplicates,
            @Size(max = 10_000) List<@Valid @NotNull OverrideRequest> overrides) {

        @Override
        public String toString() {
            return "CommitImportRequest[format="
                    + format
                    + ", fileLength="
                    + (file == null ? 0 : file.length())
                    + ", overrides="
                    + (overrides == null ? 0 : overrides.size())
                    + "]";
        }
    }

    /**
     * A saved mapping, replaced whole.
     *
     * @param headerLabels the file's header, so the next import can tell whether the bank changed
     *     its export format
     * @param version required when replacing an existing mapping
     */
    public record SaveMappingRequest(
            @Size(max = 32) String charset,
            @NotNull @Pattern(regexp = "^[,;\\t|]$", message = "must be one of , ; tab |")
                    String delimiter,
            @NotNull @Min(0) @Max(20) Integer headerRow,
            @NotNull @Size(max = 16) String dateFormat,
            @NotNull @Pattern(regexp = "^[.,]$", message = "must be . or ,")
                    String decimalSeparator,
            @Pattern(regexp = SINGLE, message = "must be a single character")
                    String groupingSeparator,
            Boolean invertSign,
            @NotNull @Size(max = 64)
                    Map<@Pattern(regexp = "^[0-9]{1,2}$") String, @NotNull CsvColumnRole> columns,
            @Size(max = 32) String directionIn,
            @Size(max = 32) String directionOut,
            @Size(max = 64) List<@NotNull @Size(max = 256) String> headerLabels,
            Long version) {

        ImportSettings settings() {
            return new ImportSettings(
                    charset,
                    delimiter,
                    headerRow,
                    dateFormat,
                    decimalSeparator,
                    groupingSeparator,
                    invertSign,
                    columns,
                    directionIn,
                    directionOut,
                    null);
        }

        @Override
        public String toString() {
            return "SaveMappingRequest[version=" + version + "]";
        }
    }
}
