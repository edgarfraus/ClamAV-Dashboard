package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.util.AtomicPropertiesFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    public static final String SETTINGS_FILE = "conf/clamav-web-client.properties";

    // Keys
    private static final String ALLOWED_SCAN_ROOTS = "app.allowedScanRoots";
    private static final String UPLOAD_MAX_BYTES = "app.upload.maxBytes";
    private static final String CONCURRENT_SCANS = "app.concurrentScans";
    private static final String UPLOAD_DIR = "app.storage.uploadDir";
    private static final String QUARANTINE_DIR = "app.storage.quarantineDir";
    private static final String QUARANTINE_ENABLED = "app.quarantine.enabled";
    private static final String WEBHOOK_ENABLED = "app.webhook.enabled";
    private static final String WEBHOOK_URL = "app.webhook.url";
    private static final String TELEGRAM_ENABLED = "app.telegram.enabled";
    private static final String TELEGRAM_BOT_TOKEN = "app.telegram.botToken";
    private static final String TELEGRAM_CHAT_ID = "app.telegram.chatId";
    private static final String TELEGRAM_ACTIONS_ENABLED = "app.telegram.actions.enabled";
    private static final String TELEGRAM_ACTION_USERS = "app.telegram.actionUsers";
    private static final String PUBLIC_URL = "app.publicUrl";
    private static final String WATCH_ENABLED = "app.watch.enabled";
    private static final String WATCH_POLL_SECONDS = "app.watch.pollSeconds";
    private static final String SIGNATURE_RELOAD_ENABLED = "app.signatureReload.enabled";
    private static final String SIGNATURE_RELOAD_CRON = "app.signatureReload.cron";

    // Legacy keys (kept for backward compatibility)
    private static final String CLAMAV_SERVICE_HOST_PROPERTY = "clamav.service.host";
    private static final String CLAMAV_SERVICE_PORT_PROPERTY = "clamav.service.port";
    private static final String CLAMAV_SERVICE_PLATFORM_PROPERTY = "clamav.service.platform";

    private Properties props;

    @PostConstruct
    public void init() {
        try {
            props = AtomicPropertiesFile.loadOrCreate(Paths.get(SETTINGS_FILE));
            // Defaults
            props.putIfAbsent(ALLOWED_SCAN_ROOTS, "/scandir");
            props.putIfAbsent(UPLOAD_MAX_BYTES, String.valueOf(2L * 1024 * 1024 * 1024)); // 2GiB
            props.putIfAbsent(CONCURRENT_SCANS, "2");
            props.putIfAbsent(UPLOAD_DIR, "./data/uploads");
            props.putIfAbsent(QUARANTINE_DIR, "./data/quarantine");
            props.putIfAbsent(QUARANTINE_ENABLED, "false");
            props.putIfAbsent(WEBHOOK_ENABLED, "false");
            props.putIfAbsent(WEBHOOK_URL, "");
            props.putIfAbsent(TELEGRAM_ENABLED, "false");
            props.putIfAbsent(TELEGRAM_BOT_TOKEN, "");
            props.putIfAbsent(TELEGRAM_CHAT_ID, "");
            props.putIfAbsent(TELEGRAM_ACTIONS_ENABLED, "false");
            props.putIfAbsent(TELEGRAM_ACTION_USERS, "");
            props.putIfAbsent(PUBLIC_URL, "");
            props.putIfAbsent(WATCH_ENABLED, "false");
            props.putIfAbsent(WATCH_POLL_SECONDS, "30");
            props.putIfAbsent(SIGNATURE_RELOAD_ENABLED, "false");
            props.putIfAbsent(SIGNATURE_RELOAD_CRON, "0 0 2 * * *");

            // Legacy defaults if absent
            props.putIfAbsent(CLAMAV_SERVICE_HOST_PROPERTY, Optional.ofNullable(System.getenv("CLAMAV_HOST")).orElse("localhost"));
            props.putIfAbsent(CLAMAV_SERVICE_PORT_PROPERTY, Optional.ofNullable(System.getenv("CLAMAV_PORT")).orElse("3310"));
            props.putIfAbsent(CLAMAV_SERVICE_PLATFORM_PROPERTY, "UNIX");

            persist();
        } catch (IOException e) {
            log.error("Failed to init settings: {}", e.getMessage());
            props = new Properties();
        }
    }

    public synchronized void persist() {
        try {
            AtomicPropertiesFile.storeAtomic(Paths.get(SETTINGS_FILE), props);
        } catch (IOException e) {
            log.error("Failed to persist settings: {}", e.getMessage());
        }
    }

    public synchronized Properties snapshot() {
        Properties p = new Properties();
        p.putAll(props);
        return p;
    }

    public synchronized void updateFromMap(Map<String, String> updates) {
        for (var e : updates.entrySet()) {
            if (e.getValue() == null) continue;
            props.setProperty(e.getKey(), e.getValue().trim());
        }
        persist();
    }

    public List<Path> allowedRoots() {
        String raw = props.getProperty(ALLOWED_SCAN_ROOTS, "/scandir");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Paths::get)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .collect(Collectors.toList());
    }

    public long uploadMaxBytes() {
        try { return Long.parseLong(props.getProperty(UPLOAD_MAX_BYTES)); } catch (Exception e) { return 2L * 1024 * 1024 * 1024; }
    }

    public int concurrentScans() {
        try { return Integer.parseInt(props.getProperty(CONCURRENT_SCANS)); } catch (Exception e) { return 2; }
    }

    public Path uploadDir() {
        return Paths.get(props.getProperty(UPLOAD_DIR, "./data/uploads")).toAbsolutePath().normalize();
    }

    public Path quarantineDir() {
        return Paths.get(props.getProperty(QUARANTINE_DIR, "./data/quarantine")).toAbsolutePath().normalize();
    }

    public boolean quarantineEnabled() {
        return Boolean.parseBoolean(props.getProperty(QUARANTINE_ENABLED, "false"));
    }

    public boolean webhookEnabled() {
        return Boolean.parseBoolean(props.getProperty(WEBHOOK_ENABLED, "false"));
    }

    public String webhookUrl() {
        return props.getProperty(WEBHOOK_URL, "");
    }

    public boolean telegramEnabled() {
        return Boolean.parseBoolean(props.getProperty(TELEGRAM_ENABLED, "false"));
    }

    public String telegramBotToken() {
        return props.getProperty(TELEGRAM_BOT_TOKEN, "");
    }

    public String telegramChatId() {
        return props.getProperty(TELEGRAM_CHAT_ID, "");
    }

    /** Quarantine / restore buttons under Telegram alerts. Needs Telegram alerts on as well. */
    public boolean telegramActionsEnabled() {
        return telegramEnabled() && Boolean.parseBoolean(props.getProperty(TELEGRAM_ACTIONS_ENABLED, "false"));
    }

    /**
     * Who may press those buttons: Telegram id -> console username, from
     * "12345=alice" pairs separated by commas or new lines. A positive id is a
     * person; a negative one is a group, whose every member may then press.
     * In a group anyone can press a button, so a press counts only if the
     * person or the group they press in is listed here.
     */
    public Map<Long, String> telegramActionUsers() {
        Map<Long, String> out = new LinkedHashMap<>();
        for (String pair : props.getProperty(TELEGRAM_ACTION_USERS, "").split("[,\\n\\r]+")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(Long.parseLong(pair.substring(0, eq).trim()), pair.substring(eq + 1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** The console's address as people reach it, for links in notifications; empty when unknown. */
    public String publicUrl() {
        String u = props.getProperty(PUBLIC_URL, "").trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    public boolean watchEnabled() {
        return Boolean.parseBoolean(props.getProperty(WATCH_ENABLED, "false"));
    }

    public int watchPollSeconds() {
        try { return Integer.parseInt(props.getProperty(WATCH_POLL_SECONDS, "30")); } catch (Exception e) { return 30; }
    }

    public boolean signatureReloadEnabled() {
        return Boolean.parseBoolean(props.getProperty(SIGNATURE_RELOAD_ENABLED, "false"));
    }

    public String signatureReloadCron() {
        return props.getProperty(SIGNATURE_RELOAD_CRON, "0 0 2 * * *");
    }

    // Legacy getters
    public String legacyHost() { return props.getProperty(CLAMAV_SERVICE_HOST_PROPERTY, "localhost"); }
    public int legacyPort() { try { return Integer.parseInt(props.getProperty(CLAMAV_SERVICE_PORT_PROPERTY, "3310")); } catch (Exception e) { return 3310; } }
    public String legacyPlatform() { return props.getProperty(CLAMAV_SERVICE_PLATFORM_PROPERTY, "UNIX"); }
}
