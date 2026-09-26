package com.budgetowl.instance.persistence;

import com.budgetowl.instance.domain.InstanceSettings;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The one row of {@link InstanceSettings}, created by migration {@code V6}. */
public interface InstanceSettingsRepository extends Repository<InstanceSettings, Short> {

    InstanceSettings save(InstanceSettings settings);

    @Query("select s from InstanceSettings s where s.id = 1")
    Optional<InstanceSettings> findCurrent();

    /**
     * Claims first-run setup, once, for the whole life of the instance.
     *
     * <p>This is the database-level means the threat model asks for against <em>"an attacker finds
     * an internet-facing fresh-looking instance and POSTs {@code /api/setup/first-user} to become
     * its administrator"</em>. Reading "are there any users yet?" and then inserting cannot be safe
     * however carefully it is written: between the read and the insert, a second caller can do the
     * same.
     *
     * <p>A conditional UPDATE of a single row cannot race. The second caller blocks on the row
     * lock; when the first commits, PostgreSQL re-evaluates {@code setup_completed_at is null}
     * against the newly committed tuple, matches nothing, and returns 0. Call this <em>first</em>,
     * in the same transaction as the insert, and treat 0 as "setup is already done" — do not create
     * a user, a household or a session.
     *
     * <p>Two further schema-level backstops make a second administrator impossible even if a future
     * caller forgets this method: {@code uq_households_singleton} permits one household ever, and
     * {@code uq_users_single_instance_admin} permits one instance administrator ever.
     *
     * @return 1 for the single caller that won, 0 for everyone else
     */
    @Modifying
    @Query(
            """
            update InstanceSettings s
               set s.setupCompletedAt = :completedAt
             where s.id = 1
               and s.setupCompletedAt is null
            """)
    int claimFirstUserSetup(@Param("completedAt") Instant completedAt);
}
