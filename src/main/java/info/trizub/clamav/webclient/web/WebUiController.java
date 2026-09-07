package info.trizub.clamav.webclient.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.*;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.repo.ScanExclusionRepository;
import info.trizub.clamav.webclient.repo.ScanJobRepository;
import info.trizub.clamav.webclient.repo.WatchedDirectoryRepository;
import info.trizub.clamav.webclient.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import info.trizub.clamav.webclient.util.ClamVersionInfo;
import xyz.capybara.clamav.ClamavClient;
import xyz.capybara.clamav.Platform;

import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class WebUiController {

    @ModelAttribute
    public void addCommonModelAttributes(Model model, Authentication authentication) {
        try {
            model.addAttribute("settings", settings.snapshot());
        } catch (Exception e) {
            model.addAttribute("settings", Collections.<String, String>emptyMap());
        }
        model.addAttribute("pingOk", Boolean.FALSE);
        model.addAttribute("versionOk", Boolean.FALSE);
        model.addAttribute("statsOk", Boolean.FALSE);
        model.addAttribute("reloadOk", Boolean.FALSE);

        if (authentication != null) {
            model.addAttribute("username", authentication.getName());
            try {
                long openAlerts = scanJobRepo.countByVerdictAndAcknowledged(ScanVerdict.VIRUS_FOUND, false);
                model.addAttribute("openAlertsCount", openAlerts);
            } catch (Exception e) {
                model.addAttribute("openAlertsCount", 0L);
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(WebUiController.class);

    private static final String PAGE_DASHBOARD = "pages/dashboard";
    private static final String PAGE_MAIN = "pages/main";
    private static final String PAGE_SCAN = "pages/scan";
    private static final String PAGE_JOBS = "pages/jobs";
    private static final String PAGE_JOB = "pages/job";
    private static final String PAGE_SETTINGS = "pages/settings";
    private static final String PAGE_ENDPOINTS = "pages/endpoints";
    private static final String PAGE_USERS = "pages/users";
    private static final String PAGE_WATCH = "pages/watch";
    private static final String PAGE_AUDIT = "pages/audit";
    private static final String PAGE_GROUPS = "pages/groups";
    private static final String PAGE_SCHEDULES = "pages/schedules";
    private static final String PAGE_ALERTS = "pages/alerts";
    private static final String PAGE_ALERT_DETAIL = "pages/alert-detail";
    private static final String PAGE_EXCLUSIONS = "pages/exclusions";

    private final SettingsService settings;
    private final EndpointService endpoints;
    private final ScanJobService jobs;
    private final ClamavClientProvider clientProvider;
    private final AuditService audit;
    private final AuditEventRepository auditRepo;
    private final AppUserRepository userRepo;
    private final UserService userService;
    private final WatchedDirectoryRepository watchRepo;
    private final ScanJobRepository scanJobRepo;
    private final EndpointGroupService groupService;
    private final ScheduledScanService scheduledScanService;
    private final SignatureReloadService signatureReloadService;
    private final ObjectMapper objectMapper;
    private final ScanExclusionRepository exclusionRepo;

    public WebUiController(SettingsService settings,
                           EndpointService endpoints,
                           ScanJobService jobs,
                           ClamavClientProvider clientProvider,
                           AuditService audit,
                           AuditEventRepository auditRepo,
                           AppUserRepository userRepo,
                           UserService userService,
                           WatchedDirectoryRepository watchRepo,
                           ScanJobRepository scanJobRepo,
                           EndpointGroupService groupService,
                           ScheduledScanService scheduledScanService,
                           SignatureReloadService signatureReloadService,
                           ObjectMapper objectMapper,
                           ScanExclusionRepository exclusionRepo) {
        this.settings = settings;
        this.endpoints = endpoints;
        this.jobs = jobs;
        this.clientProvider = clientProvider;
        this.audit = audit;
        this.auditRepo = auditRepo;
        this.userRepo = userRepo;
        this.userService = userService;
        this.watchRepo = watchRepo;
        this.scanJobRepo = scanJobRepo;
        this.groupService = groupService;
        this.scheduledScanService = scheduledScanService;
        this.signatureReloadService = signatureReloadService;
        this.objectMapper = objectMapper;
        this.exclusionRepo = exclusionRepo;
    }

    // ---- Auth ----

    @GetMapping("/login")
    public String login() { return "login"; }

    @GetMapping(value = {"/"})
    public String root() { return "redirect:/dashboard"; }

    // ---- Dashboard ----

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("endpointsCount", endpoints.all().size());
        // Dashboard shows only last 10 jobs for readability
        var dashJobs = scanJobRepo.findTop10ByOrderBySubmittedAtDesc();
        model.addAttribute("jobs", dashJobs);
        model.addAttribute("jobsCount", scanJobRepo.count());
        var def = endpoints.defaultEndpointOrEnsure();
        model.addAttribute("defaultEndpointName", def != null ? def.getName() : "—");
        long openAlerts = scanJobRepo.countByVerdictAndAcknowledged(ScanVerdict.VIRUS_FOUND, false);
        model.addAttribute("openAlertsCount", openAlerts);
        model.addAttribute("scheduledScansCount", scheduledScanService.all().size());
        // Verdict summary counts
        model.addAttribute("countOk",      scanJobRepo.countByVerdict(ScanVerdict.OK));
        model.addAttribute("countError",   scanJobRepo.countByVerdict(ScanVerdict.ERROR));
        model.addAttribute("countSkipped", scanJobRepo.countByVerdict(ScanVerdict.SKIPPED));
        model.addAttribute("countVirus",   scanJobRepo.countByVerdict(ScanVerdict.VIRUS_FOUND));
        return PAGE_DASHBOARD;
    }

    // ---- Main (endpoint health) ----

    @GetMapping({"/main"})
    public String main(@RequestParam(name="endpointId", required = false) Long endpointId, Model model) {
        ClamdEndpoint ep = endpointId != null ? endpoints.get(endpointId) : endpoints.defaultEndpointOrEnsure();
        model.addAttribute("endpoint", ep);
        model.addAttribute("endpoints", endpoints.all());

        if (ep == null) {
            model.addAttribute("pingOk", false);
            model.addAttribute("error", "No endpoints configured.");
            return PAGE_MAIN;
        }

        try {
            ClamavClient c = clientProvider.clientFor(ep);
            c.ping();
            String versionStr = c.version();
            String statsStr = c.stats();
            model.addAttribute("pingOk", true);
            model.addAttribute("version", versionStr);
            model.addAttribute("stats", statsStr);
            model.addAttribute("versionInfo", ClamVersionInfo.parse(versionStr));
            model.addAttribute("statsMap", parseStats(statsStr));
        } catch (Exception e) {
            model.addAttribute("pingOk", false);
            model.addAttribute("error", e.getMessage());
        }

        return PAGE_MAIN;
    }

    private Map<String, String> parseStats(String stats) {
        if (stats == null) return Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<>();
        String lastKey = null;
        for (String line : stats.split("\n")) {
            if (line.trim().isEmpty() || "END".equals(line.trim())) continue;
            int colon = line.indexOf(": ");
            if (colon > 0 && !Character.isWhitespace(line.charAt(0))) {
                lastKey = line.substring(0, colon).trim();
                result.put(lastKey, line.substring(colon + 2).trim());
            } else if (lastKey != null && !line.trim().isEmpty()) {
                result.put(lastKey, result.get(lastKey) + " · " + line.trim());
            }
        }
        return result;
    }

    // ---- Scan ----

    @GetMapping("/scan")
    public String scan(Model model, @RequestParam(name="endpointId", required = false) Long endpointId) {
        List<ClamdEndpoint> epList = endpoints.all();
        model.addAttribute("endpoints", epList);
        model.addAttribute("endpointId", endpointId != null ? endpointId
                : Optional.ofNullable(endpoints.defaultEndpointOrEnsure()).map(ClamdEndpoint::getId).orElse(null));
        model.addAttribute("allowedRoots", settings.allowedRoots());
        model.addAttribute("uploadMaxBytes", settings.uploadMaxBytes());
        model.addAttribute("jobs", jobs.latest());
        Map<String, List<String>> epTargets = new LinkedHashMap<>();
        for (ClamdEndpoint ep : epList) {
            epTargets.put(String.valueOf(ep.getId()), resolveFullDiskTargets(ep));
        }
        model.addAttribute("endpointTargets", epTargets);
        return PAGE_SCAN;
    }

    @PostMapping("/scan/upload")
    public String scanUpload(@RequestParam("files") List<MultipartFile> files,
                             @RequestParam("endpointId") Long endpointId,
                             Authentication auth, HttpServletRequest req, Model model) {
        try {
            ClamdEndpoint ep = endpoints.get(endpointId);
            List<ScanJob> created = jobs.createUploadJobs(files, ep, auth.getName());
            audit.record(auth, req, "SCAN_UPLOAD", "files=" + created.size(), "SUCCESS",
                    created.isEmpty() ? null : created.get(0).getId());
            return "redirect:/jobs";
        } catch (Exception e) {
            audit.record(auth, req, "SCAN_UPLOAD", e.getMessage(), "FAILED", null);
            model.addAttribute("errorMsg", e.getMessage());
            return scan(model, endpointId);
        }
    }

    @PostMapping("/scan/fulldisk")
    public String scanFullDisk(@RequestParam("endpointId") Long endpointId,
                               Authentication auth, HttpServletRequest req, Model model) {
        try {
            ClamdEndpoint ep = endpoints.get(endpointId);
            List<String> targets = resolveFullDiskTargets(ep);
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("No safe targets to scan for this endpoint " +
                        "(configured targets resolved to none after excluding critical paths). " +
                        "Check Full disk targets in Admin > Endpoints.");
            }
            String firstJobId = null;
            for (String target : targets) {
                ScanJob job = jobs.createFullDiskScanJob(target, ep, auth.getName());
                if (firstJobId == null) firstJobId = job.getId();
            }
            audit.record(auth, req, "SCAN_FULLDISK",
                    "targets=" + targets.size() + " endpoint=" + ep.getName(), "SUCCESS", firstJobId);
            return "redirect:/scan";
        } catch (Exception e) {
            audit.record(auth, req, "SCAN_FULLDISK", e.getMessage(), "FAILED", null);
            model.addAttribute("errorMsg", e.getMessage());
            return scan(model, endpointId);
        }
    }

    // "Complete" here means every real directory under root — everything except the virtual/
    // pseudo filesystems below, which are never safe to hand to clamd as a scan target (see
    // CRITICAL_UNIX_PREFIXES).
    private static final List<String> DEFAULT_UNIX_TARGETS =
            List.of("/bin", "/boot", "/etc", "/home", "/lib", "/lib64", "/media", "/mnt",
                    "/opt", "/root", "/sbin", "/srv", "/tmp", "/usr", "/var", "/data");
    private static final List<String> DEFAULT_WINDOWS_TARGETS =
            List.of("C:\\Users", "C:\\Program Files", "C:\\Program Files (x86)");

    // Paths that must NEVER be scanned, even if an admin explicitly configures them (or "/")
    // as a full-disk target. Reason: xyz.capybara:clamav-client's parallelScan(Path) has no
    // exclude/filter parameter — clamd walks the given target to the end with no way for this
    // app to prune subpaths. Handing it "/" or a virtual filesystem means it recurses into
    // /proc, /sys, etc., which can hang the scan (and, since the shared executor has a fixed
    // thread pool, block every other scan behind it) or blow up on files that lie about their
    // size. So instead of trying to exclude subpaths, this app only ever targets a curated list
    // of real directories (DEFAULT_UNIX_TARGETS) and hard-blocks anything that could expand
    // into a virtual mount.
    private static final List<String> CRITICAL_UNIX_PREFIXES =
            List.of("/proc", "/sys", "/dev", "/run");
    private static final java.util.regex.Pattern WINDOWS_DRIVE_ROOT =
            java.util.regex.Pattern.compile("^[A-Za-z]:\\\\?$");

    private boolean isCriticalPath(String target, Platform platform) {
        if (target == null || target.isBlank()) return true;
        String t = target.trim();
        if (platform == Platform.WINDOWS) {
            return WINDOWS_DRIVE_ROOT.matcher(t).matches();
        }
        if (t.equals("/")) return true;
        return CRITICAL_UNIX_PREFIXES.stream()
                .anyMatch(p -> t.equals(p) || t.startsWith(p + "/"));
    }

    private List<String> resolveFullDiskTargets(ClamdEndpoint ep) {
        String cfg = ep.getFullDiskTargets();
        List<String> base;
        if (cfg != null && !cfg.isBlank()) {
            base = Arrays.stream(cfg.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .collect(Collectors.toList());
        } else {
            base = new ArrayList<>(ep.getPlatform() == Platform.WINDOWS
                    ? DEFAULT_WINDOWS_TARGETS : DEFAULT_UNIX_TARGETS);
        }

        // Hard safety net: applies even to a custom fullDiskTargets list, so a critical path can
        // never reach clamd through this button regardless of what's configured on the endpoint.
        base = base.stream().filter(t -> !isCriticalPath(t, ep.getPlatform())).collect(Collectors.toList());

        List<String> excluded = exclusionRepo.findApplicableTo(ep)
                .stream().map(ScanExclusion::getPath).collect(Collectors.toList());
        if (excluded.isEmpty()) return base;
        return base.stream()
                .filter(t -> excluded.stream().noneMatch(ex ->
                        t.equals(ex) || t.startsWith(ex + "/") || t.startsWith(ex + "\\")))
                .collect(Collectors.toList());
    }

    @PostMapping("/scan/path")
    public String scanPath(@RequestParam("path") String path,
                           @RequestParam("endpointId") Long endpointId,
                           Authentication auth, HttpServletRequest req, Model model) {
        try {
            ClamdEndpoint ep = endpoints.get(endpointId);
            ScanJob job = jobs.createPathJob(path, ep, auth.getName());
            audit.record(auth, req, "SCAN_PATH", "path=" + path, "SUCCESS", job.getId());
            return "redirect:/scan";
        } catch (Exception e) {
            audit.record(auth, req, "SCAN_PATH", e.getMessage(), "FAILED", null);
            model.addAttribute("errorMsg", e.getMessage());
            return scan(model, endpointId);
        }
    }

    // ---- Jobs ----

    @GetMapping("/jobs")
    public String jobs(@RequestParam(name="filter", defaultValue = "all") String filter, Model model) {
        List<ScanJob> jobList;
        switch (filter) {
            case "ok"      -> jobList = scanJobRepo.findTop200ByVerdictOrderBySubmittedAtDesc(ScanVerdict.OK);
            case "error"   -> jobList = scanJobRepo.findTop200ByVerdictOrderBySubmittedAtDesc(ScanVerdict.ERROR);
            case "virus"   -> jobList = scanJobRepo.findTop200ByVerdictOrderBySubmittedAtDesc(ScanVerdict.VIRUS_FOUND);
            case "skipped" -> jobList = scanJobRepo.findTop200ByVerdictOrderBySubmittedAtDesc(ScanVerdict.SKIPPED);
            default        -> jobList = jobs.latest();
        }
        model.addAttribute("jobs", jobList);
        model.addAttribute("filter", filter);
        model.addAttribute("countAll",     scanJobRepo.count());
        model.addAttribute("countOk",      scanJobRepo.countByVerdict(ScanVerdict.OK));
        model.addAttribute("countError",   scanJobRepo.countByVerdict(ScanVerdict.ERROR));
        model.addAttribute("countVirus",   scanJobRepo.countByVerdict(ScanVerdict.VIRUS_FOUND));
        model.addAttribute("countSkipped", scanJobRepo.countByVerdict(ScanVerdict.SKIPPED));
        return PAGE_JOBS;
    }

    @GetMapping("/jobs/{id}")
    public String job(@PathVariable("id") String id, Model model) {
        var job = jobs.getOrNull(id);
        if (job == null) {
            model.addAttribute("errorMsg", "Job not found: " + id);
            model.addAttribute("jobs", jobs.latest());
            return PAGE_JOBS;
        }
        model.addAttribute("job", job);
        return PAGE_JOB;
    }

    // ---- Alerts ----

    @GetMapping("/alerts")
    public String alerts(@RequestParam(name="filter", defaultValue = "open") String filter, Model model) {
        List<ScanJob> allAlerts;
        if ("all".equals(filter)) {
            allAlerts = scanJobRepo.findByVerdictOrderBySubmittedAtDesc(ScanVerdict.VIRUS_FOUND);
        } else if ("acked".equals(filter)) {
            allAlerts = scanJobRepo.findByVerdictAndAcknowledgedOrderBySubmittedAtDesc(ScanVerdict.VIRUS_FOUND, true);
        } else {
            allAlerts = scanJobRepo.findByVerdictAndAcknowledgedOrderBySubmittedAtDesc(ScanVerdict.VIRUS_FOUND, false);
        }

        // Group by endpoint
        Map<Long, List<ScanJob>> byEndpointId = new LinkedHashMap<>();
        List<ScanJob> orphans = new ArrayList<>();
        for (ScanJob j : allAlerts) {
            if (j.getEndpoint() == null) {
                orphans.add(j);
            } else {
                byEndpointId.computeIfAbsent(j.getEndpoint().getId(), k -> new ArrayList<>()).add(j);
            }
        }

        // Build ordered groups (most open alerts first).
        // Track which endpoint ids still exist, so alerts pointing at a
        // deleted/hidden endpoint are NOT silently dropped from the list.
        java.util.Set<Long> knownEndpointIds = new java.util.HashSet<>();
        List<EndpointAlertGroup> endpointGroups = new ArrayList<>();
        for (ClamdEndpoint ep : endpoints.all()) {
            knownEndpointIds.add(ep.getId());
            List<ScanJob> list = byEndpointId.get(ep.getId());
            if (list != null && !list.isEmpty()) {
                endpointGroups.add(new EndpointAlertGroup(ep, list));
            }
        }
        // Alerts whose endpoint id is no longer in endpoints.all(): keep them
        // visible using the (stale) endpoint reference carried by the job itself.
        for (Map.Entry<Long, List<ScanJob>> e : byEndpointId.entrySet()) {
            if (!knownEndpointIds.contains(e.getKey())) {
                ClamdEndpoint stale = e.getValue().isEmpty() ? null : e.getValue().get(0).getEndpoint();
                endpointGroups.add(new EndpointAlertGroup(stale, e.getValue()));
            }
        }
        endpointGroups.sort(Comparator.comparingLong(EndpointAlertGroup::getOpenCount).reversed());

        if (!orphans.isEmpty()) {
            endpointGroups.add(new EndpointAlertGroup(null, orphans));
        }

        model.addAttribute("endpointGroups", endpointGroups);
        model.addAttribute("filter", filter);
        model.addAttribute("openCount", scanJobRepo.countByVerdictAndAcknowledged(ScanVerdict.VIRUS_FOUND, false));
        model.addAttribute("ackedCount", scanJobRepo.countByVerdictAndAcknowledged(ScanVerdict.VIRUS_FOUND, true));
        return PAGE_ALERTS;
    }

    @GetMapping("/alerts/{id}")
    public String alertDetail(@PathVariable("id") String id, Model model) {
        ScanJob job = jobs.getOrNull(id);
        if (job == null) {
            model.addAttribute("errorMsg", "Alert not found: " + id);
            return alerts("open", model);
        }
        model.addAttribute("job", job);
        model.addAttribute("parentService", deriveParentService(job));
        model.addAttribute("parentPath", deriveParentPath(job));
        model.addAttribute("virusEntries", parseVirusEntries(job.getFoundVirusesJson()));
        return PAGE_ALERT_DETAIL;
    }

    @PostMapping("/alerts/{id}/ack")
    public String alertAck(@PathVariable("id") String id, Authentication auth,
                           HttpServletRequest req) {
        jobs.acknowledge(id, auth.getName());
        audit.record(auth, req, "ALERT_ACK", "jobId=" + id, "SUCCESS", id);
        return "redirect:/alerts/" + id;
    }

    @PostMapping("/alerts/ack-all")
    public String alertAckAll(Authentication auth, HttpServletRequest req) {
        jobs.acknowledgeAll(auth.getName());
        audit.record(auth, req, "ALERT_ACK_ALL", null, "SUCCESS", null);
        return "redirect:/alerts";
    }

    // ---- Alert helpers ----

    private String deriveParentService(ScanJob job) {
        String by = job.getSubmittedBy();
        if (by == null) return "Unknown";
        if (by.startsWith("scheduler:")) return "Scheduled Scan — " + by.substring("scheduler:".length());
        if ("watcher".equals(by)) return "Directory Watcher";
        return "Manual — " + by;
    }

    private String deriveParentPath(ScanJob job) {
        if (job.getType() == ScanJobType.WATCH) {
            try {
                java.nio.file.Path p = Paths.get(job.getTarget());
                java.nio.file.Path parent = p.getParent();
                return parent != null ? parent.toString() : job.getTarget();
            } catch (Exception e) {
                return job.getTarget();
            }
        }
        if (job.getType() == ScanJobType.UPLOAD && job.getStoredPath() != null) {
            try {
                java.nio.file.Path p = Paths.get(job.getStoredPath());
                java.nio.file.Path parent = p.getParent();
                return parent != null ? parent.toString() : job.getStoredPath();
            } catch (Exception e) {
                return job.getStoredPath();
            }
        }
        return job.getTarget();
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> parseVirusEntries(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            return Collections.singletonMap("(raw)", Collections.singletonList(json));
        }
    }

    // ---- Admin: Settings ----

    @GetMapping("/admin/settings")
    public String adminSettings(Model model) {
        model.addAttribute("settings", settings.snapshot());
        model.addAttribute("lastReloadAt", signatureReloadService.getLastReloadAt());
        model.addAttribute("lastReloadResults", signatureReloadService.getLastReloadResults());
        return PAGE_SETTINGS;
    }

    @PostMapping("/admin/settings")
    public String adminSettingsSave(@RequestParam Map<String,String> params,
                                    Authentication auth, HttpServletRequest req) {
        Map<String,String> allowed = new HashMap<>();
        for (String key : List.of(
                "app.allowedScanRoots",
                "app.upload.maxBytes",
                "app.concurrentScans",
                "app.storage.uploadDir",
                "app.storage.quarantineDir",
                "app.quarantine.enabled",
                "app.webhook.enabled",
                "app.webhook.url",
                "app.telegram.enabled",
                "app.telegram.botToken",
                "app.telegram.chatId",
                "app.watch.enabled",
                "app.watch.pollSeconds",
                "app.signatureReload.enabled",
                "app.signatureReload.cron"
        )) {
            if (params.containsKey(key)) allowed.put(key, params.get(key));
        }
        settings.updateFromMap(allowed);
        signatureReloadService.reschedule();
        audit.record(auth, req, "ADMIN_SETTINGS_UPDATE", "keys=" + allowed.keySet(), "SUCCESS", null);
        return "redirect:/admin/settings";
    }

    @PostMapping("/admin/signatures/reload-now")
    public String adminSignaturesReloadNow(Authentication auth, HttpServletRequest req) {
        Map<String, String> results = signatureReloadService.reloadAll();
        audit.record(auth, req, "SIGNATURE_RELOAD", results.toString(), "SUCCESS", null);
        return "redirect:/admin/settings";
    }

    @PostMapping("/admin/signatures/{id}/reload")
    public String adminSignaturesReloadEndpoint(@PathVariable Long id,
                                                Authentication auth, HttpServletRequest req) {
        var ep = endpoints.get(id);
        signatureReloadService.reloadEndpoint(ep);
        audit.record(auth, req, "SIGNATURE_RELOAD_ENDPOINT", ep.getName(), "SUCCESS", null);
        return "redirect:/admin/endpoints";
    }

    // ---- Admin: Endpoints ----

    @GetMapping("/admin/endpoints")
    public String adminEndpoints(Model model) {
        model.addAttribute("endpoints", endpoints.all());
        model.addAttribute("groups", groupService.all());
        return PAGE_ENDPOINTS;
    }

    @PostMapping("/admin/endpoints/create")
    public String adminEndpointsCreate(@RequestParam String name,
                                       @RequestParam String host,
                                       @RequestParam int port,
                                       @RequestParam String platform,
                                       @RequestParam(defaultValue = "true") boolean enabled,
                                       @RequestParam(required = false) Long groupId,
                                       Authentication auth, HttpServletRequest req) {
        ClamdEndpoint ep = endpoints.create(name, host, port,
                xyz.capybara.clamav.Platform.valueOf(platform), enabled);
        if (groupId != null) {
            endpoints.setGroup(ep.getId(), groupId);
        }
        audit.record(auth, req, "ENDPOINT_CREATE", name + "@" + host + ":" + port, "SUCCESS", null);
        return "redirect:/admin/endpoints";
    }

    @PostMapping("/admin/endpoints/{id}/update")
    public String adminEndpointsUpdate(@PathVariable Long id,
                                       @RequestParam String name,
                                       @RequestParam String host,
                                       @RequestParam int port,
                                       @RequestParam String platform,
                                       @RequestParam(defaultValue = "true") boolean enabled,
                                       @RequestParam(required = false) Long groupId,
                                       Authentication auth, HttpServletRequest req) {
        endpoints.update(id, name, host, port, xyz.capybara.clamav.Platform.valueOf(platform), enabled);
        endpoints.setGroup(id, groupId);
        audit.record(auth, req, "ENDPOINT_UPDATE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/endpoints";
    }

    @PostMapping("/admin/endpoints/{id}/delete")
    public String adminEndpointsDelete(@PathVariable Long id, Authentication auth, HttpServletRequest req) {
        endpoints.delete(id);
        audit.record(auth, req, "ENDPOINT_DELETE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/endpoints";
    }

    @PostMapping("/admin/endpoints/{id}/scan-targets")
    public String adminEndpointScanTargets(@PathVariable Long id,
                                           @RequestParam(defaultValue = "") String targets,
                                           Authentication auth, HttpServletRequest req) {
        endpoints.saveScanTargets(id, targets);
        audit.record(auth, req, "ENDPOINT_SCAN_TARGETS_UPDATE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/endpoints";
    }

    // ---- Admin: Endpoint Groups ----

    @GetMapping("/admin/groups")
    public String adminGroups(Model model) {
        model.addAttribute("groups", groupService.all());
        model.addAttribute("endpoints", endpoints.all());
        return PAGE_GROUPS;
    }

    @PostMapping("/admin/groups/create")
    public String adminGroupsCreate(@RequestParam String name,
                                    @RequestParam(required = false) String description,
                                    Authentication auth, HttpServletRequest req) {
        groupService.create(name, description != null ? description : "");
        audit.record(auth, req, "GROUP_CREATE", name, "SUCCESS", null);
        return "redirect:/admin/groups";
    }

    @PostMapping("/admin/groups/{id}/update")
    public String adminGroupsUpdate(@PathVariable Long id,
                                    @RequestParam String name,
                                    @RequestParam(required = false) String description,
                                    Authentication auth, HttpServletRequest req) {
        groupService.update(id, name, description != null ? description : "");
        audit.record(auth, req, "GROUP_UPDATE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/groups";
    }

    @PostMapping("/admin/groups/{id}/delete")
    public String adminGroupsDelete(@PathVariable Long id, Authentication auth, HttpServletRequest req) {
        groupService.delete(id);
        audit.record(auth, req, "GROUP_DELETE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/groups";
    }

    // ---- Admin: Scheduled Scans ----

    @GetMapping("/admin/schedules")
    public String adminSchedules(Model model) {
        model.addAttribute("schedules", scheduledScanService.all());
        model.addAttribute("endpoints", endpoints.all());
        model.addAttribute("groups", groupService.all());
        return PAGE_SCHEDULES;
    }

    @PostMapping("/admin/schedules/create")
    public String adminSchedulesCreate(@RequestParam String name,
                                       @RequestParam String cronExpression,
                                       @RequestParam String scanPath,
                                       @RequestParam String targetType,
                                       @RequestParam(required = false) Long endpointId,
                                       @RequestParam(required = false) Long groupId,
                                       @RequestParam(defaultValue = "true") boolean enabled,
                                       Authentication auth, HttpServletRequest req,
                                       Model model) {
        try {
            scheduledScanService.create(name, cronExpression, scanPath,
                    ScheduledScanTargetType.valueOf(targetType),
                    endpointId, groupId, enabled, auth.getName());
            audit.record(auth, req, "SCHEDULE_CREATE", name + " cron=" + cronExpression, "SUCCESS", null);
        } catch (Exception e) {
            audit.record(auth, req, "SCHEDULE_CREATE", e.getMessage(), "FAILED", null);
            model.addAttribute("errorMsg", e.getMessage());
            return adminSchedules(model);
        }
        return "redirect:/admin/schedules";
    }

    @PostMapping("/admin/schedules/{id}/toggle")
    public String adminSchedulesToggle(@PathVariable Long id, Authentication auth, HttpServletRequest req) {
        scheduledScanService.toggle(id);
        audit.record(auth, req, "SCHEDULE_TOGGLE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/schedules";
    }

    @PostMapping("/admin/schedules/{id}/run-now")
    public String adminSchedulesRunNow(@PathVariable Long id, Authentication auth, HttpServletRequest req) {
        scheduledScanService.runScanNow(id);
        audit.record(auth, req, "SCHEDULE_RUN_NOW", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/schedules";
    }

    @PostMapping("/admin/schedules/{id}/delete")
    public String adminSchedulesDelete(@PathVariable Long id, Authentication auth, HttpServletRequest req) {
        scheduledScanService.delete(id);
        audit.record(auth, req, "SCHEDULE_DELETE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/schedules";
    }

    // ---- Admin: Users ----

    @GetMapping("/admin/users")
    public String adminUsers(Model model) {
        model.addAttribute("users", userRepo.findAll());
        return PAGE_USERS;
    }

    @PostMapping("/admin/users/create")
    public String adminUsersCreate(@RequestParam String username,
                                   @RequestParam String password,
                                   @RequestParam(defaultValue = "true") boolean enabled,
                                   @RequestParam(defaultValue = "VIEWER") String role,
                                   Authentication auth, HttpServletRequest req) {
        Set<String> roles = new HashSet<>();
        if ("ADMIN".equalsIgnoreCase(role)) {
            roles.add(Role.ADMIN.asAuthority());
            roles.add(Role.OPERATOR.asAuthority());
            roles.add(Role.VIEWER.asAuthority());
        } else if ("OPERATOR".equalsIgnoreCase(role)) {
            roles.add(Role.OPERATOR.asAuthority());
            roles.add(Role.VIEWER.asAuthority());
        } else {
            roles.add(Role.VIEWER.asAuthority());
        }
        userService.createUser(username, password, roles, enabled);
        audit.record(auth, req, "USER_CREATE", username, "SUCCESS", null);
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/delete")
    public String adminUsersDelete(@PathVariable Long id, Authentication auth, HttpServletRequest req) {
        userService.deleteUser(id);
        audit.record(auth, req, "USER_DELETE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/reset")
    public String adminUsersReset(@PathVariable Long id,
                                  @RequestParam String newPassword,
                                  Authentication auth, HttpServletRequest req) {
        userService.resetPassword(id, newPassword);
        audit.record(auth, req, "USER_RESET_PASSWORD", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/users";
    }

    // ---- Admin: Watch dirs ----

    @GetMapping("/admin/watch")
    public String adminWatch(Model model) {
        model.addAttribute("watchEnabled", settings.watchEnabled());
        model.addAttribute("watchDirs", watchRepo.findAll());
        model.addAttribute("endpoints", endpoints.all());
        model.addAttribute("allowedRoots", settings.allowedRoots());
        return PAGE_WATCH;
    }

    @PostMapping("/admin/watch/create")
    public String adminWatchCreate(@RequestParam String path,
                                   @RequestParam Long endpointId,
                                   Authentication auth, HttpServletRequest req) {
        var ep = endpoints.get(endpointId);
        watchRepo.save(new info.trizub.clamav.webclient.model.WatchedDirectory(path, ep));
        audit.record(auth, req, "WATCH_CREATE", path, "SUCCESS", null);
        return "redirect:/admin/watch";
    }

    @PostMapping("/admin/watch/{id}/toggle")
    public String adminWatchToggle(@PathVariable Long id, Authentication auth, HttpServletRequest req) {
        var wd = watchRepo.findById(id).orElseThrow();
        wd.setEnabled(!wd.isEnabled());
        watchRepo.save(wd);
        audit.record(auth, req, "WATCH_TOGGLE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/watch";
    }

    @PostMapping("/admin/watch/{id}/delete")
    public String adminWatchDelete(@PathVariable Long id, Authentication auth, HttpServletRequest req) {
        watchRepo.deleteById(id);
        audit.record(auth, req, "WATCH_DELETE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/watch";
    }

    // ---- Admin: Scan Exclusions ----

    @GetMapping("/admin/exclusions")
    public String adminExclusions(Model model) {
        List<ScanExclusion> excls = exclusionRepo.findAllByOrderByEndpointAscPathAsc();
        model.addAttribute("exclusions", excls);
        model.addAttribute("endpoints", endpoints.all());
        String snippet = excls.isEmpty() ? "" :
                "# Scan exclusions — generated by ClamAV Web Client\n" +
                "# Add to /etc/clamav/clamd.conf and restart clamd\n" +
                excls.stream()
                    .map(e -> "ExcludePath ^" + e.getPath())
                    .collect(Collectors.joining("\n"));
        model.addAttribute("clamdSnippet", snippet);
        return PAGE_EXCLUSIONS;
    }

    @PostMapping("/admin/exclusions/create")
    public String adminExclusionsCreate(@RequestParam(required = false) Long endpointId,
                                        @RequestParam String path,
                                        @RequestParam(defaultValue = "") String reason,
                                        Authentication auth, HttpServletRequest req) {
        ScanExclusion ex = new ScanExclusion();
        ex.setPath(path.trim());
        ex.setReason(reason.isBlank() ? null : reason.trim());
        ex.setCreatedAt(java.time.Instant.now());
        ex.setCreatedBy(auth.getName());
        if (endpointId != null) {
            ex.setEndpoint(endpoints.get(endpointId));
        }
        exclusionRepo.save(ex);
        audit.record(auth, req, "EXCLUSION_CREATE", path, "SUCCESS", null);
        return "redirect:/admin/exclusions";
    }

    @PostMapping("/admin/exclusions/{id}/delete")
    public String adminExclusionsDelete(@PathVariable Long id, Authentication auth, HttpServletRequest req) {
        exclusionRepo.deleteById(id);
        audit.record(auth, req, "EXCLUSION_DELETE", "id=" + id, "SUCCESS", null);
        return "redirect:/admin/exclusions";
    }

    // ---- Admin: Audit log ----

    @GetMapping("/admin/audit")
    public String adminAudit(Model model) {
        model.addAttribute("auditEvents", auditRepo.findTop200ByOrderByAtDesc());
        return PAGE_AUDIT;
    }
}
