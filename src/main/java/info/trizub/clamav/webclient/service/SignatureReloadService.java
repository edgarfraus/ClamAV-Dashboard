package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

@Service
public class SignatureReloadService {

    private static final Logger log = LoggerFactory.getLogger(SignatureReloadService.class);

    private final SettingsService settings;
    private final ClamdEndpointRepository endpointRepo;
    private final ThreadPoolTaskScheduler taskScheduler;

    private volatile ScheduledFuture<?> currentFuture;
    private volatile Instant lastReloadAt;
    private volatile Map<String, String> lastReloadResults;

    public SignatureReloadService(SettingsService settings,
                                  ClamdEndpointRepository endpointRepo,
                                  ThreadPoolTaskScheduler taskScheduler) {
        this.settings = settings;
        this.endpointRepo = endpointRepo;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public synchronized void reschedule() {
        if (currentFuture != null) {
            currentFuture.cancel(false);
            currentFuture = null;
        }
        if (!settings.signatureReloadEnabled()) return;
        String cron = settings.signatureReloadCron();
        if (cron == null || cron.isBlank()) return;
        try {
            currentFuture = taskScheduler.schedule(this::reloadAll, new CronTrigger(cron));
            log.info("Signature reload scheduled with cron '{}'", cron);
        } catch (Exception e) {
            log.warn("Invalid signature reload cron '{}': {}", cron, e.getMessage());
        }
    }

    public Map<String, String> reloadAll() {
        Map<String, String> results = new LinkedHashMap<>();
        endpointRepo.findByEnabledTrue().forEach(ep -> results.put(ep.getName(), sendReload(ep)));
        lastReloadAt = Instant.now();
        lastReloadResults = results;
        return results;
    }

    public Map<String, String> reloadEndpoint(ClamdEndpoint ep) {
        Map<String, String> results = new LinkedHashMap<>();
        results.put(ep.getName(), sendReload(ep));
        return results;
    }

    /**
     * Sends the clamd RELOAD command via raw TCP socket.
     * clamav-client 2.1.2 does not expose a reload() method, so we send it directly.
     * Unix domain socket endpoints are skipped (freshclam handles reload automatically there).
     */
    private String sendReload(ClamdEndpoint ep) {
        String host = ep.getHost();
        int port = ep.getPort();

        // Endpoint gestito da un agent: la console non lo raggiunge, e non deve.
        // Li' e' freshclam sulla macchina a tenere aggiornate le firme.
        if (host == null || host.isBlank()) {
            log.debug("Skipping RELOAD for agent-managed endpoint '{}'", ep.getName());
            return "SKIPPED (endpoint con agent — freshclam aggiorna in locale)";
        }

        // Unix socket endpoints: host is a file path — skip raw TCP reload
        if (host.startsWith("/") || host.startsWith(".")) {
            log.info("Skipping RELOAD for Unix socket endpoint '{}' ({})", ep.getName(), host);
            return "SKIPPED (Unix socket — freshclam reloads automatically)";
        }

        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 4000);
            sock.setSoTimeout(4000);
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();
            out.write("nRELOAD\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] buf = new byte[64];
            int read = in.read(buf);
            String response = read > 0 ? new String(buf, 0, read, StandardCharsets.UTF_8).trim() : "";
            log.info("RELOAD on endpoint '{}': {}", ep.getName(), response);
            return response.isEmpty() ? "OK" : response;
        } catch (Exception e) {
            log.warn("RELOAD failed on endpoint '{}': {}", ep.getName(), e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }

    public Instant getLastReloadAt() { return lastReloadAt; }
    public Map<String, String> getLastReloadResults() { return lastReloadResults; }
}
