package com.budgetowl.category;

import static org.assertj.core.api.Assertions.assertThat;

import com.budgetowl.category.domain.CategoryKind;
import com.budgetowl.category.service.DefaultCategories;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The default category list exists twice — in {@link DefaultCategories} for new households, and in
 * {@code V11__categories.sql} for the instance that was already running when categories shipped —
 * and this is what stops the two drifting.
 *
 * <p><b>Why the duplication cannot be removed.</b> A migration cannot call application code, and
 * application code does not run for a household that already exists. Neither copy can read the
 * other. The honest options are to duplicate and test, or to duplicate and hope; this file is the
 * first one.
 *
 * <p><b>Why it parses the SQL rather than asserting a count.</b> A count passes while the names are
 * wrong, and the failure mode that matters is a household upgrading into a different vocabulary
 * from the one a fresh install gets — "Eating Out" in one and "Dining" in the other, with the user
 * guide's screenshots matching only one of them. So the test reads the migration's {@code VALUES}
 * rows and compares names, kinds and parentage.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DefaultCategoriesTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V11__categories.sql");

    /** {@code ('Income', 'INCOME', 1),} — a parent row in the migration's VALUES list. */
    private static final Pattern PARENT_ROW =
            Pattern.compile("\\('([^']+)',\\s*'(INCOME|EXPENSE)',\\s*\\d+\\)");

    /** {@code ('Bills', 'Utilities'),} — a child row. */
    private static final Pattern CHILD_ROW = Pattern.compile("\\('([^']+)',\\s*'([^']+)'\\)");

    @Test
    void the_migration_seeds_exactly_the_categories_a_new_household_gets() {
        String sql = read();

        Map<String, CategoryKind> parentsInSql =
                parentsIn(section(sql, "parents (name, kind, sort)"));
        Map<String, List<String>> childrenInSql =
                childrenIn(section(sql, "children (parent_name, name)"));

        Map<String, CategoryKind> parentsInCode = new LinkedHashMap<>();
        Map<String, List<String>> childrenInCode = new LinkedHashMap<>();
        for (DefaultCategories.Group group : DefaultCategories.ALL) {
            parentsInCode.put(group.name(), group.kind());
            if (!group.children().isEmpty()) {
                childrenInCode.put(group.name(), group.children());
            }
        }

        assertThat(parentsInSql)
                .as("top-level categories, with their kinds, must match DefaultCategories.ALL")
                .containsExactlyInAnyOrderEntriesOf(parentsInCode);

        assertThat(childrenInSql)
                .as("children, grouped by parent, must match DefaultCategories.ALL")
                .containsExactlyInAnyOrderEntriesOf(childrenInCode);
    }

    @Test
    void every_child_in_the_migration_names_a_parent_the_migration_also_creates() {
        String sql = read();
        Map<String, CategoryKind> parents = parentsIn(section(sql, "parents (name, kind, sort)"));

        assertThat(childrenIn(section(sql, "children (parent_name, name)")).keySet())
                .as("a child whose parent is not seeded would violate fk_categories_parent")
                .allSatisfy(parent -> assertThat(parents).containsKey(parent));
    }

    /**
     * The cap exists so the whole tree fits in one page; a default list that already filled it
     * would make that guarantee useless on day one.
     */
    @Test
    void the_default_list_is_comfortably_inside_the_per_household_cap() {
        long total =
                DefaultCategories.ALL.size()
                        + DefaultCategories.ALL.stream()
                                .mapToLong(group -> group.children().size())
                                .sum();

        assertThat(total)
                .as("defaults should leave a household plenty of room to add its own")
                .isLessThan(
                        com.budgetowl.category.service.CategoryService.MAXIMUM_PER_HOUSEHOLD / 2);
    }

    private static Map<String, CategoryKind> parentsIn(String section) {
        Map<String, CategoryKind> parents = new LinkedHashMap<>();
        Matcher matcher = PARENT_ROW.matcher(section);
        while (matcher.find()) {
            parents.put(matcher.group(1), CategoryKind.valueOf(matcher.group(2)));
        }
        assertThat(parents)
                .as("parsed no parent rows — has the migration's shape changed?")
                .isNotEmpty();
        return parents;
    }

    private static Map<String, List<String>> childrenIn(String section) {
        Map<String, List<String>> children = new LinkedHashMap<>();
        Matcher matcher = CHILD_ROW.matcher(section);
        while (matcher.find()) {
            children.computeIfAbsent(matcher.group(1), key -> new ArrayList<>())
                    .add(matcher.group(2));
        }
        assertThat(children)
                .as("parsed no child rows — has the migration's shape changed?")
                .isNotEmpty();
        return children;
    }

    /**
     * The text between a {@code VALUES} list's header and the closing {@code )} of that CTE.
     *
     * <p>Sliced rather than regexed over the whole file so that the two row patterns cannot match
     * each other's rows: a parent row and a child row are both parenthesised tuples of strings.
     */
    private static String section(String sql, String header) {
        int start = sql.indexOf(header);
        assertThat(start).as("migration no longer contains the CTE '%s'", header).isNotNegative();
        int values = sql.indexOf("VALUES", start);
        int end = sql.indexOf("\n)", values);
        assertThat(end).as("could not find the end of '%s'", header).isNotNegative();
        return sql.substring(values, end);
    }

    private static String read() {
        try {
            return Files.readString(MIGRATION, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "cannot read " + MIGRATION.toAbsolutePath() + " — run from backend/",
                    unreadable);
        }
    }
}
