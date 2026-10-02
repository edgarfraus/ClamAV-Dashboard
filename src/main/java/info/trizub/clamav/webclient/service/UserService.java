package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AppUser;
import info.trizub.clamav.webclient.model.Role;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    static final String DEFAULT_ADMIN = "admin";
    private static final String DEFAULT_PASSWORD = "admin";
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final AppUserRepository repo;
    private final PasswordEncoder encoder;

    public UserService(AppUserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Transactional
    public void ensureDefaultAdmin() {
        if (repo.count() == 0) {
            AppUser admin = new AppUser();
            admin.setUsername(DEFAULT_ADMIN);
            admin.setPasswordHash(encoder.encode(DEFAULT_PASSWORD));
            admin.setRoles(Set.of(Role.ADMIN.asAuthority(), Role.OPERATOR.asAuthority(), Role.VIEWER.asAuthority()));
            admin.setMustChangePassword(true);
            repo.save(admin);
        }
    }

    /**
     * Flags an "admin" account that still has the default password, so the
     * forced change also reaches consoles installed before it existed: those
     * created their admin/admin without the flag, and a password nobody changed
     * is exactly the case the flag is for.
     */
    @Transactional
    public void flagDefaultPassword() {
        repo.findByUsername(DEFAULT_ADMIN).ifPresent(u -> {
            if (!u.isMustChangePassword() && encoder.matches(DEFAULT_PASSWORD, u.getPasswordHash())) {
                u.setMustChangePassword(true);
                repo.save(u);
                log.warn("User '{}' still has the default password: a new one will be required at the next sign-in.",
                        DEFAULT_ADMIN);
            }
        });
    }

    public boolean mustChangePassword(String username) {
        return repo.findByUsername(username).map(AppUser::isMustChangePassword).orElse(false);
    }

    /**
     * A user changing their own password. Throws IllegalArgumentException with
     * a message meant to be shown as is.
     */
    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword, String confirmation) {
        AppUser u = repo.findByUsername(username).orElseThrow();
        if (currentPassword == null || !encoder.matches(currentPassword, u.getPasswordHash())) {
            throw new IllegalArgumentException("The current password is not correct.");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("The new password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
        }
        if (!newPassword.equals(confirmation)) {
            throw new IllegalArgumentException("The two new passwords do not match.");
        }
        if (newPassword.equals(currentPassword)) {
            throw new IllegalArgumentException("The new password must be different from the current one.");
        }
        if (newPassword.equalsIgnoreCase(username) || newPassword.equals(DEFAULT_PASSWORD)) {
            throw new IllegalArgumentException("The new password cannot be the username or the default password.");
        }
        u.setPasswordHash(encoder.encode(newPassword));
        u.setMustChangePassword(false);
        repo.save(u);
    }

    @Transactional
    public AppUser createUser(String username, String rawPassword, Set<String> authorities, boolean enabled) {
        if (repo.existsByUsername(username)) {
            throw new IllegalArgumentException("User already exists: " + username);
        }
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setRoles(authorities);
        u.setEnabled(enabled);
        return repo.save(u);
    }

    @Transactional
    public void deleteUser(Long id) {
        repo.deleteById(id);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        AppUser u = repo.findById(id).orElseThrow();
        u.setPasswordHash(encoder.encode(newPassword));
        repo.save(u);
    }

    @Transactional
    public void markLogin(String username) {
        repo.findByUsername(username).ifPresent(u -> {
            u.setLastLoginAt(Instant.now());
            repo.save(u);
        });
    }
}
