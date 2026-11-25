package com.budgetowl.imports.service;

import com.budgetowl.account.domain.Account;
import com.budgetowl.account.persistence.AccountRepository;
import com.budgetowl.common.ConflictException;
import com.budgetowl.common.ErrorCode;
import com.budgetowl.common.NotFoundException;
import com.budgetowl.household.domain.HouseholdMember;
import com.budgetowl.household.service.MembershipService;
import com.budgetowl.imports.domain.CsvColumnRole;
import com.budgetowl.imports.domain.CsvMapping;
import com.budgetowl.imports.domain.ImportFormat;
import com.budgetowl.imports.domain.ImportMapping;
import com.budgetowl.imports.domain.ReadOptions;
import com.budgetowl.imports.persistence.ImportMappingRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * One saved CSV mapping per account (docs/features/file-import.md rule 5, "A saved mapping").
 *
 * <p>Household-scoped like everything else: the account is resolved against the caller's
 * membership, so another household's account is a {@code 404}; and every write needs {@code OWNER}
 * or {@code MEMBER}, checked before the account is looked up so a {@code VIEWER} gets {@code 403}
 * rather than learning whether an id exists.
 */
@Service
public class ImportMappingService {

    private static final Logger log = LoggerFactory.getLogger(ImportMappingService.class);

    private final ImportMappingRepository mappings;
    private final AccountRepository accounts;
    private final MembershipService memberships;
    private final ObjectMapper json;

    public ImportMappingService(
            ImportMappingRepository mappings,
            AccountRepository accounts,
            MembershipService memberships,
            ObjectMapper json) {
        this.mappings = mappings;
        this.accounts = accounts;
        this.memberships = memberships;
        this.json = json;
    }

    /** A mapping to save, as the person confirmed it. */
    public record MappingChanges(ImportSettings settings, List<String> headerLabels, Long version) {

        @Override
        public String toString() {
            return "MappingChanges[version=" + version + "]";
        }
    }

    @Transactional(readOnly = true)
    public ImportMappingView get(UUID actorId, UUID accountId) {
        HouseholdMember membership = memberships.requireMembership(actorId);
        UUID householdId = membership.household().id();
        Account account = requireAccount(accountId, householdId);
        return mappings.findByAccountIdAndHouseholdId(account.id(), householdId)
                .map(this::view)
                .orElseThrow(() -> new NotFoundException("no saved mapping"));
    }

    /**
     * Saves or replaces the mapping. Replacing one needs the {@code version} the caller read.
     *
     * @throws ConflictException {@code stale-version}
     */
    @Transactional
    public ImportMappingView put(UUID actorId, UUID accountId, MappingChanges changes) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Account account = requireAccount(accountId, householdId);

        ReadOptions options =
                ImportSettingsReader.read(ImportFormat.CSV, changes.settings(), null, "");
        CsvMapping mapping = options.mapping();
        if (mapping == null) {
            throw ImportSettingsReader.invalid("columns", "a mapping maps columns");
        }
        if (mapping.dateFormat() == null) {
            throw ImportSettingsReader.invalid(
                    "dateFormat", "a saved mapping names its date format");
        }
        Optional<ImportMapping> existing =
                mappings.findByAccountIdAndHouseholdId(account.id(), householdId);
        if (existing.isPresent()
                && (changes.version() == null || existing.get().version() != changes.version())) {
            throw new ConflictException(
                    ErrorCode.STALE_VERSION, "the saved mapping changed since it was read");
        }
        ImportMapping saved =
                save(
                        account,
                        existing,
                        mapping,
                        options.charset(),
                        options.invertSign(),
                        changes.headerLabels());
        log.info(
                "import mapping saved householdId={} accountId={} byUserId={}",
                householdId,
                account.id(),
                actorId);
        return view(saved);
    }

    @Transactional
    public void delete(UUID actorId, UUID accountId) {
        HouseholdMember membership = memberships.requireWriteAccess(actorId);
        UUID householdId = membership.household().id();
        Account account = requireAccount(accountId, householdId);
        ImportMapping mapping =
                mappings.findByAccountIdAndHouseholdId(account.id(), householdId)
                        .orElseThrow(() -> new NotFoundException("no saved mapping"));
        mappings.delete(mapping);
        log.info(
                "import mapping deleted householdId={} accountId={} byUserId={}",
                householdId,
                account.id(),
                actorId);
    }

    /**
     * Saved as part of a commit, inside its transaction, with whatever was there replaced: the
     * person has just confirmed this mapping by importing with it.
     */
    @Transactional
    public void saveWithCommit(
            Account account,
            CsvMapping mapping,
            String charset,
            boolean invert,
            List<String> labels) {
        save(
                account,
                mappings.findByAccountIdAndHouseholdId(account.id(), account.householdId()),
                mapping,
                charset,
                invert,
                labels);
    }

    /**
     * Whether a file's header matches the one the saved mapping was made against: empty when there
     * is no saved mapping, or no header to compare.
     */
    @Transactional(readOnly = true)
    public Optional<Boolean> matchesSaved(
            UUID householdId, UUID accountId, List<String> headerLabels) {
        Optional<ImportMapping> saved =
                mappings.findByAccountIdAndHouseholdId(accountId, householdId);
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        String signature = MappingColumns.decode(json, saved.get().columnsJson()).headerSignature();
        String current = MappingColumns.signature(headerLabels);
        if (signature == null || current == null) {
            return Optional.empty();
        }
        return Optional.of(signature.equals(current));
    }

    private ImportMapping save(
            Account account,
            Optional<ImportMapping> existing,
            CsvMapping mapping,
            String charset,
            boolean invert,
            List<String> labels) {
        ImportMapping target = existing.orElseGet(() -> ImportMapping.forAccount(account));
        target.replace(mapping, charset, invert, MappingColumns.encode(json, mapping, labels));
        return mappings.saveAndFlush(target);
    }

    private ImportMappingView view(ImportMapping mapping) {
        MappingColumns.Saved saved = MappingColumns.decode(json, mapping.columnsJson());
        Map<String, CsvColumnRole> columns = new LinkedHashMap<>();
        saved.roles().forEach((index, role) -> columns.put(index.toString(), role));
        Map<String, String> labels = new LinkedHashMap<>();
        saved.labels().forEach((index, label) -> labels.put(index.toString(), label));
        return new ImportMappingView(
                mapping.accountId(),
                String.valueOf(mapping.delimiter()),
                mapping.charset(),
                mapping.headerRow(),
                mapping.dateFormat(),
                String.valueOf(mapping.decimalSeparator()),
                mapping.groupingSeparator() == null ? null : mapping.groupingSeparator().toString(),
                mapping.invertSign(),
                columns,
                saved.directionIn(),
                saved.directionOut(),
                labels,
                mapping.updatedAt(),
                mapping.version());
    }

    private Account requireAccount(UUID accountId, UUID householdId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new NotFoundException("no such account"));
    }
}
