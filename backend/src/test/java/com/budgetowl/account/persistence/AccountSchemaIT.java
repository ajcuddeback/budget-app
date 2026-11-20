package com.budgetowl.account.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetowl.persistence.PersistenceTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code V9__accounts.sql}, exercised against the database rather than against the code that is
 * supposed to respect it.
 *
 * <p>Everything here goes in through plain SQL on purpose. Bean Validation and {@link
 * com.budgetowl.account.domain.Account} reject the same inputs and are tested separately; these
 * assertions are about what stays true when a future import path, a CLI, a restored backup or a
 * psql session writes a row without going through any of that. "The application validates for good
 * error messages; the database enforces truth" (docs/guides/database-style.md).
 *
 * <p>Each one asserts the <em>effect</em> — a row that violates the rule is refused — rather than
 * that the DDL contains a word. A test that greps {@code information_schema} for a constraint name
 * passes against a constraint whose predicate is {@code CHECK (true)}.
 */
@SpringBootTest
class AccountSchemaIT extends PersistenceTestBase {

    private UUID household;

    @BeforeEach
    void seedAHouseholdWithAnOwner() {
        household = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        // One transaction: a household with no OWNER does not survive COMMIT (V3__households.sql).
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
                });
    }

    // ------------------------------------------------------------------------------- ownership

    @Test
    void refusesAnAccountInAHouseholdThatDoesNotExist() {
        // The ownership root has to be real. An account whose household_id points at nothing is a
        // row no scoping predicate can ever reach and no owner can ever delete.
        assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), "Orphan", "CHECKING", "GBP"))
                .hasMessageContaining("fk_accounts_household");
    }

    @Test
    void takesAHouseholdsAccountsWithItWhenTheHouseholdIsDeleted() {
        // "Deleting a household removes or anonymizes all of its financial data. No orphaned
        // rows" (docs/guides/database-style.md).
        insertAccount(household, "Current", "CHECKING", "GBP");

        jdbc.update("DELETE FROM households WHERE id = ?", household);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class)).isZero();
    }

    @Test
    void refusesToMoveAnAccountIntoANonExistentHousehold() {
        UUID account = insertAccount(household, "Current", "CHECKING", "GBP");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE accounts SET household_id = ? WHERE id = ?",
                                        UUID.randomUUID(),
                                        account))
                .hasMessageContaining("fk_accounts_household");
    }

    // ------------------------------------------------------------------------------------ name

    @Test
    void refusesANameThatIsOnlyWhitespace() {
        assertThatThrownBy(() -> insertAccount(household, "   ", "CHECKING", "GBP"))
                .hasMessageContaining("ck_accounts_name_length");
    }

    @Test
    void refusesANameOfEightyOneCharacters() {
        assertThatThrownBy(() -> insertAccount(household, "n".repeat(81), "CHECKING", "GBP"))
                .hasMessageContaining("ck_accounts_name_length");
    }

    @Test
    void acceptsANameRightUpToEightyCharacters() {
        assertThatCode(() -> insertAccount(household, "n".repeat(80), "CHECKING", "GBP"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAGigabyteOfPaddingAroundOneCharacter() {
        // `length(btrim(name)) <= 80` on its own is satisfied by any amount of surrounding
        // whitespace, and `text` in PostgreSQL has no ceiling of its own — which is the exact gap
        // V8 section 4 closed on every other user-supplied text column. The application stores
        // the trimmed value, so no row it writes is affected.
        String padded = " ".repeat(200) + "Current" + " ".repeat(200);

        assertThatThrownBy(() -> insertAccount(household, padded, "CHECKING", "GBP"))
                .hasMessageContaining("ck_accounts_name_length");
    }

    @Test
    void allowsTwoAccountsInOneHouseholdToShareAName() {
        // Names are deliberately NOT unique: real households have two pots both called "Savings",
        // and that is the reason last_four exists. Nothing may assume uniqueness — not a lookup,
        // not a test fixture, not an import.
        insertAccount(household, "Savings", "SAVINGS", "GBP");

        assertThatCode(() -> insertAccount(household, "Savings", "SAVINGS", "GBP"))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------------- lastFour

    @Test
    void refusesAnythingThatIsNotExactlyFourAsciiDigits() {
        // ADR-0028: the field exists so this column CANNOT hold a full account number. That is
        // only true while every one of these is refused by the database itself.
        List<String> rejected =
                List.of(
                        "", // empty is not "absent" — absent is NULL
                        "123",
                        "12345",
                        "123456789012345",
                        "abcd",
                        "12a4",
                        "١٢٣٤", // Arabic-Indic digits: why the pattern is [0-9] and not \d
                        "１２３４", // fullwidth digits
                        "+123",
                        " 123",
                        "123 ",
                        "12 4",
                        "1234\n", // PostgreSQL's $ is end-of-string, not end-of-line, by default
                        "\n1234");

        for (String value : rejected) {
            assertThatThrownBy(() -> insertAccountWithLastFour(value))
                    .as("last_four %s", value.codePoints().boxed().toList())
                    .hasMessageContaining("ck_accounts_last_four");
        }
    }

    @Test
    void acceptsFourZeroes() {
        // The one that a "non-empty digits" check would get wrong.
        assertThatCode(() -> insertAccountWithLastFour("0000")).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT last_four FROM accounts", String.class))
                .isEqualTo("0000");
    }

    @Test
    void acceptsAnAccountWithNoDigitsAtAll() {
        // Cash in a tin has no number. NULL is the honest representation and must stay legal.
        assertThatCode(() -> insertAccountWithLastFour(null)).doesNotThrowAnyException();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM accounts WHERE last_four IS NULL",
                                Long.class))
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------------------------ type

    @Test
    void refusesATypeOutsideTheClosedSet() {
        assertThatThrownBy(() -> insertAccount(household, "Crypto", "WALLET", "GBP"))
                .hasMessageContaining("ck_accounts_type");
        assertThatThrownBy(() -> insertAccount(household, "Current", "checking", "GBP"))
                .hasMessageContaining("ck_accounts_type");
        assertThatThrownBy(() -> insertAccount(household, "Current", "", "GBP"))
                .hasMessageContaining("ck_accounts_type");
    }

    @Test
    void acceptsEachOfTheFiveTypes() {
        for (String type :
                new String[] {"CHECKING", "SAVINGS", "CREDIT_CARD", "CASH", "INVESTMENT"}) {
            assertThatCode(() -> insertAccount(household, type, type, "GBP"))
                    .as(type)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void storesTheTypeAsTextSoAddingASixthIsAMigrationRatherThanATypeRewrite() {
        // docs/features/accounts.md calls for a CHECK constraint rather than a PostgreSQL enum.
        // The difference is invisible from the application and very visible from a migration.
        assertThat(columnType("type")).isEqualTo("text");
    }

    // -------------------------------------------------------------------------------- currency

    @Test
    void refusesACurrencyThatIsNotThreeUppercaseLetters() {
        // Lowercase is refused, not merely the wrong length: 'gbp' would otherwise sit alongside
        // 'GBP' and nothing would ever reconcile the two.
        for (String value : new String[] {"gbp", "GB", "GBPX", "G8P", "   ", "", "GB "}) {
            assertThatThrownBy(() -> insertAccount(household, "Current", "CHECKING", value))
                    .as("currency '%s'", value)
                    .hasMessageContaining("ck_accounts_currency");
        }
    }

    @Test
    void acceptsEachAccountInItsOwnCurrency() {
        // Two currencies in one household is a normal case (ADR-0022), and nothing is converted.
        assertThatCode(
                        () -> {
                            insertAccount(household, "Current", "CHECKING", "GBP");
                            insertAccount(household, "Urlaub", "SAVINGS", "EUR");
                        })
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------------- opening balance

    @Test
    void storesTheOpeningBalanceAsNumericWithFourDecimalPlaces() {
        // ADR-0006, checked at the column rather than inferred from the entity.
        assertThat(columnType("opening_balance")).isEqualTo("numeric");
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT numeric_precision || ',' || numeric_scale
                                  FROM information_schema.columns
                                 WHERE table_name = 'accounts' AND column_name = 'opening_balance'
                                """,
                                String.class))
                .isEqualTo("19,4");
    }

    @Test
    void roundTripsAnExactAmountWithNoDriftInEitherDirection() {
        // The value from the feature doc's testing notes. A double cannot hold -1234.5678, so if
        // anything on this path were floating point the comparison below would fail.
        for (String amount :
                new String[] {
                    "-1234.5678",
                    "1234.5678",
                    "0.0001",
                    "-0.0001",
                    "0.0000",
                    "999999999999999.9999",
                    "-999999999999999.9999"
                }) {
            UUID account = insertAccountWithOpeningBalance(new BigDecimal(amount));

            BigDecimal stored =
                    jdbc.queryForObject(
                            "SELECT opening_balance FROM accounts WHERE id = ?",
                            BigDecimal.class,
                            account);

            assertThat(stored).as(amount).isEqualByComparingTo(new BigDecimal(amount));
            assertThat(stored.scale()).as("%s keeps NUMERIC(19,4)'s scale", amount).isEqualTo(4);
        }
    }

    @Test
    void refusesAnAmountWiderThanNineteenDigits() {
        // The ceiling is part of the type, and hitting it must be an error rather than a silently
        // truncated balance.
        assertThatThrownBy(
                        () ->
                                insertAccountWithOpeningBalance(
                                        new BigDecimal("1000000000000000.0000")))
                .hasMessageContaining("numeric field overflow");
    }

    @Test
    void acceptsANegativeOpeningBalance() {
        // A credit card you owe 400 on opens at -400.0000. Signed, with no sign coerced from type.
        assertThatCode(() -> insertAccountWithOpeningBalance(new BigDecimal("-400.0000")))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------- defaults and the index

    @Test
    void anAccountStartsUnarchivedAtVersionZeroWithBothTimestampsSet() {
        insertAccount(household, "Current", "CHECKING", "GBP");

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT count(*) FROM accounts
                                 WHERE archived = false
                                   AND version = 0
                                   AND created_at IS NOT NULL
                                   AND updated_at IS NOT NULL
                                """,
                                Long.class))
                .isEqualTo(1L);
    }

    @Test
    void theListingIndexSuppliesTheOrderSoThereIsNoSortStep() {
        // The effect the index exists for, not its name: with a sequential scan ruled out,
        // PostgreSQL must be able to answer the default listing — one household's unarchived
        // accounts, ordered by lower(name) with a tiebreak on id — straight out of the index. A
        // plan containing a Sort node would mean the index does not carry the order the feature
        // promises, which is exactly what leaving `id` out of it would cause.
        for (int i = 0; i < 50; i++) {
            insertAccount(household, "Account " + i, "CHECKING", "GBP");
        }
        jdbc.execute("ANALYZE accounts");

        List<String> lines =
                transaction.execute(
                        status -> {
                            // Ruling out a sequential scan is what makes this assertion about the
                            // index rather than about the row count: with 50 rows PostgreSQL would
                            // reasonably scan and sort, and the test would prove nothing either
                            // way.
                            jdbc.execute("SET LOCAL enable_seqscan = off");
                            return jdbc.queryForList(
                                    """
                                    EXPLAIN SELECT id FROM accounts
                                     WHERE household_id = ? AND archived = false
                                     ORDER BY lower(name), id
                                     LIMIT 50
                                    """,
                                    String.class,
                                    household);
                        });
        String plan = String.join("\n", Objects.requireNonNull(lines));

        assertThat(plan).contains("ix_accounts_household_id_archived_name");
        assertThat(plan).doesNotContain("Sort");
    }

    @Test
    void theForeignKeyIsIndexed() {
        // PostgreSQL does not do this for you, and SchemaMigrationIT checks it generically — this
        // names the index that satisfies it, so deleting the composite does not quietly pass by
        // being replaced with a plain one.
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexdef FROM pg_indexes WHERE tablename = 'accounts'",
                                String.class))
                .anySatisfy(
                        definition ->
                                assertThat(definition)
                                        .contains("ix_accounts_household_id_archived_name")
                                        .contains("household_id")
                                        .contains("archived")
                                        .contains("lower(name)"));
    }

    // ---------------------------------------------------------------------------------- helpers

    private String columnType(String column) {
        return jdbc.queryForObject(
                """
                SELECT data_type FROM information_schema.columns
                 WHERE table_name = 'accounts' AND column_name = ?
                """,
                String.class,
                column);
    }

    private UUID insertAccount(UUID inHousehold, String name, String type, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO accounts (id, household_id, name, type, currency, opening_balance)
                VALUES (?, ?, ?, ?, ?, 0)
                """,
                id,
                inHousehold,
                name,
                type,
                currency);
        return id;
    }

    private void insertAccountWithLastFour(String lastFour) {
        jdbc.update(
                """
                INSERT INTO accounts
                    (id, household_id, name, last_four, type, currency, opening_balance)
                VALUES (?, ?, 'Current', ?, 'CHECKING', 'GBP', 0)
                """,
                UUID.randomUUID(),
                household,
                lastFour);
    }

    private UUID insertAccountWithOpeningBalance(BigDecimal openingBalance) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO accounts (id, household_id, name, type, currency, opening_balance)
                VALUES (?, ?, 'Current', 'CHECKING', 'GBP', ?)
                """,
                id,
                household,
                openingBalance);
        return id;
    }
}
