package info.trizub.clamav.webclient.api;

import info.trizub.clamav.webclient.config.AgentAuthenticationFilter;
import info.trizub.clamav.webclient.model.AgentCommand;
import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.AgentCommandService;
import info.trizub.clamav.webclient.service.EndpointService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API usata dall'agent installato sulle macchine. Autenticata dalla chiave
 * dell'endpoint: ogni agent vede solo i comandi del proprio endpoint.
 *
 * E' l'agent a chiamare la console (polling), non il contrario: cosi' sulla
 * macchina non serve aprire porte e funziona anche dietro NAT.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentApiController {

    private final AgentCommandService commands;
    private final EndpointService endpoints;

    public AgentApiController(AgentCommandService commands, EndpointService endpoints) {
        this.commands = commands;
        this.endpoints = endpoints;
    }

    /**
     * Ritira le scansioni in attesa per questo agent. Restituirle significa
     * assegnarle: passano a DISPATCHED e i job a RUNNING, quindi l'agent deve
     * eseguirle e riportarne l'esito.
     */
    @GetMapping("/commands")
    public ResponseEntity<Map<String, Object>> commands(HttpServletRequest request) {
        ClamdEndpoint endpoint = resolveEndpoint(request);
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Chiave agent non valida o endpoint disabilitato"));
        }

        List<Map<String, Object>> payload = new ArrayList<>();
        for (AgentCommand cmd : commands.claimPending(endpoint)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", cmd.getId());
            entry.put("target", cmd.getTarget());
            payload.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpoint", endpoint.getName());
        body.put("commands", payload);
        String mode = desiredOnAccessMode(endpoint);
        if (mode != null) body.put("onAccessMode", mode);
        return ResponseEntity.ok(body);
    }

    /**
     * Modalita' on-access desiderata (detect/prevent), decisa a livello di
     * gruppo: un endpoint senza gruppo non e' gestito da remoto e mantiene
     * quella impostata al momento dell'installazione. null = "non toccare
     * nulla", cosi' un agent senza realtime installato non fa niente con essa.
     */
    private String desiredOnAccessMode(ClamdEndpoint endpoint) {
        if (endpoint.getGroup() == null) return null;
        return endpoint.getGroup().isOnAccessPrevent() ? "prevent" : "detect";
    }

    /**
     * Stessa coda, in formato riga per riga: "<id> <target in base64>".
     * L'agent e' uno script bash e su una macchina minimale jq puo' non esserci,
     * mentre base64 fa parte di coreutils. La codifica evita anche ogni problema
     * di quoting: i path possono contenere spazi e a capo.
     *
     * Quando il gruppo dell'endpoint ha una modalita' on-access impostata, una
     * riga "MODE detect|prevent" precede i comandi: l'id "MODE" non e' mai un
     * id di comando valido (sono numerici), quindi il parser bash la riconosce
     * senza ambiguita' e non tenta di decodificarla come base64.
     */
    @GetMapping(value = "/commands", params = "format=text", produces = "text/plain; charset=utf-8")
    public ResponseEntity<String> commandsText(HttpServletRequest request) {
        ClamdEndpoint endpoint = resolveEndpoint(request);
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("chiave agent non valida\n");
        }
        StringBuilder out = new StringBuilder();
        String mode = desiredOnAccessMode(endpoint);
        if (mode != null) {
            out.append("MODE ").append(mode).append('\n');
        }
        for (AgentCommand cmd : commands.claimPending(endpoint)) {
            String encoded = Base64.getEncoder().encodeToString(
                    cmd.getTarget().getBytes(StandardCharsets.UTF_8));
            out.append(cmd.getId()).append(' ').append(encoded).append('\n');
        }
        return ResponseEntity.ok(out.toString());
    }

    private ClamdEndpoint resolveEndpoint(HttpServletRequest request) {
        Long id = AgentAuthenticationFilter.endpointId(request);
        if (id == null) return null;
        try {
            return endpoints.get(id);
        } catch (Exception e) {
            return null;
        }
    }
}
