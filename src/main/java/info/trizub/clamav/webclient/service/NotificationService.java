package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanJobType;
import info.trizub.clamav.webclient.model.ScanVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SettingsService settings;
    private final ObjectMapper mapper;

    public NotificationService(SettingsService settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public void notifyIfNeeded(ScanJob job) {
        notifyWebhook(job);
        notifyTelegram(job);
    }

    private void notifyWebhook(ScanJob job) {
        if (!settings.webhookEnabled()) return;
        String url = settings.webhookUrl();
        if (url == null || url.isBlank()) return;

        try {
            Map<String, Object> payload = Map.of(
                    "jobId", job.getId(),
                    "type", job.getType().name(),
                    "target", job.getTarget(),
                    "endpoint", job.getEndpoint() != null ? job.getEndpoint().getName() : null,
                    "submittedBy", job.getSubmittedBy(),
                    "verdict", job.getVerdict() != null ? job.getVerdict().name() : null,
                    "foundViruses", job.getFoundVirusesJson(),
                    "error", job.getErrorMessage(),
                    "quarantinePath", job.getQuarantinePath()
            );
            RestClient.create().post().uri(url)
                    .header("Content-Type", "application/json")
                    .body(mapper.writeValueAsString(payload))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Webhook notification failed: {}", e.getMessage());
        }
    }

    private void notifyTelegram(ScanJob job) {
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
            job.getFoundVirusesMap().forEach((path, sigs) ->
                    findings.append(path).append(": ").append(String.join(", ", sigs)).append(" FOUND\n"));
            text = (realtime ? "🚨 *ClamAV Realtime Alert* (on-access) 🚨\n" : "🚨 *ClamAV Alert* 🚨\n") +
                    "Host: `" + escapeMarkdown(host) + "`\n" +
                    "Job: `" + job.getId() + "`\n" +
                    "Target: `" + escapeMarkdown(job.getTarget()) + "`\n" +
                    "File infetti trovati: *" + job.getInfectedFileCount() + "*\n\n" +
                    "```\n" + findings + "```";
        } else {
            text = "⚠️ *ClamAV Warning*\n" +
                    "Host: `" + escapeMarkdown(host) + "`\n" +
                    "Job: `" + job.getId() + "`\n" +
                    "Errore durante lo scan: " + escapeMarkdown(job.getErrorMessage());
        }

        try {
            Map<String, Object> payload = Map.of(
                    "chat_id", chatId,
                    "parse_mode", "Markdown",
                    "text", text
            );
            RestClient.create().post()
                    .uri("https://api.telegram.org/bot" + token + "/sendMessage")
                    .header("Content-Type", "application/json")
                    .body(mapper.writeValueAsString(payload))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Telegram notification failed: {}", e.getMessage());
        }
    }

    private String escapeMarkdown(String s) {
        return s == null ? "" : s.replace("`", "'");
    }
}
