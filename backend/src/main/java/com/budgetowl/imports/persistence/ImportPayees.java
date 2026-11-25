package com.budgetowl.imports.persistence;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Get-or-create for every payee in one import, in bulk.
 *
 * <p><b>Why not {@code PayeeService.getOrCreate} in a loop.</b> That method retries a lost
 * unique-index race by catching the violation and querying again <em>in the same transaction</em>,
 * and in PostgreSQL a failed statement aborts the surrounding transaction unless a savepoint was
 * taken — so inside an import's single transaction the retry would itself fail, and the whole
 * import with it (docs/features/file-import.md, Implementation notes). Here the database resolves
 * the race: {@code INSERT … ON CONFLICT DO NOTHING} against {@code ux_payees_household_name}, then
 * a select that matches with PostgreSQL's own {@code lower()} — the same expression the index uses,
 * so the two can never disagree about which names are one payee.
 *
 * <p>Plain JDBC rather than JPA because the conflict clause has no JPQL form. It runs inside the
 * caller's transaction through the same {@code DataSource}. Every statement is bound; nothing from
 * a file is ever concatenated into SQL.
 */
@Repository
public class ImportPayees {

    /** Names per statement, so a ten-thousand-payee file is ten round trips, not one huge one. */
    private static final int CHUNK = 1000;

    private final JdbcTemplate jdbc;

    public ImportPayees(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param names normalised payee names, each 1–80 characters
     * @return each requested name's payee id in this household
     */
    public Map<String, UUID> getOrCreate(UUID householdId, Collection<String> names) {
        Map<String, UUID> ids = new HashMap<>();
        List<String> all = new ArrayList<>(names);
        for (int start = 0; start < all.size(); start += CHUNK) {
            List<String> chunk = all.subList(start, Math.min(all.size(), start + CHUNK));
            String[] values = chunk.toArray(String[]::new);
            jdbc.update(
                    """
                    INSERT INTO payees (id, household_id, name)
                    SELECT gen_random_uuid(), ?, wanted.name
                      FROM (SELECT DISTINCT ON (lower(n)) n AS name
                              FROM unnest(?) AS n
                             ORDER BY lower(n), n) AS wanted
                    ON CONFLICT (household_id, (lower(name))) DO NOTHING
                    """,
                    statement -> {
                        statement.setObject(1, householdId);
                        statement.setArray(2, textArray(statement.getConnection(), values));
                    });
            jdbc.query(
                    """
                    SELECT n.name AS wanted, p.id AS id
                      FROM unnest(?) AS n(name)
                      JOIN payees p
                        ON p.household_id = ?
                       AND lower(p.name) = lower(n.name)
                    """,
                    statement -> {
                        statement.setArray(1, textArray(statement.getConnection(), values));
                        statement.setObject(2, householdId);
                    },
                    row -> {
                        ids.put(row.getString("wanted"), row.getObject("id", UUID.class));
                    });
        }
        return ids;
    }

    private static Array textArray(java.sql.Connection connection, String[] values)
            throws java.sql.SQLException {
        return connection.createArrayOf("text", values);
    }
}
