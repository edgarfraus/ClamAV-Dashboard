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
