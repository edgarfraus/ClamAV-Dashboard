package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.config.AgentAuthenticationFilter;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.EndpointService;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Optional;
import java.util.Set;

/**
 * Distribuisce l'agent. Tutto sotto /agent/** e' autenticato dalla chiave
 * dell'endpoint (vedi {@link AgentAuthenticationFilter}), oppure da una sessione
 * ADMIN quando e' l'amministratore a scaricare l'installer dalla UI.
 *
 * Gli script di installazione sono generati a partire dai template in
 * resources/agent/, sostituendo URL della console, nome dell'endpoint e chiave:
 * la macchina che li esegue e' cosi' gia' configurata, senza passaggi manuali.
 */
@RestController
@RequestMapping("/agent")
public class AgentInstallController {

    /** Solo questi file possono essere scaricati: niente path traversal. */
    private static final Set<String> ALLOWED_FILES = Set.of(
            "install-clamd-remote.sh",
            "clamav-onacc-report.sh",
            "clamav-telegram-alert.sh",
            "clamav-agent-poll.sh");

    private final EndpointService endpoints;

    public AgentInstallController(EndpointService endpoints) {
        this.endpoints = endpoints;
    }

    @GetMapping(value = "/install.sh", produces = "text/x-shellscript; charset=utf-8")
    public ResponseEntity<String> installSh(HttpServletRequest request,
                                            @RequestParam(required = false) String key) {
        return renderInstaller("agent/install.sh.tpl", "install.sh", request, key);
    }

    @GetMapping(value = "/install.ps1", produces = "text/plain; charset=utf-8")
    public ResponseEntity<String> installPs1(HttpServletRequest request,
                                             @RequestParam(required = false) String key) {
        return renderInstaller("agent/install.ps1.tpl", "install.ps1", request, key);
    }

    /** Script veri e propri dell'agent, scaricati dall'installer durante l'enrollment. */
    @GetMapping(value = "/files/{name}", produces = "text/x-shellscript; charset=utf-8")
    public ResponseEntity<String> file(@PathVariable String name) {
        if (!ALLOWED_FILES.contains(name)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Not found\n");
        }
        try {
            return ResponseEntity.ok(readClasspath("agent/" + name));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Script non disponibile nel jar: " + name + "\n");
        }
    }

    private ResponseEntity<String> renderInstaller(String template, String filename,
                                                   HttpServletRequest request, String key) {
        Optional<ClamdEndpoint> endpoint = resolveEndpoint(request, key);
        if (endpoint.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    "Endpoint non identificato. Scarica l'installer dalla console\n"
                  + "(Admin > Endpoints), oppure aggiungi ?key=<chiave dell'endpoint>.\n");
        }
        ClamdEndpoint ep = endpoint.get();

        String body;
        try {
            body = readClasspath(template);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Template dell'installer non disponibile: " + template + "\n");
        }

        // URL con cui la macchina raggiungera' la console. Dietro reverse proxy
        // vale l'host pubblico solo se il proxy manda gli header X-Forwarded-*
        // e l'app ha server.forward-headers-strategy=framework.
        String consoleUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();

        body = body.replace("@@CONSOLE_URL@@", consoleUrl)
                   .replace("@@AGENT_KEY@@", ep.getAgentKey() == null ? "" : ep.getAgentKey())
                   .replace("@@ENDPOINT_NAME@@", ep.getName());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /**
     * L'endpoint arriva dal filtro quando la richiesta e' autenticata con la chiave;
     * quando invece a scaricare e' un ADMIN gia' loggato il filtro non interviene,
     * quindi ricaviamo l'endpoint dalla chiave passata come parametro.
     */
    private Optional<ClamdEndpoint> resolveEndpoint(HttpServletRequest request, String key) {
        Object attribute = request.getAttribute(AgentAuthenticationFilter.ENDPOINT_ID_ATTRIBUTE);
        if (attribute instanceof Long) {
            try {
                return Optional.of(endpoints.get((Long) attribute));
            } catch (Exception ignored) {
                // endpoint sparito nel frattempo: ripiego sulla chiave
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
