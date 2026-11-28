package com.budgetowl.imports.persistence;

import com.budgetowl.imports.domain.ImportMapping;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Saved CSV mappings, one per account. Every method is household-scoped. */
public interface ImportMappingRepository extends Repository<ImportMapping, UUID> {

    ImportMapping saveAndFlush(ImportMapping mapping);

    void delete(ImportMapping mapping);

    Optional<ImportMapping> findByAccountIdAndHouseholdId(UUID accountId, UUID householdId);
}
