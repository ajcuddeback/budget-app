package com.budgetowl.imports.domain;

import com.budgetowl.account.domain.Account;
import com.budgetowl.common.HouseholdOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One account's saved CSV mapping (docs/features/file-import.md rule 5, "A saved mapping").
 *
 * <p><b>Settings and the bank's column labels, never a cell value.</b> The labels are kept so the
 * next file's header can be compared with them: if the bank changed its export format, the preview
 * says so rather than applying the mapping silently. The settings columns are checked by the
 * database against the same allowlists the API uses.
 *
 * <p>The roles, direction values and labels live in {@code columns}, a bounded {@code jsonb} object
 * the service writes and reads; this entity does not interpret it.
 */
@Entity
@Table(name = "import_mappings")
public class ImportMapping implements HouseholdOwned {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "delimiter", nullable = false)
    private String delimiter;

    @Column(name = "charset")
    private String charset;

    @Column(name = "date_format", nullable = false)
    private String dateFormat;

    @Column(name = "decimal_separator", nullable = false)
    private String decimalSeparator;

    @Column(name = "grouping_separator")
    private String groupingSeparator;

    /** {@code smallint} in the schema — see docs/memory/gotchas.md on schema validation. */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "header_row", nullable = false)
    private Integer headerRow;

    @Column(name = "invert_sign", nullable = false)
    private boolean invertSign;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns", nullable = false)
    private String columns;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ImportMapping() {
        // Hibernate.
    }

    /** A first mapping for this account. */
    public static ImportMapping forAccount(Account account) {
        ImportMapping mapping = new ImportMapping();
        mapping.householdId = Objects.requireNonNull(account, "account").householdId();
        mapping.accountId = account.id();
        return mapping;
    }

    /**
     * Replaces every setting at once: a mapping is saved whole, as the person confirmed it.
     *
     * @param columnsJson the service's serialisation of roles, direction values and labels
     */
    public void replace(
            CsvMapping mapping, String charsetName, boolean invert, String columnsJson) {
        Objects.requireNonNull(mapping, "mapping");
        if (mapping.dateFormat() == null) {
            throw new IllegalArgumentException("a saved mapping names its date format");
        }
        this.delimiter = String.valueOf(mapping.delimiter());
        this.charset = charsetName;
        this.dateFormat = mapping.dateFormat().pattern();
        this.decimalSeparator = String.valueOf(mapping.decimalSeparator());
        this.groupingSeparator =
                mapping.groupingSeparator() == null ? null : mapping.groupingSeparator().toString();
        this.headerRow = mapping.headerRow();
        this.invertSign = invert;
        this.columns = Objects.requireNonNull(columnsJson, "columnsJson");
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID householdId() {
        return householdId;
    }

    public UUID accountId() {
        return accountId;
    }

    public char delimiter() {
        return delimiter.charAt(0);
    }

    public String charset() {
        return charset;
    }

    public String dateFormat() {
        return dateFormat;
    }

    public char decimalSeparator() {
        return decimalSeparator.charAt(0);
    }

    public Character groupingSeparator() {
        return groupingSeparator == null ? null : groupingSeparator.charAt(0);
    }

    public int headerRow() {
        return headerRow;
    }

    public boolean invertSign() {
        return invertSign;
    }

    public String columnsJson() {
        return columns;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    /** The bank's labels are file content; print ids only. */
    @Override
    public String toString() {
        return "ImportMapping[id=" + id + "]";
    }
}
