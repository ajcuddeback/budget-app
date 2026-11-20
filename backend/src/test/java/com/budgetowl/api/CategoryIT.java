package com.budgetowl.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Categories over real HTTP against real PostgreSQL.
 *
 * <p>The rules worth proving here are the ones a unit test cannot: that the two-level limit holds
 * when the database is the thing enforcing it, that a duplicate name loses to a unique index rather
 * than to a look-before-you-leap check, and — the one that would hurt most if it were wrong — that
 * deleting a category <b>keeps</b> the transactions filed under it.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CategoryIT extends ApiTestBase {

    @Test
    void a_new_household_starts_with_a_usable_set_of_categories() {
        Household household = seedHousehold();

        ApiResponse listed = household.owner().get("/api/categories?size=200");

        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.numberField("totalElements"))
                .as("a first-run app whose category picker is empty looks broken")
                .isGreaterThan(10);
        assertThat(listed.body())
                .as("the vocabulary the screens were designed around")
                .contains("Groceries")
                .contains("Transport")
                .contains("Utilities");
        assertThat(listed.numberField("totalPages"))
                .as("the cap equals the maximum page size so the whole tree fits in one request")
                .isEqualTo(1);
    }

    @Test
    void the_whole_tree_arrives_in_one_page_so_a_picker_never_has_to_page() {
        Household household = seedHousehold();

        ApiResponse listed = household.owner().get("/api/categories?size=200");

        assertThat(listed.numberField("size")).isEqualTo(200);
        assertThat(listed.numberField("totalPages")).isEqualTo(1);
    }

    @Test
    void a_child_takes_its_parents_kind_and_reports_its_parents_name() {
        Household household = seedHousehold();
        UUID bills = seededCategoryId("Bills");

        ApiResponse created =
                household
                        .owner()
                        .post(
                                "/api/categories",
                                """
                                {"name":"Water","parentId":"%s"}
                                """
                                        .formatted(bills));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.stringField("kind"))
                .as("a child must not be able to disagree with its parent")
                .isEqualTo("EXPENSE");
        assertThat(created.stringField("parentName")).isEqualTo("Bills");
    }

    @Test
    void a_child_that_contradicts_its_parents_kind_is_refused_rather_than_quietly_corrected() {
        Household household = seedHousehold();
        UUID bills = seededCategoryId("Bills");

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/categories",
                                """
                                {"name":"Windfall","kind":"INCOME","parentId":"%s"}
                                """
                                        .formatted(bills));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("validation-failed").contains("kind");
    }

    @Test
    void a_third_level_is_refused() {
        Household household = seedHousehold();
        UUID utilities = seededCategoryId("Utilities");

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/categories",
                                """
                                {"name":"Electricity","parentId":"%s"}
                                """
                                        .formatted(utilities));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body()).contains("category-depth-exceeded");
    }

    @Test
    void a_category_with_children_cannot_itself_become_a_child() {
        Household household = seedHousehold();
        UUID bills = seededCategoryId("Bills");
        UUID groceries = seededCategoryId("Groceries");
        long version = household.owner().get("/api/categories/" + bills).numberField("version");

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/categories/" + bills,
                                """
                                {"parentId":"%s","version":%d}
                                """
                                        .formatted(groceries, version));

        assertThat(refused.status())
                .as("moving Bills under Groceries would push Utilities to a third level")
                .isEqualTo(409);
        assertThat(refused.body()).contains("category-depth-exceeded");
    }

    @Test
    void a_category_cannot_be_moved_under_a_parent_of_another_kind() {
        Household household = seedHousehold();
        UUID groceries = seededCategoryId("Groceries");
        UUID income = seededCategoryId("Income");
        long version = household.owner().get("/api/categories/" + groceries).numberField("version");

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/categories/" + groceries,
                                """
                                {"parentId":"%s","version":%d}
                                """
                                        .formatted(income, version));

        // Not category-kind-immutable: nobody asked to change a kind. The MOVE is what is refused,
        // because it would file spending under income and make every roll-up ambiguous.
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("validation-failed").contains("parentId");
    }

    @Test
    void a_category_cannot_be_moved_under_itself() {
        Household household = seedHousehold();
        UUID groceries = seededCategoryId("Groceries");
        long version = household.owner().get("/api/categories/" + groceries).numberField("version");

        ApiResponse refused =
                household
                        .owner()
                        .patch(
                                "/api/categories/" + groceries,
                                """
                                {"parentId":"%s","version":%d}
                                """
                                        .formatted(groceries, version));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body()).contains("category-depth-exceeded");
    }

    @Test
    void a_duplicate_name_at_the_same_level_is_refused() {
        Household household = seedHousehold();

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/categories",
                                """
                                {"name":"groceries","kind":"EXPENSE"}
                                """);

        assertThat(refused.status())
                .as("case-insensitively: nothing downstream could tell the two apart")
                .isEqualTo(409);
        assertThat(refused.body()).contains("category-name-taken");
    }

    @Test
    void the_same_name_under_two_different_parents_is_allowed() {
        Household household = seedHousehold();
        UUID bills = seededCategoryId("Bills");
        UUID transport = seededCategoryId("Transport");

        assertThat(createCategory(household.owner(), "Phone", null, bills)).isNotNull();

        ApiResponse second =
                household
                        .owner()
                        .post(
                                "/api/categories",
                                """
                                {"name":"Phone","parentId":"%s"}
                                """
                                        .formatted(transport));

        assertThat(second.status())
                .as("Bills → Phone and Transport → Phone are both reasonable")
                .isEqualTo(201);
    }

    @Test
    void an_explicit_null_parent_moves_a_child_to_the_top_level_and_an_absent_one_does_not() {
        Household household = seedHousehold();
        UUID utilities = seededCategoryId("Utilities");
        long version = household.owner().get("/api/categories/" + utilities).numberField("version");

        ApiResponse renamedOnly =
                household
                        .owner()
                        .patch(
                                "/api/categories/" + utilities,
                                """
                                {"name":"Utilities and water","version":%d}
                                """
                                        .formatted(version));

        assertThat(renamedOnly.status()).isEqualTo(200);
        assertThat(renamedOnly.stringField("parentName"))
                .as("an absent parentId must not silently promote the category")
                .isEqualTo("Bills");

        ApiResponse promoted =
                household
                        .owner()
                        .patch(
                                "/api/categories/" + utilities,
                                """
                                {"parentId":null,"version":%d}
                                """
                                        .formatted(renamedOnly.numberField("version")));

        assertThat(promoted.status()).isEqualTo(200);
        assertThat(promoted.body()).contains("\"parentId\":null");
    }

    @Test
    void deleting_a_category_keeps_the_transactions_filed_under_it() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", "4321");
        UUID groceries = seededCategoryId("Groceries");
        UUID transaction =
                createTransaction(household.owner(), account, "2026-11-02", "-42.5000", groceries);

        ApiResponse deleted = household.owner().delete("/api/categories/" + groceries);
        assertThat(deleted.status()).isEqualTo(204);

        ApiResponse survivor = household.owner().get("/api/transactions/" + transaction);
        assertThat(survivor.status())
                .as("deleting a category must never delete money")
                .isEqualTo(200);
        assertThat(survivor.body()).contains("\"categoryId\":null");
        assertThat(survivor.stringField("amount")).isEqualTo("-42.5000");
    }

    @Test
    void deleting_a_parent_deletes_its_children_and_still_keeps_their_transactions() {
        Household household = seedHousehold();
        UUID account = createAccount(household.owner(), "Current", null);
        UUID bills = seededCategoryId("Bills");
        UUID utilities = seededCategoryId("Utilities");
        UUID transaction =
                createTransaction(household.owner(), account, "2026-11-03", "-88.0000", utilities);

        assertThat(household.owner().delete("/api/categories/" + bills).status()).isEqualTo(204);

        assertThat(household.owner().get("/api/categories/" + utilities).status())
                .as("a child category whose parent is gone has no meaning")
                .isEqualTo(404);
        ApiResponse survivor = household.owner().get("/api/transactions/" + transaction);
        assertThat(survivor.status()).isEqualTo(200);
        assertThat(survivor.body()).contains("\"categoryId\":null");
    }

    /** Method, path and body for each of the seven routes, with {@code {id}} to be substituted. */
    static List<Endpoint> everyRoute() {
        return List.of(
                new Endpoint("GET", "/api/categories", null),
                new Endpoint("POST", "/api/categories", "{\"name\":\"New\",\"kind\":\"EXPENSE\"}"),
                new Endpoint("GET", "/api/categories/{id}", null),
                new Endpoint(
                        "PATCH", "/api/categories/{id}", "{\"version\":0,\"name\":\"Renamed\"}"),
                new Endpoint("POST", "/api/categories/{id}/archive", null),
                new Endpoint("POST", "/api/categories/{id}/unarchive", null),
                new Endpoint("DELETE", "/api/categories/{id}", null));
    }

    /** The six that change something. A {@code VIEWER} gets {@code 403} from every one. */
    static List<Endpoint> everyWrite() {
        return everyRoute().stream().filter(endpoint -> !"GET".equals(endpoint.method())).toList();
    }

    /**
     * The highest-value authorization test in this slice.
     *
     * <p>Parameterized over every write rather than written once against {@code POST}: the role
     * check is one line per method, and the one that gets forgotten is the one nobody listed. Since
     * ADR-0026 removed the second household, this is what replaces the cross-household test for
     * role enforcement (docs/architecture/security-model.md).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWrite")
    void answers403ToAViewerOnEveryWrite(Endpoint endpoint) {
        Household household = seedHousehold();

        ApiResponse response = endpoint.callWith(household.viewer(), seededCategoryId("Groceries"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.stringField("code")).isEqualTo("read-only-role");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRoute")
    void answers404ForAnIdThatBelongsToNothing(Endpoint endpoint) {
        Household household = seedHousehold();

        ApiResponse response = endpoint.callWith(household.owner(), UUID.randomUUID());

        if (endpoint.path().contains("{id}")) {
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.stringField("code")).isEqualTo("not-found");
        } else {
            assertThat(response.status()).isIn(200, 201);
        }
    }

    @Test
    void a_viewer_may_read_categories() {
        Household household = seedHousehold();

        assertThat(household.viewer().get("/api/categories").status()).isEqualTo(200);
    }

    /** Method, path and body for one route, with {@code {id}} to be substituted. */
    record Endpoint(String method, String path, String body) {

        ApiResponse callWith(ApiClient client, UUID categoryId) {
            return client.send(method, path.replace("{id}", categoryId.toString()), body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    @Test
    void a_stale_version_is_refused_rather_than_overwriting_somebody_elses_edit() {
        Household household = seedHousehold();
        UUID groceries = seededCategoryId("Groceries");
        long stale = household.owner().get("/api/categories/" + groceries).numberField("version");

        household
                .owner()
                .patch(
                        "/api/categories/" + groceries,
                        """
                        {"name":"Food shopping","version":%d}
                        """
                                .formatted(stale));

        ApiResponse second =
                household
                        .member()
                        .patch(
                                "/api/categories/" + groceries,
                                """
                                {"name":"Supermarket","version":%d}
                                """
                                        .formatted(stale));

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body()).contains("stale-version");
    }

    @Test
    void an_archived_category_is_hidden_by_default_and_returned_on_request() {
        Household household = seedHousehold();
        UUID entertainment = seededCategoryId("Entertainment");

        assertThat(
                        household
                                .owner()
                                .post("/api/categories/" + entertainment + "/archive", null)
                                .status())
                .isEqualTo(200);

        assertThat(household.owner().get("/api/categories?size=200").body())
                .doesNotContain("Entertainment");
        assertThat(household.owner().get("/api/categories?size=200&includeArchived=true").body())
                .contains("Entertainment");
    }

    @Test
    void a_household_is_capped_so_the_whole_tree_always_fits_in_one_page() {
        Household household = seedHousehold();
        long existing =
                household
                        .owner()
                        .get("/api/categories?size=200&includeArchived=true")
                        .numberField("totalElements");

        // Fill up to the cap, which is also the API's maximum page size. That equality is the
        // point: one request always returns the whole tree, so a picker never has to page.
        for (long i = existing; i < 200; i++) {
            ApiResponse accepted =
                    household
                            .owner()
                            .post(
                                    "/api/categories",
                                    """
                                    {"name":"Filler %d","kind":"EXPENSE"}
                                    """
                                            .formatted(i));
            assertThat(accepted.status()).isEqualTo(201);
        }

        ApiResponse refused =
                household
                        .owner()
                        .post(
                                "/api/categories",
                                """
                                {"name":"One too many","kind":"EXPENSE"}
                                """);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body()).contains("category-limit-reached");
        assertThat(household.owner().get("/api/categories?size=200").numberField("totalPages"))
                .isEqualTo(1);
    }

    @Test
    void a_child_of_an_archived_parent_still_reports_its_parents_name() {
        Household household = seedHousehold();
        UUID bills = seededCategoryId("Bills");
        household.owner().post("/api/categories/" + bills + "/archive", null);

        // The default listing excludes the archived parent, so the name cannot come from the page
        // in hand — the server looks it up rather than leaving the child unlabelled.
        ApiResponse listed = household.owner().get("/api/categories?size=200");

        assertThat(listed.body()).contains("Utilities");
        assertThat(listed.body()).contains("\"parentName\":\"Bills\"");
    }

    @Test
    void a_category_that_is_not_this_households_is_indistinguishable_from_one_that_never_existed() {
        Household household = seedHousehold();

        ApiResponse response = household.owner().get("/api/categories/" + UUID.randomUUID());

        assertThat(response.status()).as("a 403 would confirm the row exists").isEqualTo(404);
    }
}
