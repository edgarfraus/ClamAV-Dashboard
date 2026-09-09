package info.trizub.clamav.webclient.config;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.EndpointService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Autentica un agent tramite la chiave dell'endpoint, al posto di un utente
 * OPERATOR per macchina. La chiave concede il solo ruolo AGENT, che in
 * SecurityConfig puo' fare unicamente /agent/** e POST /api/scan/report: se
 * viene rubata non permette di leggere i job degli altri host ne' di lanciare
 * scansioni, cosa che un utente OPERATOR permetterebbe.
 *
 * La chiave e' accettata da tre posti, in ordine di preferenza:
 *   1. header  X-Agent-Key: cav_...
 *   2. header  Authorization: Bearer cav_...
 *   3. query   ?key=cav_...   (serve per l'enrollment "curl ... | sudo bash",
 *              dove non si possono passare header; finisce nei log del reverse
 *              proxy, quindi va usata solo per il download dell'installer)
 */
public class AgentAuthenticationFilter extends OncePerRequestFilter {

    public static final String ENDPOINT_ID_ATTRIBUTE = "agentEndpointId";

    private final EndpointService endpoints;

    public AgentAuthenticationFilter(EndpointService endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * Di default OncePerRequestFilter non rigira sul dispatch verso /error: senza
     * questo, un errore in una richiesta dell'agent perde l'autenticazione lungo
     * la strada e torna un 401 con "WWW-Authenticate: Basic" al posto dell'errore
     * vero (500/400). Mascherare cosi' le cause rende la diagnosi impossibile.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // Se la richiesta e' gia' autenticata (sessione web, Basic auth) non tocchiamo nulla.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String key = extractKey(request);
            if (key != null) {
                Optional<ClamdEndpoint> found = endpoints.findByAgentKey(key);
                if (found.isPresent() && found.get().isEnabled()) {
                    ClamdEndpoint ep = found.get();
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            "agent:" + ep.getName(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    request.setAttribute(ENDPOINT_ID_ATTRIBUTE, ep.getId());
                    // L'agent allega la versione di clamd e, se gestisce il realtime,
                    // la modalita' on-access che ha davvero in vigore a ogni richiesta:
                    // cosi' la console le conosce senza doversi collegare alla macchina,
                    // e non serve un giro HTTP in piu' solo per il heartbeat.
                    endpoints.touchAgentSeen(ep.getId(), request.getHeader("X-Agent-Clamav"),
                            request.getHeader("X-Agent-OnAccess-Mode"));
                }
            }
        }
        chain.doFilter(request, response);
    }

    /** Id dell'endpoint autenticato dalla chiave, o null se la richiesta non viene da un agent. */
    public static Long endpointId(HttpServletRequest request) {
        Object attribute = request.getAttribute(ENDPOINT_ID_ATTRIBUTE);
        return (attribute instanceof Long) ? (Long) attribute : null;
    }

    private String extractKey(HttpServletRequest request) {
        String header = request.getHeader("X-Agent-Key");
        if (header != null && !header.isBlank()) return header.trim();

        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).trim();
            if (!token.isEmpty()) return token;
        }

        String param = request.getParameter("key");
        if (param != null && !param.isBlank()) return param.trim();

        return null;
    }
}
