package com.budgetowl.household.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.auth.service.AuthRateLimiter;
import com.budgetowl.common.OpaqueToken;
import com.budgetowl.household.service.AcceptedInvitation;
import com.budgetowl.household.service.InvitationService;
import com.budgetowl.persistence.PersistenceTestBase;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * <b>An invitation link can be used once.</b> Including when it is presented twice at the same
 * instant.
 *
 * <p>The link is the credential: whoever holds it joins the household, and it is shared through
 * whatever channel the owner had to hand — a chat, an email, a note. So a forwarded or leaked link
 * being usable a second time is the failure this test exists for, and "the service checks {@code
 * accepted_at} before writing it" is not a defence. Under READ COMMITTED both attempts read the
 * invitation before either writes it, both see it unused, and both join: one link, two memberships.
 *
 * <p>The other two invariants of this feature were given database-level anti-race treatment in
 * slice 2 — the last-owner rule has a deferred constraint trigger, first-run setup has a
 * conditional UPDATE with a rowcount. This one had read-check-write, and survived only because
 * {@code InvitationService} pins the new user's address to the invitation's, so the racers collided
 * on {@code uq_users_email} or {@code uq_household_members_household_id_user_id} instead. That is
 * two unrelated constraints doing this one's job, and it stops working the moment a joiner may name
 * their own address or accept while signed in.
 *
 * <p>So acceptance now claims the invitation the same way setup is claimed: {@code
 * HouseholdInvitationRepository.markAccepted}, a conditional UPDATE of one row, taken first.
 *
 * <p>Repeated, because a concurrency test that passed once has told you very little.
 */
@SpringBootTest
class InvitationSingleUseIT extends PersistenceTestBase {

    private static final String JOINER_EMAIL = "chris@example.com";
    private static final String JOINER_SECRET = "chris-example-passphrase";

    @Autowired private HouseholdInvitationRepository invitations;

    @Autowired private InvitationService invitationService;

    @Autowired private AuthRateLimiter rateLimiter;

    private UUID household;
    private UUID owner;
    private UUID invitation;
    private OpaqueToken token;

    @BeforeEach
    void seedAPendingInvitation() {
        rateLimiter.clearAll();
        owner = UUID.randomUUID();
        household = UUID.randomUUID();
        invitation = UUID.randomUUID();
        token = OpaqueToken.generate();

        transaction.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                            owner,
                            "ada@example.com",
                            "Ada");
                    jdbc.update(
                            "INSERT INTO households (id, name, base_currency) VALUES (?, ?, ?)",
                            household,
                            "Home",
                            "GBP");
                    jdbc.update(
                            """
                            INSERT INTO household_members (id, household_id, user_id, role)
                            VALUES (?, ?, ?, 'OWNER')
                            """,
                            UUID.randomUUID(),
                            household,
                            owner);
                    jdbc.update(
                            """
                            INSERT INTO household_invitations
                                (id, household_id, email, role, token_hash, expires_at, created_by)
                            VALUES (?, ?, ?, 'MEMBER', ?, ?, ?)
                            """,
                            invitation,
                            household,
                            JOINER_EMAIL,
                            token.sha256Hex(),
                            OffsetDateTime.now().plusDays(7),
                            owner);
                });
    }

    @RepeatedTest(5)
    void exactlyOneOfTwoSimultaneousClaimsWins() throws Exception {
        List<Integer> claimed =
                runSimultaneously(this::claimTheInvitation, this::claimTheInvitation);

        assertThat(claimed)
                .as("one caller spends the invitation; the other is told nothing usable exists")
                .containsExactlyInAnyOrder(1, 0);
        assertThat(acceptedAt()).isNotNull();
    }

    @RepeatedTest(5)
    void twoSimultaneousAcceptancesProduceOneMembership() throws Exception {
        // The whole path, not just its guard: two holders of one link, released together.
        List<Optional<AcceptedInvitation>> outcomes =
                runSimultaneously(this::acceptTheLink, this::acceptTheLink);

        assertThat(outcomes.stream().filter(Optional::isPresent).count())
                .as("one acceptance succeeds")
                .isEqualTo(1);
        assertThat(memberCount()).isEqualTo(2L);
        assertThat(usersWith(JOINER_EMAIL)).isEqualTo(1L);
        assertThat(acceptedAt()).isNotNull();
    }

    @Test
    void aClaimedInvitationCannotBeClaimedAgain() {
        int first = claimTheInvitation();
        int second = claimTheInvitation();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    @Test
    void aRevokedInvitationCannotBeClaimed() {
        jdbc.update("UPDATE household_invitations SET revoked_at = now() WHERE id = ?", invitation);

        assertThat(claimTheInvitation()).isZero();
    }

    @Test
    void anInvitationThatNeverExistedCannotBeClaimed() {
        // Same rowcount, same answer at the edge. Expired, revoked, used and unknown are one
        // response, or the link's history leaks to whoever found it.
        int claimed = claim(UUID.randomUUID());

        assertThat(claimed).isZero();
    }

    // ---------------------------------------------------------------------------------- helpers

    private int claimTheInvitation() {
        return claim(invitation);
    }

    private int claim(UUID invitationId) {
        Integer claimed =
                transaction.execute(
                        status -> invitations.markAccepted(invitationId, Instant.now()));
        return claimed == null ? 0 : claimed;
    }

    /**
     * No outer transaction: {@code InvitationService.accept} is {@code @Transactional} and must be
     * allowed to own the one it races in, exactly as it does behind the endpoint.
     */
    private Optional<AcceptedInvitation> acceptTheLink() {
        try {
            return Optional.of(
                    invitationService.accept(
                            token.value(), "Chris", JOINER_SECRET, null, "203.0.113.9"));
        } catch (RuntimeException refused) {
            return Optional.empty();
        }
    }

    private <T> List<T> runSimultaneously(Callable<T> first, Callable<T> second) throws Exception {
        CyclicBarrier ready = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<T> a = threads.submit(released(first, ready));
            Future<T> b = threads.submit(released(second, ready));
            List<T> outcomes = new ArrayList<>();
            outcomes.add(a.get(30, TimeUnit.SECONDS));
            outcomes.add(b.get(30, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            threads.shutdownNow();
        }
    }

    /** Each attempt on its own thread, and therefore its own transaction, released together. */
    private <T> Callable<T> released(Callable<T> work, CyclicBarrier ready) {
        return () -> {
            ready.await(30, TimeUnit.SECONDS);
            return work.call();
        };
    }

    private Instant acceptedAt() {
        OffsetDateTime accepted =
                jdbc.queryForObject(
                        "SELECT accepted_at FROM household_invitations WHERE id = ?",
                        OffsetDateTime.class,
                        invitation);
        return accepted == null ? null : accepted.toInstant();
    }

    private long memberCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM household_members WHERE household_id = ?",
                Long.class,
                household);
    }

    private long usersWith(String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = CAST(? AS citext)", Long.class, email);
    }
}
