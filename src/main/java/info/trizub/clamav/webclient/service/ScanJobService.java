package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.util.PathPolicy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Service
public class ScanJobService {

    private static final Logger log = LoggerFactory.getLogger(ScanJobService.class);

    private final ScanJobRepository repo;
    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final ScanExecutionService executor;
    private final NotificationService notificationService;

    public ScanJobService(ScanJobRepository repo, SettingsService settings, ObjectMapper mapper,
                          ScanExecutionService executor, NotificationService notificationService) {
        this.repo = repo;
        this.settings = settings;
        this.mapper = mapper;
        this.executor = executor;
        this.notificationService = notificationService;
    }

    @PostConstruct
    public void resumeQueued() {
        // If app restarts, re-enqueue queued/running jobs as queued.
        // AGENT jobs are excluded: the agent runs them on its own machine, and
        // handing them to our executor would redo them over TCP (exactly what
        // the agent exists to avoid). They stay where they are: if the agent
        // never answers, AgentCommandService's timeout takes care of them.
        repo.findAll().stream()
                .filter(j -> j.getStatus() != ScanJobStatus.FINISHED)
                .filter(j -> j.getType() != ScanJobType.AGENT)
                .forEach(j -> {
                    j.setStatus(ScanJobStatus.QUEUED);
                    repo.save(j);
                    executor.enqueue(j.getId());
                });
    }

    /** Jobs submitted since an instant, oldest first — feeds the dashboard charts. */
    public List<ScanJob> submittedSince(java.time.Instant since) {
        return repo.findBySubmittedAtGreaterThanEqualOrderBySubmittedAtAsc(since);
    }

    /** When the most recent tracked scan was submitted, if there is one. */
    public java.util.Optional<java.time.Instant> lastSubmittedAt() {
        return repo.findTopByOrderBySubmittedAtDesc().map(ScanJob::getSubmittedAt);
    }

    public List<ScanJob> latest() {
        return repo.findTop200ByOrderBySubmittedAtDesc();
    }

    public List<ScanJob> activeJobs() {
        return repo.findByStatusInOrderBySubmittedAtDesc(
                java.util.Arrays.asList(ScanJobStatus.RUNNING, ScanJobStatus.QUEUED));
    }

    /**
     * Fetch a job by id.
     *
     * NOTE: The web UI should not 500 if a job id is missing (e.g. stale link),
     * so this method returns null when not found.
     */
    public ScanJob getOrNull(String id) {
        return repo.findById(id).orElse(null);
    }

    @Transactional
    public List<ScanJob> createUploadJobs(List<MultipartFile> files, ClamdEndpoint endpoint, String username) {
        List<ScanJob> jobs = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            if (f.getSize() > settings.uploadMaxBytes()) {
                throw new IllegalArgumentException("File too large: " + f.getOriginalFilename());
            }
            String id = UUID.randomUUID().toString().replace("-", "");
            Path uploadDir = settings.uploadDir();
            try {
                Files.createDirectories(uploadDir);
                String safeName = (f.getOriginalFilename() == null ? "upload" : f.getOriginalFilename()).replaceAll("[^a-zA-Z0-9._-]", "_");
                Path stored = uploadDir.resolve(id + "-" + safeName);

                // Save file
                try (InputStream in = f.getInputStream()) {
                    Files.copy(in, stored, StandardCopyOption.REPLACE_EXISTING);
                }

                // Hash
                String sha;
                try (InputStream in2 = Files.newInputStream(stored)) {
                    sha = info.trizub.clamav.webclient.util.HashUtils.sha256(in2);
                }

                ScanJob job = new ScanJob();
                job.setId(id);
                job.setType(ScanJobType.UPLOAD);
                job.setStatus(ScanJobStatus.QUEUED);
                job.setTarget(safeName);
                job.setStoredPath(stored.toString());
                job.setSha256(sha);
                job.setSizeBytes(f.getSize());
                job.setEndpoint(endpoint);
                job.setSubmittedBy(username);
                job.setSubmittedAt(Instant.now());
                repo.save(job);
                jobs.add(job);
                enqueueAfterCommit(job.getId());
                } catch (Exception e) {
                log.error("Failed to create upload job: {}", e.getMessage());
            }
        }
        return jobs;
    }

    @Transactional
    public ScanJob createPathJob(String path, ClamdEndpoint endpoint, String username) {
        Path requested = PathPolicy.normalize(path);

        if (!PathPolicy.isUnderAllowedRoots(requested, settings.allowedRoots())) {
            throw new IllegalArgumentException("Path is not allowed by policy. Allowed roots: " + settings.allowedRoots());
        }

        String id = UUID.randomUUID().toString().replace("-", "");
        ScanJob job = new ScanJob();
        job.setId(id);
        job.setType(ScanJobType.PATH);
        job.setStatus(ScanJobStatus.QUEUED);
        job.setTarget(requested.toString());
        job.setEndpoint(endpoint);
        job.setSubmittedBy(username);
        job.setSubmittedAt(Instant.now());
        repo.save(job);
        enqueueAfterCommit(job.getId());
                return job;
    }

    @Transactional
    public ScanJob createFullDiskScanJob(String path, ClamdEndpoint endpoint, String username) {
        // Full disk scan bypasses allowedRoots — user explicitly requested scanning the disk root
        String id = UUID.randomUUID().toString().replace("-", "");
        ScanJob job = new ScanJob();
        job.setId(id);
        job.setType(ScanJobType.PATH);
        job.setStatus(ScanJobStatus.QUEUED);
        job.setTarget(path);
        job.setEndpoint(endpoint);
        job.setSubmittedBy(username);
        job.setSubmittedAt(Instant.now());
        repo.save(job);
        enqueueAfterCommit(job.getId());
        return job;
    }

    /**
     * Job for a scan the agent will run on the machine.
     * Created QUEUED and deliberately NOT handed to the executor: the agent does
     * the work, then closes the job via /api/scan/report with the commandId.
     */
    @Transactional
    public ScanJob createAgentJob(String target, ClamdEndpoint endpoint, String username) {
        String id = UUID.randomUUID().toString().replace("-", "");
        ScanJob job = new ScanJob();
        job.setId(id);
        job.setType(ScanJobType.AGENT);
        job.setStatus(ScanJobStatus.QUEUED);
        job.setTarget(target);
        job.setEndpoint(endpoint);
        job.setSubmittedBy(username);
        job.setSubmittedAt(Instant.now());
        return repo.save(job);
    }

    @Transactional
    public ScanJob createScheduledPathJob(String path, ClamdEndpoint endpoint, String submittedBy) {
        // Scheduled scans bypass allowedRoots check — admin configured the path
        Path requested = PathPolicy.normalize(path);
        String id = UUID.randomUUID().toString().replace("-", "");
        ScanJob job = new ScanJob();
        job.setId(id);
        job.setType(ScanJobType.PATH);
        job.setStatus(ScanJobStatus.QUEUED);
        job.setTarget(requested.toString());
        job.setEndpoint(endpoint);
        job.setSubmittedBy(submittedBy);
        job.setSubmittedAt(Instant.now());
        repo.save(job);
        enqueueAfterCommit(job.getId());
        return job;
    }

    @Transactional
    public void acknowledge(String jobId, String username) {
        ScanJob job = repo.findById(jobId).orElseThrow();
        job.setAcknowledged(true);
        job.setAcknowledgedBy(username);
        job.setAcknowledgedAt(Instant.now());
        repo.save(job);
    }

    @Transactional
    public void acknowledgeAll(String username) {
        repo.findByVerdictAndAcknowledgedOrderBySubmittedAtDesc(
                info.trizub.clamav.webclient.model.ScanVerdict.VIRUS_FOUND, false)
            .forEach(job -> {
                job.setAcknowledged(true);
                job.setAcknowledgedBy(username);
                job.setAcknowledgedAt(Instant.now());
                repo.save(job);
            });
    }

    @Transactional
    public ScanJob createWatchFileJob(Path file, ClamdEndpoint endpoint, String username) {
        String id = UUID.randomUUID().toString().replace("-", "");
        ScanJob job = new ScanJob();
        job.setId(id);
        job.setType(ScanJobType.WATCH);
        job.setStatus(ScanJobStatus.QUEUED);
        job.setTarget(file.toAbsolutePath().normalize().toString());
        job.setEndpoint(endpoint);
        job.setSubmittedBy(username != null ? username : "watcher");
        job.setSubmittedAt(Instant.now());
        repo.save(job);
        enqueueAfterCommit(job.getId());
                return job;
    }

    /**
     * Records the result of a scan that was executed elsewhere (e.g. a clamdscan cron job on a
     * fleet machine) instead of by this app's own executor. The job is created already FINISHED
     * — there is nothing to enqueue — and notifications (Telegram/webhook) fire immediately,
     * same as a normal VIRUS_FOUND/ERROR job.
     */
    @Transactional
    public ScanJob createExternalReport(String hostname, String path, ScanVerdict verdict,
                                        Map<String, List<String>> foundViruses, String errorMessage,
                                        ScanJobType type, ClamdEndpoint endpoint, String username,
                                        RemediationStatus remediation, String remediationPath) {
        String id = UUID.randomUUID().toString().replace("-", "");
        ScanJob job = new ScanJob();
        job.setId(id);
        job.setType(type != null ? type : ScanJobType.EXTERNAL);
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(verdict);
        // scan_jobs.target is NOT NULL, but a spontaneous report can arrive with
        // no path (an on-access event about the whole machine): in that case the
        // host is shown, which is the useful piece of information.
        job.setTarget(path != null && !path.isBlank() ? path : hostname);
        job.setSourceHost(hostname);
        // When the report comes from an agent authenticated with the endpoint's
        // key, the job is bound to that endpoint instead of an "orphan" host.
        job.setEndpoint(endpoint);
        job.setSubmittedBy(username);
        Instant now = Instant.now();
        job.setSubmittedAt(now);
        job.setStartedAt(now);
        job.setFinishedAt(now);
        if (verdict == ScanVerdict.VIRUS_FOUND) {
            try {
                job.setFoundVirusesJson(mapper.writeValueAsString(foundViruses));
            } catch (Exception e) {
                job.setFoundVirusesJson(String.valueOf(foundViruses));
            }
            job.setRemediationStatus(remediation != null ? remediation : RemediationStatus.NOT_ATTEMPTED);
            if (remediationPath != null && !remediationPath.isBlank()) job.setQuarantinePath(remediationPath);
        } else if (verdict == ScanVerdict.ERROR) {
            job.setErrorMessage(errorMessage);
        }
        repo.save(job);
        notificationService.notifyIfNeeded(job);
        return job;
    }


private void enqueueAfterCommit(String jobId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.enqueue(jobId);
            }
        });
    } else {
        executor.enqueue(jobId);
    }
}

/**
     * Sends webhook/Telegram for a job that has already finished. Needed for
     * jobs closed by an agent: they never pass through ScanExecutionService,
     * which is where notifications normally fire.
     */
    public void notifyIfNeeded(String jobId) {
        try {
            repo.findById(jobId).ifPresent(notificationService::notifyIfNeeded);
        } catch (Exception e) {
            log.warn("Notification not sent for job {}: {}", jobId, e.getMessage());
        }
    }

    @Transactional
    public void finishOk(String id) {
        ScanJob job = repo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.OK);
        job.setFinishedAt(Instant.now());
        repo.save(job);
    }

    /**
     * Result of a scan launched from the console and closed by an agent
     * (POST /api/scan/report with commandId). remediation is what the agent
     * actually did to the infected file on ITS machine - the console has no
     * other way to know, since there is no direct channel to the host.
     */
    @Transactional
    public void finishFound(String id, Object foundViruses, RemediationStatus remediation, String remediationPath) {
        ScanJob job = repo.findById(id).orElseThrow();
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
        repo.save(job);
    }

    @Transactional
    public void finishError(String id, String message) {
        ScanJob job = repo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.FINISHED);
        job.setVerdict(ScanVerdict.ERROR);
        job.setErrorMessage(message);
        job.setFinishedAt(Instant.now());
        repo.save(job);
    }

    @Transactional
    public void markRunning(String id) {
        ScanJob job = repo.findById(id).orElseThrow();
        job.setStatus(ScanJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        repo.save(job);
    }

}
