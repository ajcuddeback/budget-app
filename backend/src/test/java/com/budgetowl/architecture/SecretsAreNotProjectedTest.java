package com.budgetowl.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>No query selects a password hash or a token hash into anything.</b>
 *
 * <p>{@code SecretsAreUnreadableTest} proves the <em>objects</em> cannot give a secret up: {@link
 * com.budgetowl.auth.domain.PasswordCredential} has no accessor for its hash, {@link
 * com.budgetowl.auth.domain.AuthToken} none for its token, and no DTO has a field one could land
 * in. That is real and it is the better half of the guarantee, because it is structural.
 *
 * <p>It is not the whole guarantee, and the feature doc used to claim it was. {@code passwordHash}
 * and {@code tokenHash} are persistent attributes, so one line of JPQL —
 *
 * <pre>{@code select c.passwordHash from PasswordCredential c where c.userId = :id}</pre>
 *
 * <p>— returns a {@code String} to whoever asked for it, and no amount of reflection over instances
 * or Jackson serialisation can see a query written in an annotation. Nothing does this today. This
 * test is what keeps that true, by reading the queries themselves.
 *
 * <p>A source-level test rather than a bytecode one on purpose: the query is a string constant in
 * an annotation, which is exactly where it is easiest to add a projection and hardest to notice
 * one. Mentioning a hash in a {@code WHERE} (the login lookup) or in a {@code SET} (a password
 * change) is fine — that is using the column, not handing it out. Selecting it is not.
 */
class SecretsAreNotProjectedTest {

    /** The one query that legitimately selects the column, because it hydrates the entity. */
    private static final String THE_ENTITY_HYDRATING_LOGIN_QUERY =
            "SELECT id, password_hash FROM users WHERE email = CAST(:email AS citext)";

    private static final List<String> SECRETS =
            List.of("passwordhash", "password_hash", "tokenhash", "token_hash");

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void noPersistenceQuerySelectsASecretIntoAProjection() {
        List<Path> sources = persistenceSources();
        assertThat(sources)
                .as("the scan must actually find the repositories, or it proves nothing")
                .isNotEmpty();

        List<String> offenders = new ArrayList<>();
        int queriesRead = 0;
        int hydratingQueries = 0;

        for (Path source : sources) {
            for (String query : queriesIn(read(source))) {
                queriesRead++;
                if (query.equals(THE_ENTITY_HYDRATING_LOGIN_QUERY)) {
                    // Allowed, and pinned to its exact text: the moment this query changes shape
                    // it stops being allowed and somebody has to look at it again.
                    assertThat(source.getFileName().toString())
                            .isEqualTo("PasswordCredentialRepository.java");
                    hydratingQueries++;
                    continue;
                }
                projectedSecret(query)
                        .ifPresent(
                                secret ->
                                        offenders.add(
                                                source.getFileName()
                                                        + " projects "
                                                        + secret
                                                        + ": "
                                                        + query));
            }
        }

        assertThat(queriesRead)
                .as("queries were found and parsed, rather than the extraction silently failing")
                .isGreaterThan(5);
        assertThat(hydratingQueries)
                .as("the one allowed query still exists; if it was renamed, review it")
                .isEqualTo(1);
        assertThat(offenders)
                .as(
                        "a hash may be matched in a WHERE and written in a SET, never selected"
                                + " into a result. See docs/features/authentication-and-households.md")
                .isEmpty();
    }

    /**
     * @return the secret this query hands back, if it hands one back
     */
    private static java.util.Optional<String> projectedSecret(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<String> handedBack = new ArrayList<>();

        if (lower.startsWith("select")) {
            int from = lower.indexOf(" from ");
            handedBack.add(from < 0 ? lower : lower.substring(0, from));
        }
        int returning = lower.indexOf(" returning ");
        if (returning >= 0) {
            handedBack.add(lower.substring(returning));
        }

        return handedBack.stream()
                .flatMap(region -> SECRETS.stream().filter(region::contains))
                .findFirst();
    }

    /**
     * Every string literal in the file that looks like a query — text blocks first, then ordinary
     * literals. Deliberately wider than "the value of an {@code @Query} annotation": a query
     * assembled in a constant or handed to an {@code EntityManager} is the same disclosure.
     */
    private static List<String> queriesIn(String source) {
        List<String> queries = new ArrayList<>();
        StringBuilder withoutTextBlocks = new StringBuilder();
        Matcher blocks = TEXT_BLOCK.matcher(source);
        int cursor = 0;
        while (blocks.find()) {
            withoutTextBlocks.append(source, cursor, blocks.start());
            queries.add(normalize(blocks.group(1)));
            cursor = blocks.end();
        }
        withoutTextBlocks.append(source.substring(cursor));

        Matcher literals = STRING_LITERAL.matcher(withoutTextBlocks);
        while (literals.find()) {
            queries.add(normalize(literals.group(1)));
        }
        return queries.stream().filter(SecretsAreNotProjectedTest::looksLikeAQuery).toList();
    }

    private static boolean looksLikeAQuery(String candidate) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        return lower.startsWith("select")
                || lower.startsWith("update ")
                || lower.startsWith("delete ")
                || lower.startsWith("insert ")
                || lower.startsWith("from ");
    }

    private static String normalize(String literal) {
        return literal.replaceAll("\\s+", " ").strip();
    }

    private static List<Path> persistenceSources() {
        Path root = Path.of("src/main/java/com/budgetowl");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.getParent().endsWith("persistence"))
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
