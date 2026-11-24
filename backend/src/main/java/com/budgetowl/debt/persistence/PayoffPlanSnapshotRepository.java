package com.budgetowl.debt.persistence;

import com.budgetowl.debt.domain.PayoffPlanSnapshot;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * What the plan projected when it last changed.
 *
 * <p><b>Write-once, and that is the whole interface.</b> There is no update and no delete: a
 * snapshot that could be rewritten is not a record of what was true, and one that can be deleted is
 * a comparison that quietly disappears. There is no read either — slice 10 takes the snapshot so
 * that <em>"4 months earlier than last month's plan"</em> has something to compare against, and the
 * screen that renders the delta is a later slice. A reader added now would be a query nothing calls
 * and nothing tests.
 */
public interface PayoffPlanSnapshotRepository extends Repository<PayoffPlanSnapshot, UUID> {

    PayoffPlanSnapshot save(PayoffPlanSnapshot snapshot);
}
