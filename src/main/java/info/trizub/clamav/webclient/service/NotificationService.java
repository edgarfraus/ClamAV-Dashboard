package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.TelegramAction;
import info.trizub.clamav.webclient.repo.TelegramActionRepository;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final TelegramApi telegram;
    private final TelegramActionRepository telegramActions;

    public NotificationService(SettingsService settings, ObjectMapper mapper, TelegramApi telegram,
                               TelegramActionRepository telegramActions) {
        this.settings = settings;
        this.mapper = mapper;
        this.telegram = telegram;
        this.telegramActions = telegramActions;
    }

    public void notifyIfNeeded(ScanJob job) {
        Map<String, List<String>> findings = job.getFoundVirusesMap();
        Map<String, List<String>> fresh = new LinkedHashMap<>();
        findings.forEach((path, sigs) -> {
            if (!alreadyInQuarantine(path)) fresh.put(path, sigs);
        });
        if (!findings.isEmpty() && fresh.isEmpty()) {
            log.info("Job {}: all {} detection(s) are files already sitting in quarantine - no notification sent.",
                    job.getId(), findings.size());
            return;
        }
        if (fresh.size() < findings.size()) {
            log.info("Job {}: {} of {} detection(s) are files already in quarantine - left out of the notification.",
                    job.getId(), findings.size() - fresh.size(), findings.size());
        }
        notifyWebhook(job, fresh);
        notifyTelegram(job, fresh);
    }

    /**
     * True when the detected file already sits in a quarantine directory.
     *
     * <p>Such a hit is a re-detection of something ClamAV has already acted on:
     * the scan walked over the quarantine itself. It is worth nothing as an
     * alert - the file was neutralised, possibly days ago - and it comes back on
     * every scan from then on, so a single quarantined file turns into one alert
     * per scan forever, which is what buried the alerts that do matter. Worse,
     * an agent quarantining with clamscan's own --move re-quarantines its own
     * quarantine, which is where the endless
     * "eicar_com.zip.001.001.001.001..." names come from.
     *
     * <p>The agents now keep their quarantine out of the scan as well, but the
     * check has to live here too: clamdscan ignores --exclude-dir outright (it
     * prints "WARNING: Ignoring unsupported option" and scans anyway), so on any
     * machine where clamd is up the exclusion never takes effect, and an agent
     * that has not been reinstalled yet keeps reporting these regardless.
     */
    private boolean alreadyInQuarantine(String path) {
        if (path == null || path.isBlank()) return false;
        // Windows reports backslashes; the appended separator keeps contains()
        // matching whole path segments instead of any name that starts the same.
        String p = path.replace('\\', '/').toLowerCase(Locale.ROOT) + "/";
        if (p.contains("/.claimav-quarantine/")) return true;   // Linux/macOS agent
        if (p.contains("/claimav/quarantine/")) return true;    // Windows agent
        String consoleDir = consoleQuarantinePrefix();
        return consoleDir != null && p.startsWith(consoleDir);
    }

    /** The console's own quarantine directory, normalised like the paths above. */
    private String consoleQuarantinePrefix() {
        try {
            String dir = settings.quarantineDir().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (!dir.endsWith("/")) dir = dir + "/";
            // Quarantine pointed at the filesystem root would silence every
            // notification there is: treat that as "not configured" instead of
            // quietly going dark.
            return "/".equals(dir) ? null : dir;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The findings in the same shape the webhook has always carried (a JSON
     * string), re-serialised only when something was actually filtered out, so
     * an existing consumer keeps parsing byte-identical payloads.
     */
    private String findingsJson(ScanJob job, Map<String, List<String>> fresh) {
        if (fresh.size() == job.getFoundVirusesMap().size()) return job.getFoundVirusesJson();
        try {
            return mapper.writeValueAsString(fresh);
        } catch (Exception e) {
            return job.getFoundVirusesJson();
        }
    }

    private void notifyWebhook(ScanJob job, Map<String, List<String>> fresh) {
        if (!settings.webhookEnabled()) return;
        String url = settings.webhookUrl();
        if (url == null || url.isBlank()) return;

        try {
            // Map.of() throws NPE on a null value, and most of these legitimately
            // are null (endpoint, error, quarantinePath...) - a LinkedHashMap tolerates
            // them and keeps the previous key order for whoever reads the raw JSON.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jobId", job.getId());
            payload.put("type", job.getType().name());
            payload.put("target", job.getTarget());
            payload.put("endpoint", job.getEndpoint() != null ? job.getEndpoint().getName() : null);
            payload.put("submittedBy", job.getSubmittedBy());
            payload.put("verdict", job.getVerdict() != null ? job.getVerdict().name() : null);
            payload.put("foundViruses", findingsJson(job, fresh));
            payload.put("error", job.getErrorMessage());
            payload.put("remediation", job.getRemediationStatus() != null ? job.getRemediationStatus().name() : null);
            payload.put("quarantinePath", job.getQuarantinePath());
            RestClient.create().post().uri(url)
                    .header("Content-Type", "application/json")
                    .body(mapper.writeValueAsString(payload))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Webhook notification failed: {}", e.getMessage());
        }
    }

    private void notifyTelegram(ScanJob job, Map<String, List<String>> fresh) {
        if (!settings.telegramEnabled()) return;
        if (job.getVerdict() != ScanVerdict.VIRUS_FOUND && job.getVerdict() != ScanVerdict.ERROR) return;
        String token = settings.telegramBotToken();
        String chatId = settings.telegramChatId();
        if (token == null || token.isBlank() || chatId == null || chatId.isBlank()) return;

        String host = job.getSourceHost() != null ? job.getSourceHost()
                : (job.getEndpoint() != null ? job.getEndpoint().getName() : "unknown");
        String text;
        boolean realtime = job.getType() == ScanJobType.REALTIME;
        if (job.getVerdict() == ScanVerdict.VIRUS_FOUND) {
            StringBuilder findings = new StringBuilder();
            fresh.forEach((path, sigs) ->
                    findings.append(path).append(": ").append(String.join(", ", sigs)).append(" FOUND\n"));
            text = (realtime ? "🚨 *ClamAV Realtime Alert* (on-access) 🚨\n" : "🚨 *ClamAV Alert* 🚨\n") +
                    "Host: `" + escapeMarkdown(host) + "`\n" +
                    "Job: `" + job.getId() + "`\n" +
                    "Target: `" + escapeMarkdown(job.getTarget()) + "`\n" +
                    "Infected files found: *" + fresh.size() + "*\n" +
                    "Remediation: *" + remediationLabel(job) + "*\n\n" +
                    "```\n" + findings + "```";
        } else {
            text = "⚠️ *ClamAV Warning*\n" +
                    "Host: `" + escapeMarkdown(host) + "`\n" +
                    "Job: `" + job.getId() + "`\n" +
                    "Scan error: " + escapeMarkdown(job.getErrorMessage());
        }

        // Buttons: Quarantine for a file still in place, Restore for one in
        // quarantine (see TelegramBotService), plus a link to the alert when
        // the console's public address is known.
        List<TelegramAction> offers = job.getVerdict() == ScanVerdict.VIRUS_FOUND && settings.telegramActionsEnabled()
                ? TelegramBotService.offersFor(job, fresh.keySet(), chatId)
                : List.of();
        String alertUrl = settings.publicUrl().isEmpty() || job.getVerdict() != ScanVerdict.VIRUS_FOUND
                ? null : settings.publicUrl() + "/alerts/" + job.getId();
        Map<String, Object> keyboard = TelegramKeyboard.build(offers, null, alertUrl);
        if (!offers.isEmpty()) text += "\n_Tap a button, then confirm._";
        // Say why an alert goes out without buttons: otherwise "the buttons do
        // not show up" can only be answered by guessing.
        if (job.getVerdict() == ScanVerdict.VIRUS_FOUND && settings.telegramActionsEnabled()) {
            if (!offers.isEmpty()) {
                log.info("Telegram alert for job {} sent with {} button(s)", job.getId(), offers.size());
            } else if (job.getEndpoint() == null) {
                log.info("Telegram alert for job {} has no buttons: it is bound to no endpoint "
                        + "(reported with user credentials instead of an agent key?)", job.getId());
            } else if (!job.getEndpoint().isFileActionsSupported()) {
                log.info("Telegram alert for job {} has no buttons: the agent on {} does not declare "
                        + "file-actions (reinstall it, and check that its poll service is running)",
                        job.getId(), job.getEndpoint().getName());
            } else {
                log.info("Telegram alert for job {} has no buttons: no file in a state to act on "
                        + "(remediation {}, quarantine locations known for {} file(s))", job.getId(),
                        job.getRemediationStatus(), job.getQuarantineMap().size());
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", chatId);
        payload.put("parse_mode", "Markdown");
        payload.put("text", text);
        if (!TelegramKeyboard.isEmpty(keyboard)) payload.put("reply_markup", keyboard);
        JsonNode sent;
        try {
            sent = telegram.call("sendMessage", payload);
        } catch (TelegramApi.TelegramException e) {
            if (!payload.containsKey("reply_markup")) {
                log.warn("Telegram notification failed: {}", e.getMessage());
                return;
            }
            // A bad button (Telegram rejects some URLs, e.g. "localhost") must
            // not cost the alert itself: send it again without buttons.
            log.warn("Telegram refused the alert's buttons ({}); sending it without them.", e.getMessage());
            payload.remove("reply_markup");
            offers = List.of();
            try {
                sent = telegram.call("sendMessage", payload);
            } catch (TelegramApi.TelegramException e2) {
                log.warn("Telegram notification failed: {}", e2.getMessage());
                return;
            }
        }
        if (!offers.isEmpty()) {
            long messageId = sent.path("message_id").asLong();
            offers.forEach(o -> o.setMessageId(messageId));
            telegramActions.saveAll(offers);
        }
    }

    private String remediationLabel(ScanJob job) {
        info.trizub.clamav.webclient.model.RemediationStatus r = job.getRemediationStatus();
        if (r == null) return "not available";
        switch (r) {
            case QUARANTINED: return "quarantined";
            case REMOVED: return "removed";
            case FAILED: return "FAILED - file still present";
            default: return "none (Detection mode)";
        }
    }

    private String escapeMarkdown(String s) {
        return s == null ? "" : s.replace("`", "'");
    }
}
