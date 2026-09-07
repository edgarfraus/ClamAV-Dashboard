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
     * Allarga scan_jobs.type sui database creati prima dei tipi EXTERNAL/REALTIME/AGENT.
     *
     * Hibernate genera un CHECK constraint con i soli valori dell'enum noti al
     * momento della creazione della tabella, e ddl-auto=update non lo aggiorna:
     * su un DB vecchio inserire un job di tipo AGENT fallisce con
     * "Value not permitted for column TYPE". Cambiare il tipo della colonna fa
     * cadere il vincolo, stesso trucco gia' usato sopra per verdict.
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
     * Rende nullable clamd_endpoints.host su database creati prima degli agent.
     *
     * Serve una migrazione esplicita perche' hibernate.ddl-auto=update aggiunge
     * colonne ma non tocca i vincoli gia' esistenti: su un DB vecchio la colonna
     * resta NOT NULL, e creare un endpoint gestito da agent (host vuoto, perche'
     * e' la macchina a contattare la console) fallisce con
     * "NULL not allowed for column HOST". Su un DB nuovo la colonna nasce gia'
     * nullable e questa migrazione non ha nulla da fare.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateEndpointHostNullable() {
        // H2 e PostgreSQL usano sintassi diverse: proviamo entrambe, la prima
        // che passa vince. Se sono gia' a posto falliscono entrambe, senza danno.
        String[] statements = {
                "ALTER TABLE clamd_endpoints ALTER COLUMN host DROP NOT NULL",  // PostgreSQL
                "ALTER TABLE clamd_endpoints ALTER COLUMN host SET NULL"        // H2
        };
        for (String sql : statements) {
            try {
                jdbcTemplate.execute(sql);
                log.info("SchemaFixup: clamd_endpoints.host ora ammette NULL (endpoint gestiti da agent)");
                return;
            } catch (Exception e) {
                log.debug("SchemaFixup: '{}' non applicabile: {}", sql, e.getMessage());
            }
        }
        log.debug("SchemaFixup: clamd_endpoints.host gia' nullable o tabella non ancora creata");
    }
}
