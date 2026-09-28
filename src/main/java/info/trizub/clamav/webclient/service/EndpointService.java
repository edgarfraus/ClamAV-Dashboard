package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.model.EndpointGroup;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobStatus;
import info.trizub.clamav.webclient.model.ScanVerdict;
import info.trizub.clamav.webclient.repo.AgentCommandRepository;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import info.trizub.clamav.webclient.repo.EndpointGroupRepository;
import info.trizub.clamav.webclient.repo.ScanExclusionRepository;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.repo.ScheduledScanRepository;
import info.trizub.clamav.webclient.repo.WatchedDirectoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import xyz.capybara.clamav.Platform;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class EndpointService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClamdEndpointRepository repo;
    private final SettingsService settings;
    private final EndpointGroupRepository groupRepo;
    private final ScanJobRepository jobRepo;
    private final WatchedDirectoryRepository watchRepo;
    private final ScanExclusionRepository exclusionRepo;
    private final ScheduledScanRepository scheduledScanRepo;
    private final AgentCommandRepository agentCommandRepo;

    public EndpointService(ClamdEndpointRepository repo, SettingsService settings,
                           EndpointGroupRepository groupRepo,
                           ScanJobRepository jobRepo,
                           WatchedDirectoryRepository watchRepo,
                           ScanExclusionRepository exclusionRepo,
                           ScheduledScanRepository scheduledScanRepo,
                           AgentCommandRepository agentCommandRepo) {
        this.repo = repo;
        this.settings = settings;
        this.groupRepo = groupRepo;
        this.jobRepo = jobRepo;
        this.watchRepo = watchRepo;
        this.exclusionRepo = exclusionRepo;
        this.scheduledScanRepo = scheduledScanRepo;
        this.agentCommandRepo = agentCommandRepo;
    }

    @Transactional
    public void ensureDefaultEndpoint() {
        if (repo.count() == 0) {
            Platform p;
            try { p = Platform.valueOf(settings.legacyPlatform()); } catch (Exception e) { p = Platform.UNIX; }
            ClamdEndpoint ep = new ClamdEndpoint("default", settings.legacyHost(), settings.legacyPort(), p);
            repo.save(ep);
        }
    }

    public List<ClamdEndpoint> all() {
        return repo.findAll();
    }

    public ClamdEndpoint get(Long id) {
        return repo.findById(id).orElseThrow();
    }

    /** An endpoint is "agent-managed" when it has a key: there the direction is inverted. */
    public boolean isAgentManaged(ClamdEndpoint ep) {
        return ep != null && ep.isAgentEnrolled();
    }

    /** Empty host = agent-managed endpoint: there is nothing to contact. */
    private static String normalizeHost(String host) {
        return (host == null || host.isBlank()) ? null : host.trim();
    }

    @Transactional
    public ClamdEndpoint create(String name, String host, int port, Platform platform, boolean enabled) {
        ClamdEndpoint ep = new ClamdEndpoint(name, normalizeHost(host), port, platform);
        ep.setEnabled(enabled);
        return repo.save(ep);
    }

    @Transactional
    public ClamdEndpoint update(Long id, String name, String host, int port, Platform platform, boolean enabled) {
        ClamdEndpoint ep = repo.findById(id).orElseThrow();
        ep.setName(name);
        ep.setHost(normalizeHost(host));
        ep.setPort(port);
        ep.setPlatform(platform);
        ep.setEnabled(enabled);
        return repo.save(ep);
    }

    /**
     * Deletes an endpoint after detaching everything that points at it.
     *
     * Five entities carry an endpoint_id (scan jobs, watched directories, scan
     * exclusions, scheduled scans, agent commands), so a bare deleteById fails on
     * the foreign key as soon as the endpoint has ever been used, and the error is
     * swallowed into a redirect: the row simply refuses to disappear.
     *
     * What happens to each dependent is deliberate:
     *  - scan jobs keep their history and just lose the link; any job still queued
     *    or running is closed as ERROR, because without an endpoint the executor
     *    has nothing to talk to;
     *  - watched directories, scheduled scans and agent commands are configuration
     *    or work items bound to that endpoint: pointing them at nothing would only
     *    produce failures, so they go;
     *  - exclusions are DELETED, never detached: a null endpoint means "applies to
     *    every endpoint", so clearing the field would silently widen an exclusion
     *    to the whole fleet.
     */
    @Transactional
    public void delete(Long id) {
        ClamdEndpoint ep = repo.findById(id).orElse(null);
        if (ep == null) return;

        for (ScanJob job : jobRepo.findByEndpoint(ep)) {
            if (job.getStatus() != ScanJobStatus.FINISHED) {
                job.setStatus(ScanJobStatus.FINISHED);
                job.setVerdict(ScanVerdict.ERROR);
                job.setErrorMessage("Endpoint '" + ep.getName() + "' was deleted while this scan was pending.");
                job.setFinishedAt(Instant.now());
            }
            job.setEndpoint(null);
            jobRepo.save(job);
        }

        agentCommandRepo.deleteAll(agentCommandRepo.findByEndpoint(ep));
        watchRepo.deleteAll(watchRepo.findByEndpoint(ep));
        scheduledScanRepo.deleteAll(scheduledScanRepo.findByEndpoint(ep));
        exclusionRepo.deleteAll(exclusionRepo.findByEndpoint(ep));

        repo.delete(ep);
    }

    /**
     * Generates (or regenerates) an endpoint's agent enrollment key.
     * Regenerating invalidates the previous key immediately: agents installed
     * with the old one stop being accepted until they are reinstalled with
     * the new one.
     */
    @Transactional
    public String generateAgentKey(Long id) {
        ClamdEndpoint ep = repo.findById(id).orElseThrow();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String key = "cav_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        ep.setAgentKey(key);
        ep.setAgentLastSeenAt(null);
        repo.save(ep);
        return key;
    }

    @Transactional
    public void revokeAgentKey(Long id) {
        ClamdEndpoint ep = repo.findById(id).orElseThrow();
        ep.setAgentKey(null);
        ep.setAgentLastSeenAt(null);
        repo.save(ep);
    }

    public Optional<ClamdEndpoint> findByAgentKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return repo.findByAgentKey(key.trim());
    }

    /**
     * Updates "last seen" and, when the agent sent them, the clamd version and
     * the on-access mode it actually applied locally (not the one the group
     * asks for: this is the confirmation that the request was carried out).
     * Best-effort: it must never make the agent's request fail.
     */
    @Transactional
    public void touchAgentSeen(Long id, String clamdVersion, String onAccessMode, String agentOs) {
        try {
            repo.findById(id).ifPresent(ep -> {
                ep.setAgentLastSeenAt(Instant.now());
                if (clamdVersion != null && !clamdVersion.isBlank()) {
                    String v = clamdVersion.trim();
                    ep.setAgentClamdVersion(v.length() > 255 ? v.substring(0, 255) : v);
                }
                // "unsupported" = Windows/macOS agents reporting a platform fact (no
                // fanotify there, ever), not a mode the console asked for - still
                // worth persisting so the UI can say so instead of "not yet reported".
                if ("prevent".equals(onAccessMode) || "detect".equals(onAccessMode)
                        || "unsupported".equals(onAccessMode)) {
                    ep.setAgentOnAccessMode(onAccessMode);
                    ep.setAgentOnAccessAppliedAt(Instant.now());
                }
                if ("linux".equals(agentOs) || "macos".equals(agentOs) || "windows".equals(agentOs)) {
                    ep.setAgentOs(agentOs);
                }
                repo.save(ep);
            });
        } catch (Exception ignored) {
        }
    }

    @Transactional
    public void saveScanTargets(Long id, String targets) {
        ClamdEndpoint ep = repo.findById(id).orElseThrow();
        ep.setFullDiskTargets(targets == null || targets.isBlank() ? null : targets);
        repo.save(ep);
    }

    @Transactional
    public void setGroup(Long endpointId, Long groupId) {
        ClamdEndpoint ep = repo.findById(endpointId).orElseThrow();
        if (groupId == null) {
            ep.setGroup(null);
        } else {
            EndpointGroup g = groupRepo.findById(groupId).orElse(null);
            ep.setGroup(g);
        }
        repo.save(ep);
    }

    public ClamdEndpoint defaultEndpoint() {
        return repo.findByName("default").orElseGet(() -> repo.findAll().stream().findFirst().orElse(null));
    }

    /**
     * Like defaultEndpoint(), but guarantees a non-null return by creating the default endpoint when needed.
     */
    @Transactional
    public ClamdEndpoint defaultEndpointOrEnsure() {
        ClamdEndpoint ep = defaultEndpoint();
        if (ep == null) {
            ensureDefaultEndpoint();
            ep = defaultEndpoint();
        }
        return ep;
    }
}
