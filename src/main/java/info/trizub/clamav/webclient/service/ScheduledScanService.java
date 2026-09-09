package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.ClamdEndpointRepository;
import info.trizub.clamav.webclient.repo.EndpointGroupRepository;
import info.trizub.clamav.webclient.repo.ScheduledScanRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class ScheduledScanService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledScanService.class);

    private final ScheduledScanRepository repo;
    private final ScanJobService scanJobService;
    private final ClamdEndpointRepository endpointRepo;
    private final EndpointGroupRepository groupRepo;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final AgentCommandService agentCommands;

    private final Map<Long, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    public ScheduledScanService(ScheduledScanRepository repo,
                                ScanJobService scanJobService,
                                ClamdEndpointRepository endpointRepo,
                                EndpointGroupRepository groupRepo,
                                ThreadPoolTaskScheduler taskScheduler,
                                AgentCommandService agentCommands) {
        this.repo = repo;
        this.scanJobService = scanJobService;
        this.endpointRepo = endpointRepo;
        this.groupRepo = groupRepo;
        this.taskScheduler = taskScheduler;
        this.agentCommands = agentCommands;
    }

    @PostConstruct
    public void init() {
        repo.findAll().forEach(this::register);
    }

    public synchronized void register(ScheduledScan ss) {
        cancel(ss.getId());
        if (!ss.isEnabled()) return;
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                () -> runScan(ss.getId()),
                new CronTrigger(ss.getCronExpression())
            );
            activeFutures.put(ss.getId(), future);
            log.info("Scheduled scan '{}' registered with cron '{}'", ss.getName(), ss.getCronExpression());
        } catch (Exception e) {
            log.warn("Failed to register scheduled scan '{}': {}", ss.getName(), e.getMessage());
        }
    }

    public synchronized void cancel(Long id) {
        ScheduledFuture<?> f = activeFutures.remove(id);
        if (f != null) f.cancel(false);
    }

    public void runScanNow(Long id) {
        taskScheduler.execute(() -> runScan(id));
    }

    private void runScan(Long ssId) {
        ScheduledScan ss = repo.findById(ssId).orElse(null);
        if (ss == null || !ss.isEnabled()) return;
        updateLastRunAt(ssId);
        log.info("Running scheduled scan '{}' on path '{}'", ss.getName(), ss.getScanPath());

        if (ss.getTargetType() == ScheduledScanTargetType.ENDPOINT && ss.getEndpoint() != null) {
            createJob(ss.getScanPath(), ss.getEndpoint(), ss.getName());
        } else if (ss.getTargetType() == ScheduledScanTargetType.GROUP && ss.getEndpointGroup() != null) {
            endpointRepo.findByGroupAndEnabled(ss.getEndpointGroup(), true)
                .forEach(ep -> createJob(ss.getScanPath(), ep, ss.getName()));
        }
    }

    private void createJob(String path, ClamdEndpoint ep, String scheduleName) {
        try {
            // Con un agent la scansione la fa la macchina stessa: un endpoint
            // agent-managed non ha host da contattare via TCP, quindi il job
            // diretto fallirebbe sempre. Stessa scelta di WebUiController.scanPath.
            if (ep.isAgentEnrolled()) {
                agentCommands.enqueue(ep, path, "scheduler:" + scheduleName);
            } else {
                scanJobService.createScheduledPathJob(path, ep, "scheduler:" + scheduleName);
            }
        } catch (Exception e) {
            log.warn("Scheduled scan job creation failed for path '{}' on endpoint '{}': {}", path, ep.getName(), e.getMessage());
        }
    }

    private void updateLastRunAt(Long id) {
        repo.findById(id).ifPresent(ss -> {
            ss.setLastRunAt(Instant.now());
            repo.save(ss);
        });
    }

    public List<ScheduledScan> all() { return repo.findAll(); }

    @Transactional
    public ScheduledScan create(String name, String cron, String path,
                                ScheduledScanTargetType targetType,
                                Long endpointId, Long groupId,
                                boolean enabled, String createdBy) {
        ScheduledScan ss = new ScheduledScan();
        ss.setName(name);
        ss.setCronExpression(cron);
        ss.setScanPath(path);
        ss.setTargetType(targetType);
        if (targetType == ScheduledScanTargetType.ENDPOINT && endpointId != null) {
            ss.setEndpoint(endpointRepo.findById(endpointId).orElse(null));
        } else if (targetType == ScheduledScanTargetType.GROUP && groupId != null) {
            ss.setEndpointGroup(groupRepo.findById(groupId).orElse(null));
        }
        ss.setEnabled(enabled);
        ss.setCreatedBy(createdBy);
        ScheduledScan saved = repo.save(ss);
        register(saved);
        return saved;
    }

    @Transactional
    public void toggle(Long id) {
        ScheduledScan ss = repo.findById(id).orElseThrow();
        ss.setEnabled(!ss.isEnabled());
        repo.save(ss);
        register(ss);
    }

    @Transactional
    public void delete(Long id) {
        cancel(id);
        repo.deleteById(id);
    }
}
