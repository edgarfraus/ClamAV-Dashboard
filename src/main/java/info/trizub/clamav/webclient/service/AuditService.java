package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AuditEvent;
import info.trizub.clamav.webclient.repo.AuditEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditEventRepository repo;

    public AuditService(AuditEventRepository repo) {
        this.repo = repo;
    }

    public void record(Authentication auth, HttpServletRequest req, String action, String details, String outcome, String jobId) {
        String user = auth != null ? auth.getName() : null;
        String ip = req != null ? req.getRemoteAddr() : null;
        String ua = req != null ? req.getHeader("User-Agent") : null;
        // details e' VARCHAR(1024): un messaggio d'errore lungo (uno stack SQL, per
        // esempio) farebbe fallire l'INSERT, e l'audit di un errore trasformerebbe
        // un errore gestito in un 500 che ne nasconde la causa. Meglio troncare.
        repo.save(new AuditEvent(user, action, truncate(details, 1024), outcome, jobId, ip, ua));
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max - 3) + "...";
    }
}
