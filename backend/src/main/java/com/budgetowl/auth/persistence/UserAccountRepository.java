package com.budgetowl.auth.persistence;

import com.budgetowl.auth.domain.UserAccount;
import com.budgetowl.auth.domain.UserSummary;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Users.
 *
 * <p>Extends the bare {@link Repository} marker rather than {@code JpaRepository} on purpose. The
 * convenience interfaces inherit {@code findAll}, {@code deleteAll} and friends, and a repository
 * that offers them is a repository somebody will use them from; every method a caller can reach is
 * written out below and no other exists.
 *
 * <p>Nothing here can return a password hash. {@link UserAccount} has no field for one and {@link
 * UserSummary} has no component for one — the guarantee is in the type, not in an annotation
 * somebody has to remember.
 */
public interface UserAccountRepository extends Repository<UserAccount, UUID> {

    UserAccount save(UserAccount user);

    void delete(UserAccount user);

    Optional<UserAccount> findById(UUID id);

    /**
     * Looks a user up by email, case-insensitively.
     *
     * <p>Native, and cast, for a reason that costs an hour to rediscover: {@code users.email} is
     * {@code citext}, but JDBC binds a Java {@code String} as {@code varchar}, and PostgreSQL
     * resolves {@code citext = varchar} by casting both to {@code text} — which compares
     * case-SENSITIVELY. A derived {@code findByEmail} therefore looks right, passes a naive test
     * that uses matching case, and fails to find "Alice@example.com" for a user stored as
     * "alice@example.com". The explicit cast is what makes the column's type mean what it says.
     * Parameterized, never concatenated.
     */
    @Query(
            value =
                    """
                    SELECT id, email, display_name, status, is_instance_admin, created_at, updated_at
                    FROM users
                    WHERE email = CAST(:email AS citext)
                    """,
            nativeQuery = true)
    Optional<UserAccount> findByEmail(@Param("email") String email);

    /** See {@link #findByEmail} on why this is cast. */
    @Query(
            value = "SELECT EXISTS (SELECT 1 FROM users WHERE email = CAST(:email AS citext))",
            nativeQuery = true)
    boolean existsByEmail(@Param("email") String email);

    /**
     * Whether this is a fresh instance. The {@code /api/setup/first-user} endpoint may not rely on
     * this answer alone — between reading it and inserting, another caller can win. It is the cheap
     * check; {@code InstanceSettingsRepository.claimFirstUserSetup} is the correct one.
     */
    long count();

    @Query(
            """
            select new com.budgetowl.auth.domain.UserSummary(
                u.id, u.email, u.displayName, u.status, u.instanceAdmin, u.createdAt)
            from UserAccount u
            where u.id = :id
            """)
    Optional<UserSummary> findSummaryById(@Param("id") UUID id);
}
