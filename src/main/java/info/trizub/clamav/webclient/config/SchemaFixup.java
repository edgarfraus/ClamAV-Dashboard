package info.trizub.clamav.webclient.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaFixup {

    private static final Logger log = LoggerFactory.getLogger(SchemaFixup.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaFixup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateVerdictColumn() {
        try {
            jdbcTemplate.execute("ALTER TABLE scan_jobs ALTER COLUMN verdict SET DATA TYPE VARCHAR(20)");
            log.info("SchemaFixup: migrated scan_jobs.verdict column to VARCHAR(20)");
        } catch (Exception e) {
            log.debug("SchemaFixup: verdict column migration skipped: {}", e.getMessage());
        }
        dropPostgresEnumChecks("scan_jobs", "verdict");
    }

    /**
     * Lets telegram_actions.type take ACK on databases whose table was created
     * when only QUARANTINE and RESTORE existed: Hibernate's CHECK constraint
     * still lists the old values, and inserting an acknowledge button would
     * fail. Same two steps as the scan_jobs columns: the type change drops the
     * constraint on H2, the explicit drop does it on PostgreSQL.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateTelegramActionType() {
        try {
            jdbcTemplate.execute("ALTER TABLE telegram_actions ALTER COLUMN type SET DATA TYPE VARCHAR(16)");
            log.info("SchemaFixup: migrated telegram_actions.type column to VARCHAR(16)");
        } catch (Exception e) {
            log.debug("SchemaFixup: telegram_actions.type migration skipped: {}", e.getMessage());
        }
        dropPostgresEnumChecks("telegram_actions", "type");
    }

    /**
     * On PostgreSQL, changing a column's type does NOT drop a CHECK constraint
     * on it (it does on H2, which is what the ALTERs above rely on), so a
     * constraint listing an enum's old values survives and rejects every new
     * one. Drops the CHECK constraints that mention the column. Harmless
     * no-op on H2, where pg_constraint does not exist.
     */
    private void dropPostgresEnumChecks(String table, String column) {
        try {
            for (String name : jdbcTemplate.queryForList(
                    "SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'c' "
                            + "AND pg_get_constraintdef(oid) LIKE ?", String.class, table, "%(" + column + ")::%")) {
                jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + name.replace("\"", "") + "\"");
                log.info("SchemaFixup: dropped stale CHECK constraint {} on {}.{}", name, table, column);
            }
        } catch (Exception e) {
            log.debug("SchemaFixup: no PostgreSQL CHECK constraints to drop on {}.{}: {}", table, column, e.getMessage());
        }
    }

    /**
     * Widens scan_jobs.type on databases created before the EXTERNAL/REALTIME/AGENT types.
     *
     * Hibernate generates a CHECK constraint listing only the enum values known
     * when the table was created, and ddl-auto=update never updates it: on an
     * old DB, inserting a job of type AGENT fails with
     * "Value not permitted for column TYPE". Changing the column's data type
     * drops the constraint, the same trick already used above for verdict.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateJobTypeColumn() {
        try {
            jdbcTemplate.execute("ALTER TABLE scan_jobs ALTER COLUMN type SET DATA TYPE VARCHAR(20)");
            log.info("SchemaFixup: migrated scan_jobs.type column to VARCHAR(20)");
        } catch (Exception e) {
            log.debug("SchemaFixup: type column migration skipped: {}", e.getMessage());
        }
        dropPostgresEnumChecks("scan_jobs", "type");
    }

    /**
     * Makes clamd_endpoints.host nullable on databases created before agents existed.
     *
     * An explicit migration is needed because hibernate.ddl-auto=update adds
     * columns but never touches existing constraints: on an old DB the column
     * stays NOT NULL, and creating an agent-managed endpoint (empty host,
     * because the machine is the one contacting the console) fails with
     * "NULL not allowed for column HOST". On a new DB the column is created
     * nullable and this migration has nothing to do.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateEndpointHostNullable() {
        // H2 and PostgreSQL use different syntax: try both, the first that
        // succeeds wins. If the column is already fine both fail, harmlessly.
        String[] statements = {
                "ALTER TABLE clamd_endpoints ALTER COLUMN host DROP NOT NULL",  // PostgreSQL
                "ALTER TABLE clamd_endpoints ALTER COLUMN host SET NULL"        // H2
        };
        for (String sql : statements) {
            try {
                jdbcTemplate.execute(sql);
                log.info("SchemaFixup: clamd_endpoints.host now allows NULL (agent-managed endpoints)");
                return;
            } catch (Exception e) {
                log.debug("SchemaFixup: '{}' not applicable: {}", sql, e.getMessage());
            }
        }
        log.debug("SchemaFixup: clamd_endpoints.host already nullable or table not created yet");
    }
}
