package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.RemediationStatus;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.commands.scan.result.ScanResult;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
public class ScanExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ScanExecutionService.class);

    private final SettingsService settings;
    private final ScanJobRepository jobRepo;
    private final ObjectMapper mapper;
    private final ClamavClientProvider clientProvider;
    private final QuarantineService quarantineService;
    private final NotificationService notificationService;

    private ExecutorService executor;

    public ScanExecutionService(SettingsService settings,
                               ScanJobRepository jobRepo,
                               ObjectMapper mapper,
                               ClamavClientProvider clientProvider,
                               QuarantineService quarantineService,
                               NotificationService notificationService) {
        this.settings = settings;
        this.jobRepo = jobRepo;
        this.mapper = mapper;
        this.clientProvider = clientProvider;
        this.quarantineService = quarantineService;
        this.notificationService = notificationService;
    }

    @PostConstruct
    public void init() {
        int threads = Math.max(1, settings.concurrentScans());
        executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("scan-worker-" + t.getId());
                    // IMPORTANT: do NOT use daemon threads for the executor.
                    // Daemon threads may be terminated abruptly depending on runtime/container lifecycle.
                    t.setDaemon(false);
                    return t;
                }
        );
        log.info("Scan executor initialized with {} threads", threads);
    }

    public void enqueue(String jobId) {
        log.debug("Enqueue scan job {}", jobId);
        executor.submit(() -> runJob(jobId));
    }

    private void runJob(String jobId) {
        ScanJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Enqueued scan job {} but it does not exist in DB", jobId);
            return;
        }

        log.debug("Starting scan job {} type={} endpoint={}", jobId, job.getType(),
                job.getEndpoint() != null ? job.getEndpoint().getName() : "null");

        try {
            markRunning(jobId);
            log.debug("Job {} marked RUNNING", jobId);

            ClamavClient client = clientProvider.clientFor(job.getEndpoint());

            if (job.getType() == ScanJobType.UPLOAD) {
                Path stored = Paths.get(job.getStoredPath());
                try (InputStream in = Files.newInputStream(stored)) {
                    ScanResult result = client.scan(in);
                    handleResult(jobId, job.getType(), result, stored);
                }
            } else if (job.getType() == ScanJobType.WATCH) {
                // WATCH: stream content to clamd via INSTREAM so remote endpoints work.
                // The watched file lives in the web-client container, not on the clamd host.
                Path file = Paths.get(job.getTarget());
                if (!Files.isRegularFile(file)) {
                    // File vanished between detection and scan — not an error, just skip it.
                    finishSkipped(jobId, "File no longer accessible at scan time");
                    log.debug("Job {} SKIPPED: file gone before scan: {}", jobId, file);
                    return;
                }
                try (InputStream in = Files.newInputStream(file)) {
                    ScanResult result = client.scan(in);
                    handleResult(jobId, job.getType(), result, null);
                }
            } else if (job.getType() == ScanJobType.PATH) {
                // PATH: clamd must have access to the path on its own filesystem.
                Path target = Paths.get(job.getTarget());
                ScanResult result = client.parallelScan(target);
                handleResult(jobId, job.getType(), result, null);
            } else {
                finishError(jobId, "Unsupported job type: " + job.getType());
                log.debug("Job {} finished ERROR (unsupported type)", jobId);
            }

            // Re-read to report final persisted state
            ScanJob finished = jobRepo.findById(jobId).orElse(null);
            if (finished != null) {
                log.debug("Finished scan job {} status={} verdict={}", jobId, finished.getStatus(), finished.getVerdict());
            }
        } catch (Exception e) {
            log.error("Job {} failed", jobId, e);
            try {
                finishError(jobId, e.getMessage());
            log.debug("Job {} finished ERROR: {}", jobId, e.getMessage());
            } catch (Exception inner) {
                log.error("Job {} failed to persist ERROR state", jobId, inner);
            }
            notifyIfNeeded(jobId);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleResult(String jobId, ScanJobType type, ScanResult result, Path uploadedFile) {
        if (result instanceof ScanResult.OK) {
            finishOk(jobId);
        } else if (result instanceof ScanResult.VirusFound) {
            ScanResult.VirusFound vf = (ScanResult.VirusFound) result;
            Map<String, Collection<String>> found = vf.getFoundViruses();

            // Quarantine logic. QuarantineService itself no-ops (returns null)
            // when settings.quarantineEnabled() is off, which is exactly
            // NOT_ATTEMPTED - the same status agent-reported jobs get when
            // their group is in Detection mode.
            RemediationStatus remediation = RemediationStatus.NOT_ATTEMPTED;
            String remediationPath = null;
            if (uploadedFile != null) {
                Path q = quarantineService.quarantine(uploadedFile);
                if (q != null) {
                    remediation = RemediationStatus.QUARANTINED;
                    remediationPath = q.toString();
                } else if (settings.quarantineEnabled()) {
                    remediation = RemediationStatus.FAILED;
                }
            } else if (found != null && !found.isEmpty()) {
                List<String> moved = new ArrayList<>();
                boolean anyFailed = false;
                for (String p : found.keySet()) {
                    try {
                        Path file = Paths.get(p);
                        if (Files.isRegularFile(file)) {
                            Path q = quarantineService.quarantine(file);
                            if (q != null) moved.add(q.toString());
                            else if (settings.quarantineEnabled()) anyFailed = true;
                        }
                    } catch (Exception e) {
                        anyFailed = true;
                    }
                }
                if (!moved.isEmpty()) {
                    remediation = anyFailed ? RemediationStatus.FAILED : RemediationStatus.QUARANTINED;
                    remediationPath = String.join("; ", moved);
                } else if (anyFailed) {
                    remediation = RemediationStatus.FAILED;
                }
            }

            finishFound(jobId, found, remediation, remediationPath);
            log.debug("Job {} finished VIRUS_FOUND", jobId);
            notifyIfNeeded(jobId);
        } else {
            finishError(jobId, "Unknown scan result type: " + result);
            log.debug("Job {} finished ERROR (unknown result)", jobId);
            notifyIfNeeded(jobId);
        }
    }

    private void notifyIfNeeded(String jobId) {
        try {
            notificationService.notifyIfNeeded(jobRepo.findById(jobId).orElseThrow());
        } catch (Exception ignored) {
        }
    }

    @Transactional
    public void markRunning(String id) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        jobRepo.save(job);
    }

    @Transactional
    public void finishOk(String id) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.OK);
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
    }

    @Transactional
    public void finishFound(String id, Object foundViruses, RemediationStatus remediation, String remediationPath) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.VIRUS_FOUND);
        try {
            job.setFoundVirusesJson(mapper.writeValueAsString(foundViruses));
        } catch (Exception e) {
            job.setFoundVirusesJson(String.valueOf(foundViruses));
        }
        job.setRemediationStatus(remediation != null ? remediation : RemediationStatus.NOT_ATTEMPTED);
        if (remediationPath != null && !remediationPath.isBlank()) job.setQuarantinePath(remediationPath);
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
    }

    @Transactional
    public void finishError(String id, String message) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.ERROR);
        job.setErrorMessage(message);
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
    }

    @Transactional
    public void finishSkipped(String id, String reason) {
        ScanJob job = jobRepo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.SKIPPED);
        job.setErrorMessage(reason);
        job.setFinishedAt(Instant.now());
        jobRepo.save(job);
    }
}
