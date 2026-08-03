package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

@Service
public class SignatureReloadService {

    private static final Logger log = LoggerFactory.getLogger(SignatureReloadService.class);

    private final SettingsService settings;
    private final ClamdEndpointRepository endpointRepo;
    private final ClamavClientProvider clientProvider;
    private final ThreadPoolTaskScheduler taskScheduler;

    private volatile ScheduledFuture<?> currentFuture;
    private volatile Instant lastReloadAt;
    private volatile Map<String, String> lastReloadResults;

    public SignatureReloadService(SettingsService settings,
                                  ClamdEndpointRepository endpointRepo,
                                  ClamavClientProvider clientProvider,
                                  ThreadPoolTaskScheduler taskScheduler) {
        this.settings = settings;
        this.endpointRepo = endpointRepo;
        this.clientProvider = clientProvider;
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
        endpointRepo.findByEnabledTrue().forEach(ep -> {
            try {
                clientProvider.clientFor(ep).reload();
                results.put(ep.getName(), "OK");
                log.info("Signature reload OK on endpoint '{}'", ep.getName());
            } catch (Exception e) {
                results.put(ep.getName(), "ERROR: " + e.getMessage());
                log.warn("Signature reload failed on endpoint '{}': {}", ep.getName(), e.getMessage());
            }
        });
        lastReloadAt = Instant.now();
        lastReloadResults = results;
        return results;
    }

    public Map<String, String> reloadEndpoint(ClamdEndpoint ep) {
        Map<String, String> results = new LinkedHashMap<>();
        try {
            clientProvider.clientFor(ep).reload();
            results.put(ep.getName(), "OK");
        } catch (Exception e) {
            results.put(ep.getName(), "ERROR: " + e.getMessage());
        }
        return results;
    }

    public Instant getLastReloadAt() { return lastReloadAt; }
    public Map<String, String> getLastReloadResults() { return lastReloadResults; }
}
