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
 * Distribuisce l'agent.
 *
 * NB: sta nel package api, non in web, di proposito. GlobalExceptionHandler e'
 * un @ControllerAdvice limitato a ...webclient.web e trasforma ogni eccezione in
 * "redirect:/dashboard": un controller di download messo li' non restituirebbe
 * mai un errore leggibile, il browser verrebbe semplicemente rimbalzato alla
 * dashboard senza scaricare nulla. Tutto sotto /agent/** e' autenticato dalla chiave
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

    private static final Logger log = LoggerFactory.getLogger(AgentInstallController.class);

    /** Solo questi file possono essere scaricati: niente path traversal. */
    private static final Set<String> ALLOWED_FILES = Set.of(
            "install-clamd-remote.sh",
            "clamav-onacc-report.sh",
            "clamav-telegram-alert.sh",
            "clamav-agent-poll.sh");

    /**
     * Script incorporati nell'installer per Linux/macOS: segnaposto -> file nel jar.
     * L'installer e' cosi' autosufficiente, si scarica e si esegue. Un bootstrap
     * che scarica altri pezzi fallisce ogni volta che la macchina non riesce a
     * ricontattare la console a meta' installazione.
     */
    private static final Map<String, String> SH_EMBEDS = Map.of(
            "@@EMBED_CLAMD_INSTALLER@@", "install-clamd-remote.sh",
            "@@EMBED_ONACC_REPORT@@",    "clamav-onacc-report.sh",
            "@@EMBED_AGENT_POLL@@",      "clamav-agent-poll.sh",
            "@@EMBED_BATCH_SCAN@@",      "clamav-telegram-alert.sh");

    /** Prefisso dei delimitatori heredoc usati nel template per incorporare gli script. */
    private static final String HEREDOC_MARKER = "__CLAIMAV_EMBED_";

    private final EndpointService endpoints;

    public AgentInstallController(EndpointService endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * Verifica all'avvio che tutto il necessario per generare l'installer sia
     * davvero dentro il jar. Gli script vivono nella root del repo e ci finiscono
     * tramite maven-resources-plugin: se non entrano nel build context (e' successo
     * col Dockerfile, che copiava solo pom.xml e src) il build riesce lo stesso,
     * perche' un <include> che non trova file non e' un errore. Meglio accorgersene
     * qui, nei log all'avvio, che dal browser quando il download non parte.
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
            log.info("Agent installer: tutte le risorse presenti nel jar.");
        } else {
            log.error("Agent installer NON funzionante: risorse mancanti nel jar: {}. "
                    + "Gli script dell'agent stanno nella root del repo e vengono copiati da "
                    + "maven-resources-plugin (copy-agent-scripts); controlla che il build li abbia "
                    + "a disposizione (nel Dockerfile serve la COPY degli *.sh).", missing);
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

        // Gli script veri e propri finiscono dentro l'installer, dentro heredoc.
        if (body.contains("@@EMBED_")) {
            for (Map.Entry<String, String> embed : SH_EMBEDS.entrySet()) {
                if (!body.contains(embed.getKey())) continue;
                String script;
                try {
                    script = readClasspath("agent/" + embed.getValue());
                } catch (IOException e) {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                            "Script dell'agent mancante nel jar: " + embed.getValue() + "\n"
                          + "Viene copiato da maven-resources-plugin (esecuzione copy-agent-scripts):\n"
                          + "ricompila con 'mvn clean package'.\n");
                }
                if (script.contains(HEREDOC_MARKER)) {
                    // Se il delimitatore comparisse nello script, l'heredoc si
                    // chiuderebbe a meta' e l'installer sarebbe silenziosamente rotto.
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                            "Lo script " + embed.getValue() + " contiene il delimitatore "
                          + HEREDOC_MARKER + ": non posso incorporarlo senza corromperlo.\n");
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
