package com.budgetowl.auth.persistence;

import com.budgetowl.auth.domain.AuthToken;
import com.budgetowl.auth.domain.AuthTokenSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Opaque bearer tokens (ADR-0018).
 *
 * <p>Every read is scoped by the owning user: a member may revoke their own devices and nobody
 * else's, so {@code findById} does not exist and {@code findByIdAndUserId} is the shape.
 */
public interface AuthTokenRepository extends Repository<AuthToken, UUID> {

    AuthToken save(AuthToken token);

    /**
     * The authentication path, on every single mobile request. An index probe on the hash, not a
     * comparison of a secret — which is why timing attacks on token comparison were ruled out.
     *
     * <p>The user is fetched in the same query: this runs per request, and a lazy proxy resolved a
     * moment later would be an N+1 on the hottest path in the application.
     */
    @EntityGraph(attributePaths = "user")
    Optional<AuthToken> findByTokenHash(String tokenHash);

    /** Revoking one device. Scoped, so a caller cannot revoke somebody else's session. */
    Optional<AuthToken> findByIdAndUserId(UUID id, UUID userId);

    @Query(
            """
            select new com.budgetowl.auth.domain.AuthTokenSummary(
                t.id, t.deviceLabel, t.createdAt, t.lastUsedAt, t.expiresAt, t.revokedAt)
            from AuthToken t
            where t.user.id = :userId
            order by t.createdAt desc
            """)
    List<AuthTokenSummary> findSummariesByUserId(@Param("userId") UUID userId);

    /**
     * Revokes every live token of one user, in one statement.
     *
     * <p>Used when a member is removed from the household and when they change their password. It
     * has to be immediate — "a removed member keeps using a mobile token" is only mitigated if the
     * very next request fails.
     *
     * @return how many were revoked
     */
    @Modifying
    @Query(
            """
            update AuthToken t
               set t.revokedAt = :revokedAt
             where t.user.id = :userId
               and t.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);

    long countByUserIdAndRevokedAtIsNull(UUID userId);
}
