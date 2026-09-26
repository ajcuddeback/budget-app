package com.budgetowl.auth.persistence;

import com.budgetowl.auth.domain.PasswordCredential;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The only route to {@code users.password_hash} in the application.
 *
 * <p>Narrow on purpose, and named so that reaching for it is a decision rather than an accident.
 * Even so, nothing it returns exposes the hash: {@link PasswordCredential} takes a password in and
 * gives a boolean back.
 */
public interface PasswordCredentialRepository extends Repository<PasswordCredential, UUID> {

    Optional<PasswordCredential> findById(UUID userId);

    /**
     * The login path. Cast for the same reason as {@code UserAccountRepository.findByEmail} —
     * without it, {@code citext} is compared case-sensitively and a correct password is rejected
     * because of a capital letter in the address.
     */
    @Query(
            value =
                    """
                    SELECT id, password_hash
                    FROM users
                    WHERE email = CAST(:email AS citext)
                    """,
            nativeQuery = true)
    Optional<PasswordCredential> findByEmail(@Param("email") String email);

    /**
     * Sets a user's password without loading anything.
     *
     * @param encodedPassword output of a {@code PasswordEncoder}. A plaintext value is refused by
     *     {@code ck_users_password_hash_encoded}, so this cannot quietly store a credential in the
     *     clear.
     * @return 1 when the user exists, 0 otherwise
     */
    @Modifying
    @Query(
            """
            update PasswordCredential c
               set c.passwordHash = :encodedPassword
             where c.userId = :userId
            """)
    int updatePassword(
            @Param("userId") UUID userId, @Param("encodedPassword") String encodedPassword);
}
