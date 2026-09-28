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
 * API used by the agent installed on each machine. Authenticated by the
 * endpoint's key: every agent sees only its own endpoint's commands.
 *
 * The agent calls the console (polling), never the other way round: no port
 * has to be opened on the machine, and it works behind NAT too.
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
     * Collects the scans waiting for this agent. Returning them claims them:
     * they move to DISPATCHED and their jobs to RUNNING, so the agent has to
     * run them and report the result.
     */
    @GetMapping("/commands")
    public ResponseEntity<Map<String, Object>> commands(HttpServletRequest request) {
        ClamdEndpoint endpoint = resolveEndpoint(request);
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid agent key or endpoint disabled"));
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
     * Desired on-access mode (detect/prevent), decided per group: an endpoint
     * with no group is not managed remotely and keeps the mode set at install
     * time. null means "change nothing", so an agent without realtime
     * installed does nothing with it.
     */
    private String desiredOnAccessMode(ClamdEndpoint endpoint) {
        if (endpoint.getGroup() == null) return null;
        return endpoint.getGroup().isOnAccessPrevent() ? "prevent" : "detect";
    }

    /**
     * Read-only view of the desired mode, without touching the command queue.
     * It exists for the Windows scheduled scan, which runs as a separate task
     * and must not claim (claimPending changes state) the commands that belong
     * to the separate poll - a GET /commands from there would steal them.
     */
    @GetMapping("/mode")
    public ResponseEntity<Map<String, Object>> mode(HttpServletRequest request) {
        ClamdEndpoint endpoint = resolveEndpoint(request);
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid agent key or endpoint disabled"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        String mode = desiredOnAccessMode(endpoint);
        if (mode != null) body.put("onAccessMode", mode);
        return ResponseEntity.ok(body);
    }

    /**
     * The same queue, one line per command: "<id> <base64 target>".
     * The agent is a bash script, and a minimal machine may have no jq, while
     * base64 is part of coreutils. The encoding also sidesteps every quoting
     * problem: paths can contain spaces and newlines.
     *
     * When the endpoint's group sets an on-access mode, a "MODE detect|prevent"
     * line precedes the commands: "MODE" is never a valid command id (those
     * are numeric), so the bash parser recognises it unambiguously and does
     * not try to decode it as base64.
     */
    @GetMapping(value = "/commands", params = "format=text", produces = "text/plain; charset=utf-8")
    public ResponseEntity<String> commandsText(HttpServletRequest request) {
        ClamdEndpoint endpoint = resolveEndpoint(request);
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("invalid agent key\n");
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
