package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.config.PasswordChangeRequiredFilter;
import info.trizub.clamav.webclient.service.AuditService;
import info.trizub.clamav.webclient.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Lets any signed-in user change their own password, and is the one page a
 * user with a forced change can reach (see PasswordChangeRequiredFilter).
 */
@Controller
public class AccountController {

    private static final String PAGE = "account-password";

    private final UserService users;
    private final AuditService audit;

    public AccountController(UserService users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    @GetMapping(PasswordChangeRequiredFilter.CHANGE_PASSWORD_PATH)
    public String form(Authentication auth, Model model) {
        model.addAttribute("forced", users.mustChangePassword(auth.getName()));
        model.addAttribute("minLength", UserService.MIN_PASSWORD_LENGTH);
        return PAGE;
    }

    @PostMapping(PasswordChangeRequiredFilter.CHANGE_PASSWORD_PATH)
    public String change(@RequestParam String currentPassword,
                         @RequestParam String newPassword,
                         @RequestParam String confirmPassword,
                         Authentication auth, HttpServletRequest req, HttpServletResponse res, Model model) {
        try {
            users.changeOwnPassword(auth.getName(), currentPassword, newPassword, confirmPassword);
        } catch (IllegalArgumentException e) {
            audit.record(auth, req, "USER_CHANGE_PASSWORD", e.getMessage(), "FAILURE", null);
            model.addAttribute("errorMsg", e.getMessage());
            model.addAttribute("forced", users.mustChangePassword(auth.getName()));
            model.addAttribute("minLength", UserService.MIN_PASSWORD_LENGTH);
            return PAGE;
        }
        audit.record(auth, req, "USER_CHANGE_PASSWORD", auth.getName(), "SUCCESS", null);
        // Sign out and back in with the new password: it proves the user knows
        // it, and leaves no session that was opened with the old one.
        new SecurityContextLogoutHandler().logout(req, res, auth);
        return "redirect:/login?changed";
    }
}
