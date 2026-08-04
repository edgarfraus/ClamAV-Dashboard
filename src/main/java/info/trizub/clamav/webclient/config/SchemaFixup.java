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
}
