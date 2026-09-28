package info.trizub.clamav.webclient.api;

import info.trizub.clamav.webclient.config.AgentAuthenticationFilter;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.EndpointService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Distributes the agent.
 *
 * NB: it lives in the api package, not in web, on purpose. GlobalExceptionHandler
 * is a @ControllerAdvice limited to ...webclient.web that turns every exception
 * into "redirect:/dashboard": a download controller placed there would never
 * return a readable error, the browser would simply bounce back to the
 * dashboard without downloading anything. Everything under /agent/** is
 * authenticated by the endpoint's key (see {@link AgentAuthenticationFilter}),
 * or by an ADMIN session when the administrator downloads the installer from the UI.
 *
 * The installers are generated from the templates in resources/agent/, with the
 * console URL, the endpoint name and the key substituted in: the machine that
 * runs them is already configured, with no manual steps.
 */
@RestController
@RequestMapping("/agent")
public class AgentInstallController {

    private static final Logger log = LoggerFactory.getLogger(AgentInstallController.class);

    /** Only these files can be downloaded: no path traversal. */
    private static final Set<String> ALLOWED_FILES = Set.of(
            "install-clamd-remote.sh",
            "clamav-onacc-report.sh",
            "clamav-telegram-alert.sh",
            "clamav-agent-poll.sh");

    /**
     * Scripts embedded in the Linux/macOS installer: placeholder -> file in the jar.
     * This makes the installer self-contained: download it, run it. A bootstrap
     * that fetches more pieces fails whenever the machine cannot reach the
     * console again halfway through the install.
     */
    private static final Map<String, String> SH_EMBEDS = Map.of(
            "@@EMBED_CLAMD_INSTALLER@@", "install-clamd-remote.sh",
            "@@EMBED_ONACC_REPORT@@",    "clamav-onacc-report.sh",
            "@@EMBED_AGENT_POLL@@",      "clamav-agent-poll.sh",
            "@@EMBED_BATCH_SCAN@@",      "clamav-telegram-alert.sh");

    /** Prefix of the heredoc delimiters the template uses to embed the scripts. */
    private static final String HEREDOC_MARKER = "__CLAIMAV_EMBED_";

    private final EndpointService endpoints;

    public AgentInstallController(EndpointService endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * Checks at startup that everything needed to generate the installer really
     * is inside the jar. The scripts live at the repo root and get there through
     * maven-resources-plugin: if they are not in the build context (it happened
     * with the Dockerfile, which copied only pom.xml and src) the build still
     * succeeds, because an <include> that matches no file is not an error. Better
     * to notice here, in the startup log, than from a browser whose download fails.
     */
    @PostConstruct
    void verifyPackagedScripts() {
        List<String> missing = new ArrayList<>();
        for (String resource : List.of("agent/install.sh.tpl", "agent/install.ps1.tpl")) {
            if (!new ClassPathResource(resource).exists()) missing.add(resource);
        }
        for (String script : SH_EMBEDS.values()) {
            if (!new ClassPathResource("agent/" + script).exists()) missing.add("agent/" + script);
        }
        if (missing.isEmpty()) {
            log.info("Agent installer: all resources present in the jar.");
        } else {
            log.error("Agent installer BROKEN: resources missing from the jar: {}. "
                    + "The agent scripts live at the repo root and are copied by "
                    + "maven-resources-plugin (copy-agent-scripts); make sure the build can see them "
                    + "(the Dockerfile needs the COPY of the *.sh files).", missing);
        }
    }

    @GetMapping(value = "/install.sh", produces = "text/x-shellscript; charset=utf-8")
    public ResponseEntity<String> installSh(HttpServletRequest request,
                                            @RequestParam(required = false) String key) {
        return renderInstaller("agent/install.sh.tpl", "install-agent.sh", request, key);
    }

    @GetMapping(value = "/install.ps1", produces = "text/plain; charset=utf-8")
    public ResponseEntity<String> installPs1(HttpServletRequest request,
                                             @RequestParam(required = false) String key) {
        return renderInstaller("agent/install.ps1.tpl", "install-agent.ps1", request, key);
    }

    /** The agent scripts themselves, one at a time. */
    @GetMapping(value = "/files/{name}", produces = "text/x-shellscript; charset=utf-8")
    public ResponseEntity<String> file(@PathVariable String name) {
        if (!ALLOWED_FILES.contains(name)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Not found\n");
        }
        try {
            return ResponseEntity.ok(readClasspath("agent/" + name));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Script not available in the jar: " + name + "\n");
        }
    }

    private ResponseEntity<String> renderInstaller(String template, String filename,
                                                   HttpServletRequest request, String key) {
        Optional<ClamdEndpoint> endpoint = resolveEndpoint(request, key);
        if (endpoint.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    "Endpoint not identified. Download the installer from the console\n"
                  + "(Admin > Endpoints), or add ?key=<the endpoint's agent key>.\n");
        }
        ClamdEndpoint ep = endpoint.get();

        String body;
        try {
            body = readClasspath(template);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Installer template not available: " + template + "\n");
        }

        // The URL the machine will use to reach the console. Behind a reverse
        // proxy it is the public host only if the proxy sends the X-Forwarded-*
        // headers and the app has server.forward-headers-strategy=framework.
        String consoleUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();

        // The agent scripts themselves go inside the installer, in heredocs.
        if (body.contains("@@EMBED_")) {
            for (Map.Entry<String, String> embed : SH_EMBEDS.entrySet()) {
                if (!body.contains(embed.getKey())) continue;
                String script;
                try {
                    script = readClasspath("agent/" + embed.getValue());
                } catch (IOException e) {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                            "Agent script missing from the jar: " + embed.getValue() + "\n"
                          + "It is copied by maven-resources-plugin (execution copy-agent-scripts):\n"
                          + "rebuild with 'mvn clean package'.\n");
                }
                if (script.contains(HEREDOC_MARKER)) {
                    // If the delimiter appeared in the script, the heredoc would
                    // close halfway and the installer would be silently broken.
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                            "Script " + embed.getValue() + " contains the delimiter "
                          + HEREDOC_MARKER + ": it cannot be embedded without corrupting it.\n");
                }
                body = body.replace(embed.getKey(), script);
            }
        }

        body = body.replace("@@CONSOLE_URL@@", consoleUrl)
                   .replace("@@AGENT_KEY@@", ep.getAgentKey() == null ? "" : ep.getAgentKey())
                   .replace("@@ENDPOINT_NAME@@", ep.getName());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /**
     * The endpoint comes from the filter when the request is authenticated with
     * the key; when a logged-in ADMIN downloads instead, the filter does not step
     * in, so the endpoint is looked up from the key passed as a parameter.
     */
    private Optional<ClamdEndpoint> resolveEndpoint(HttpServletRequest request, String key) {
        Object attribute = request.getAttribute(AgentAuthenticationFilter.ENDPOINT_ID_ATTRIBUTE);
        if (attribute instanceof Long) {
            try {
                return Optional.of(endpoints.get((Long) attribute));
            } catch (Exception ignored) {
                // endpoint deleted in the meantime: fall back to the key
            }
        }
        return endpoints.findByAgentKey(key);
    }

    private String readClasspath(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
