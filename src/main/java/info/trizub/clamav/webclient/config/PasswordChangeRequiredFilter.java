package info.trizub.clamav.webclient.config;

import info.trizub.clamav.webclient.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Holds a user whose password must change (the admin/admin created on first
 * run) to the change-password page until they pick a new one.
 *
 * The browser is redirected; the API gets a 403 with the reason instead of a
 * redirect to an HTML page a script cannot follow. Without the API half the
 * default credentials would keep working for every curl call, which is the
 * exposure this exists to close. Agents authenticate by key, not as a user,
 * and are never affected.
 *
 * The flag is read from the database on every request rather than cached in
 * the session, so the restriction lifts the moment the password changes.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    public static final String CHANGE_PASSWORD_PATH = "/account/password";

    /** What a held user can still reach: the page itself, logging out, and what the page needs to render. */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            CHANGE_PASSWORD_PATH, "/logout", "/login", "/error",
            "/css/", "/js/", "/images/", "/icons/", "/webfonts/", "/flags/", "/favicon");

    private final UserService users;

    public PasswordChangeRequiredFilter(UserService users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String path = request.getServletPath();

        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
                || isAgent(auth) || isAllowed(path) || !users.mustChangePassword(auth.getName())) {
            chain.doFilter(request, response);
            return;
        }

        if (path.startsWith("/api/") || path.startsWith("/agent/") || path.startsWith("/h2")
                || path.startsWith("/actuator")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"Password change required: sign in to the web console "
                    + "and choose a new password for this account.\"}");
            return;
        }
        response.sendRedirect(request.getContextPath() + CHANGE_PASSWORD_PATH);
    }

    private static boolean isAgent(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_AGENT".equals(a.getAuthority()));
    }

    private static boolean isAllowed(String path) {
        return ALLOWED_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
