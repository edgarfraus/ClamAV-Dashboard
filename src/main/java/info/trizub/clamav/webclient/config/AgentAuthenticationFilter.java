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
 * Authenticates an agent by the endpoint's key, instead of a per-machine
 * OPERATOR user. The key grants only the AGENT role, which SecurityConfig
 * allows on /agent/** and POST /api/scan/report and nothing else: a stolen key
 * can neither read other hosts' jobs nor launch scans, both of which an
 * OPERATOR user could.
 *
 * The key is accepted from three places, in order of preference:
 *   1. header  X-Agent-Key: cav_...
 *   2. header  Authorization: Bearer cav_...
 *   3. query   ?key=cav_...   (for the "curl ... | sudo bash" enrollment, where
 *              no header can be passed; it ends up in the reverse proxy's
 *              logs, so use it only to download the installer)
 */
public class AgentAuthenticationFilter extends OncePerRequestFilter {

    public static final String ENDPOINT_ID_ATTRIBUTE = "agentEndpointId";

    private final EndpointService endpoints;

    public AgentAuthenticationFilter(EndpointService endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * By default OncePerRequestFilter does not run again on the dispatch to
     * /error: without this, an error in an agent request loses its
     * authentication on the way and comes back as a 401 with
     * "WWW-Authenticate: Basic" instead of the real error (500/400). Masking the
     * cause like that makes diagnosis impossible.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // A request that is already authenticated (web session, Basic auth) is left alone.
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
                    // On every request the agent attaches the clamd version, the
                    // on-access mode actually in force (if it handles realtime), and
                    // its operating system: the console learns them without
                    // connecting to the machine, and no extra HTTP round trip is
                    // needed just for the heartbeat.
                    endpoints.touchAgentSeen(ep.getId(), request.getHeader("X-Agent-Clamav"),
                            request.getHeader("X-Agent-OnAccess-Mode"), request.getHeader("X-Agent-OS"));
                }
            }
        }
        chain.doFilter(request, response);
    }

    /** Id of the endpoint authenticated by the key, or null when the request does not come from an agent. */
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
