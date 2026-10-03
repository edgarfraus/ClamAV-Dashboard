package info.trizub.clamav.webclient.service;

import com.fasterxml.jackson.databind.JsonNode;
import info.trizub.clamav.webclient.model.AgentCommand;
import info.trizub.clamav.webclient.model.AgentCommandType;
import info.trizub.clamav.webclient.model.AppUser;
import info.trizub.clamav.webclient.model.Role;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.TelegramAction;
import info.trizub.clamav.webclient.model.TelegramActionType;
import info.trizub.clamav.webclient.repo.AppUserRepository;
import info.trizub.clamav.webclient.repo.TelegramActionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The buttons under Telegram alerts: quarantine a file still in place, or
 * restore one from quarantine (a plain restore - allow-listing a file as a
 * false positive is deliberately left to the console, where the whole alert
 * is in view).
 *
 * <p>Clicks are fetched by long-polling getUpdates on a thread of its own:
 * outbound only, so the console needs no public address (a webhook would),
 * and a 25-second poll never holds up the scheduler the other jobs run on.
 * Only callback queries are requested, so the bot ignores ordinary messages
 * in the group and the other bots there are unaffected. A bot token can only
 * have one such reader: if another program polls the same bot, or a webhook
 * is set, Telegram answers 409 and this says so in the log.
 *
 * <p>A group lets anyone press a button, so a press counts only from a
 * Telegram user mapped to a console OPERATOR/ADMIN in Settings; anyone else
 * is told their Telegram id, to make adding them easy. The action is then
 * checked and queued by FileActionService exactly as from the alert page,
 * audited as that console user "via Telegram", and the agent re-checks it on
 * the machine.
 */
@Service
public class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);

    /** How long a button stays usable; then the alert page is the way. */
    public static final Duration OFFER_TTL = Duration.ofHours(24);
    /** At most this many files get buttons in one alert; the rest via the console. */
    public static final int MAX_OFFERS = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SettingsService settings;
    private final TelegramApi api;
    private final TelegramActionRepository actions;
    private final ScanJobService jobs;
    private final FileActionService fileActions;
    private final AppUserRepository users;
    private final AuditService audit;
    private final TransactionTemplate newTransaction;

    private volatile boolean running = true;
    private Thread poller;
    private long offset = 0;
    private String offsetToken = "";
    private Instant lastConflictLog = Instant.EPOCH;
    private String lastState = "";

    public TelegramBotService(SettingsService settings, TelegramApi api, TelegramActionRepository actions,
                              ScanJobService jobs, FileActionService fileActions, AppUserRepository users,
                              AuditService audit, PlatformTransactionManager txManager) {
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.settings = settings;
        this.api = api;
        this.actions = actions;
        this.jobs = jobs;
        this.fileActions = fileActions;
        this.users = users;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ offers

    /**
     * The buttons an alert should carry: Restore for a file in quarantine,
     * Quarantine for one still in place. Not saved yet - the caller saves them
     * once the message is sent and its id known.
     */
    public static List<TelegramAction> offersFor(ScanJob job, Iterable<String> paths, String chatId) {
        List<TelegramAction> out = new ArrayList<>();
        if (job.getEndpoint() == null || !job.getEndpoint().isFileActionsSupported()) return out;
        Map<String, String> quarantined = job.getQuarantineMap();
        boolean leftInPlace = job.getRemediationStatus() == null
                || job.getRemediationStatus() == info.trizub.clamav.webclient.model.RemediationStatus.NOT_ATTEMPTED
                || job.getRemediationStatus() == info.trizub.clamav.webclient.model.RemediationStatus.FAILED;
        for (String path : paths) {
            if (out.size() >= MAX_OFFERS) break;
            TelegramActionType type;
            if (quarantined.containsKey(path)) type = TelegramActionType.RESTORE;
            else if (leftInPlace) type = TelegramActionType.QUARANTINE;
            else continue; // removed, or quarantined by an agent that did not say where
            out.add(newOffer(job.getId(), path, type, chatId));
        }
        return out;
    }

    /** The "Acknowledge alert" button: no agent involved, so offered for any endpoint. */
    public static TelegramAction ackOffer(ScanJob job, String chatId) {
        return newOffer(job.getId(), "", TelegramActionType.ACK, chatId);
    }

    private static TelegramAction newOffer(String jobId, String path, TelegramActionType type, String chatId) {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        TelegramAction a = new TelegramAction();
        a.setId(Base64.getUrlEncoder().withoutPadding().encodeToString(b));
        a.setJobId(jobId);
        a.setFilePath(path);
        a.setType(type);
        a.setChatId(chatId);
        a.setCreatedAt(Instant.now());
        a.setExpiresAt(Instant.now().plus(OFFER_TTL));
        return a;
    }

    public String alertUrl(String jobId) {
        String base = settings.publicUrl();
        return base.isEmpty() ? null : base + "/alerts/" + jobId;
    }

    // ----------------------------------------------------------------- polling

    @PostConstruct
    void start() {
        poller = new Thread(this::pollLoop, "telegram-bot");
        poller.setDaemon(true);
        poller.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (poller != null) poller.interrupt();
    }

    /** Logs the buttons' state once each time it changes, so the log says whether they can work. */
    private void reportState(String state) {
        if (!state.equals(lastState)) {
            lastState = state;
            log.info("Telegram buttons: {}", state);
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                if (!settings.telegramEnabled()) {
                    reportState("off (Telegram alerts are disabled in Settings)");
                    sleep(15_000);
                    continue;
                }
                if (!settings.telegramActionsEnabled()) {
                    reportState("off (enable 'Buttons under alerts' in Settings > Telegram)");
                    sleep(15_000);
                    continue;
                }
                if (!api.configured()) {
                    reportState("off (bot token or chat id missing in Settings > Telegram)");
                    sleep(15_000);
                    continue;
                }
                if (settings.telegramActionUsers().isEmpty()) {
                    reportState("on, but nobody may press them yet: fill 'Who may press them' in Settings > Telegram");
                } else {
                    reportState("on, listening for presses from " + settings.telegramActionUsers().size()
                            + " authorised Telegram user(s)");
                }
                // A different bot means different updates: start from scratch.
                if (!settings.telegramBotToken().equals(offsetToken)) {
                    offsetToken = settings.telegramBotToken();
                    offset = 0;
                }
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("offset", offset);
                req.put("timeout", 25);
                req.put("allowed_updates", List.of("callback_query"));
                JsonNode updates = api.call("getUpdates", req);
                for (JsonNode u : updates) {
                    offset = Math.max(offset, u.path("update_id").asLong() + 1);
                    if (u.has("callback_query")) {
                        try {
                            handleCallback(u.path("callback_query"));
                        } catch (Exception e) {
                            log.warn("Telegram button press not handled: {}", e.getMessage());
                        }
                    }
                }
            } catch (TelegramApi.TelegramException e) {
                if (e.getCode() == 409) {
                    if (Instant.now().isAfter(lastConflictLog.plus(Duration.ofMinutes(10)))) {
                        lastConflictLog = Instant.now();
                        log.warn("Telegram buttons inactive: another program is reading this bot's updates, "
                                + "or a webhook is set on it ({}). One bot token can have only one reader.", e.getMessage());
                    }
                    sleep(30_000);
                } else {
                    log.warn("Telegram polling failed: {}", e.getMessage());
                    sleep(10_000);
                }
            } catch (Exception e) {
                log.warn("Telegram polling failed: {}", e.getMessage());
                sleep(10_000);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    // --------------------------------------------------------------- callbacks

    void handleCallback(JsonNode cb) throws TelegramApi.TelegramException {
        String callbackId = cb.path("id").asText();
        String data = cb.path("data").asText("");
        long fromId = cb.path("from").path("id").asLong();
        String fromName = displayName(cb.path("from"));
        String chatId = cb.path("message").path("chat").path("id").asText();
        long messageId = cb.path("message").path("message_id").asLong();

        int colon = data.indexOf(':');
        if (colon != 1) {
            answer(callbackId, "Unknown button.", false);
            return;
        }
        char verb = data.charAt(0);
        TelegramAction action = actions.findById(data.substring(2)).orElse(null);
        if (action == null || !chatId.equals(action.getChatId())) {
            answer(callbackId, "This button is no longer valid. Use the alert page in the console.", true);
            return;
        }
        if (action.getStatus() != TelegramAction.Status.OFFERED || Instant.now().isAfter(action.getExpiresAt())) {
            answer(callbackId, "This button has expired or was already used. Use the alert page in the console.", true);
            return;
        }

        String consoleUser = authorisedUser(fromId);
        if (consoleUser == null) {
            answer(callbackId, "You are not allowed to act on alerts. Your Telegram ID is " + fromId
                    + ": an admin can add it in Settings > Telegram.", true);
            log.info("Telegram button pressed by unauthorised user {} ({})", fromId, fromName);
            return;
        }

        List<TelegramAction> onMessage = actions.findByChatIdAndMessageIdOrderByCreatedAtAsc(chatId, messageId);
        String url = alertUrl(action.getJobId());
        if (action.getType() == TelegramActionType.ACK) {
            acknowledge(callbackId, action, consoleUser, fromId, fromName, url);
            return;
        }
        switch (verb) {
            case 'a' -> {
                editKeyboard(chatId, messageId, TelegramKeyboard.build(onMessage, action.getId(), url));
                answer(callbackId, action.getType() == TelegramActionType.QUARANTINE
                        ? "Confirm to move this file into quarantine on the machine."
                        : "Confirm to put this file back. On a Prevention machine the next scan quarantines it again.",
                        false);
            }
            case 'n' -> {
                editKeyboard(chatId, messageId, TelegramKeyboard.build(onMessage, null, url));
                answer(callbackId, "Cancelled.", false);
            }
            case 'y' -> confirm(callbackId, action, consoleUser, fromId, fromName, url);
            default -> answer(callbackId, "Unknown button.", false);
        }
    }

    private void confirm(String callbackId, TelegramAction action,
                         String consoleUser, long fromId, String fromName, String url)
            throws TelegramApi.TelegramException {
        ScanJob job = jobs.getOrNull(action.getJobId());
        String auditAction = "ALERT_FILE_" + action.getType().name();
        String details = "jobId=" + action.getJobId() + " path=" + action.getFilePath()
                + " via Telegram (" + fromName + ", id " + fromId + ")";
        var auth = new UsernamePasswordAuthenticationToken(consoleUser, null, List.of());
        AgentCommand cmd;
        try {
            cmd = fileActions.request(job, action.getFilePath(), action.getType().agentCommandType(), consoleUser);
        } catch (IllegalStateException e) {
            audit.record(auth, null, auditAction, details + ": " + e.getMessage(), "FAILURE", action.getJobId());
            action.setStatus(TelegramAction.Status.EXPIRED);
            actions.save(action);
            refreshKeyboard(action, url);
            answer(callbackId, "Not possible: " + e.getMessage(), true);
            return;
        }
        audit.record(auth, null, auditAction, details, "SUCCESS", action.getJobId());
        action.setStatus(TelegramAction.Status.USED);
        action.setCommandId(cmd.getId());
        action.setUsedBy(consoleUser + " via Telegram (" + fromName + ")");
        actions.save(action);
        refreshKeyboard(action, url);
        answer(callbackId, "Requested. The agent carries it out within 5 minutes.", false);
        String endpoint = job != null && job.getEndpoint() != null ? job.getEndpoint().getName() : "?";
        send(action.getChatId(), action.getMessageId(),
                "⏳ " + (action.getType() == TelegramActionType.QUARANTINE ? "Quarantine" : "Restore")
                        + " of " + code(TelegramKeyboard.shortName(action.getFilePath()))
                        + " on " + code(endpoint) + " requested by " + code(fromName)
                        + ". Waiting for the agent.", null);
    }

    /**
     * Acknowledges the alert, exactly like the button on the alert page. One
     * tap, no confirmation: it moves nothing on any machine. An alert already
     * acknowledged (from the console, or by someone quicker) says by whom.
     */
    private void acknowledge(String callbackId, TelegramAction action,
                             String consoleUser, long fromId, String fromName, String url)
            throws TelegramApi.TelegramException {
        ScanJob job = jobs.getOrNull(action.getJobId());
        if (job == null || job.isAcknowledged()) {
            action.setStatus(TelegramAction.Status.EXPIRED);
            actions.save(action);
            refreshKeyboard(action, url);
            answer(callbackId, job == null ? "This alert no longer exists."
                    : "Already acknowledged by " + job.getAcknowledgedBy() + ".", true);
            return;
        }
        jobs.acknowledge(job.getId(), consoleUser);
        audit.record(new UsernamePasswordAuthenticationToken(consoleUser, null, List.of()), null, "ALERT_ACK",
                "jobId=" + job.getId() + " via Telegram (" + fromName + ", id " + fromId + ")", "SUCCESS", job.getId());
        action.setStatus(TelegramAction.Status.USED);
        action.setUsedBy(consoleUser + " via Telegram (" + fromName + ")");
        actions.save(action);
        refreshKeyboard(action, url);
        answer(callbackId, "Alert acknowledged.", false);
        send(action.getChatId(), action.getMessageId(), "✔️ Alert acknowledged by " + code(fromName) + ".", null);
    }

    /** The console user a Telegram user acts as, or null if they may not act. */
    private String authorisedUser(long telegramId) {
        String username = settings.telegramActionUsers().get(telegramId);
        if (username == null || username.isBlank()) return null;
        AppUser u = users.findByUsername(username).orElse(null);
        if (u == null || !u.isEnabled() || u.isMustChangePassword()) return null;
        boolean operator = u.getRoles().contains(Role.OPERATOR.asAuthority())
                || u.getRoles().contains(Role.ADMIN.asAuthority());
        return operator ? u.getUsername() : null;
    }

    // ------------------------------------------------------- results from agents

    /**
     * Tells the group how an action started from Telegram ended, as a reply to
     * the alert. After a quarantine, the reply offers the way back.
     * After commit, so a Telegram hiccup can never roll back the result. That
     * also means the original transaction is over: a plain repository save
     * here would join a transaction that has already committed and never be
     * written, leaving a Restore button that answers "no longer valid". The
     * save therefore runs in an explicit new transaction.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onFileActionCompleted(FileActionCompletedEvent e) {
        AgentCommand cmd = e.command();
        TelegramAction origin = actions.findFirstByCommandId(cmd.getId()).orElse(null);
        if (origin == null || !api.configured()) return;
        try {
            boolean quarantine = cmd.getType() == AgentCommandType.QUARANTINE;
            String name = code(TelegramKeyboard.shortName(origin.getFilePath()));
            String text = (e.ok() ? "✅ " : "❌ ")
                    + (quarantine ? (e.ok() ? "Quarantined " : "Not quarantined: ") : (e.ok() ? "Restored " : "Not restored: "))
                    + name
                    + (e.message() != null && !e.message().isBlank() ? "\n" + code(e.message()) : "");
            List<TelegramAction> offers = new ArrayList<>();
            if (e.ok() && quarantine && settings.telegramActionsEnabled()) {
                offers.add(newOffer(origin.getJobId(), origin.getFilePath(), TelegramActionType.RESTORE, origin.getChatId()));
            }
            Long sent = send(origin.getChatId(), origin.getMessageId(), text,
                    offers.isEmpty() ? null : TelegramKeyboard.build(offers, null, alertUrl(origin.getJobId())));
            if (sent != null && !offers.isEmpty()) {
                offers.forEach(o -> o.setMessageId(sent));
                newTransaction.executeWithoutResult(status -> actions.saveAll(offers));
            }
        } catch (Exception ex) {
            log.warn("Could not tell Telegram the outcome of file action {}: {}", cmd.getId(), ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private Long send(String chatId, Long replyTo, String text, Map<String, Object> keyboard)
            throws TelegramApi.TelegramException {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("chat_id", chatId);
        req.put("parse_mode", "Markdown");
        req.put("text", text);
        if (replyTo != null) {
            req.put("reply_to_message_id", replyTo);
            req.put("allow_sending_without_reply", true);
        }
        if (keyboard != null && !TelegramKeyboard.isEmpty(keyboard)) req.put("reply_markup", keyboard);
        JsonNode res = api.call("sendMessage", req);
        return res.path("message_id").isMissingNode() ? null : res.path("message_id").asLong();
    }

    /** Rebuilds a message's keyboard from what is stored now, after a button changed state. */
    private void refreshKeyboard(TelegramAction changed, String url) {
        List<TelegramAction> current =
                actions.findByChatIdAndMessageIdOrderByCreatedAtAsc(changed.getChatId(), changed.getMessageId());
        editKeyboard(changed.getChatId(), changed.getMessageId(), TelegramKeyboard.build(current, null, url));
    }

    private void editKeyboard(String chatId, Long messageId, Map<String, Object> keyboard) {
        if (messageId == null) return;
        try {
            api.call("editMessageReplyMarkup", Map.of("chat_id", chatId, "message_id", messageId, "reply_markup", keyboard));
        } catch (TelegramApi.TelegramException e) {
            // "message is not modified" and friends: the keyboard is what it should be already.
            log.debug("Keyboard not updated: {}", e.getMessage());
        }
    }

    private void answer(String callbackId, String text, boolean alert) throws TelegramApi.TelegramException {
        api.call("answerCallbackQuery", Map.of("callback_query_id", callbackId, "text", text, "show_alert", alert));
    }

    private static String displayName(JsonNode from) {
        String u = from.path("username").asText("");
        if (!u.isBlank()) return "@" + u;
        String n = (from.path("first_name").asText("") + " " + from.path("last_name").asText("")).trim();
        return n.isBlank() ? String.valueOf(from.path("id").asLong()) : n;
    }

    /** Markdown code span: the only safe way to show paths and messages full of _ and *. */
    private static String code(String s) {
        return "`" + (s == null ? "" : s.replace("`", "'")) + "`";
    }

    /** Buttons are good for a day; their rows only matter for the history afterwards. */
    @Scheduled(cron = "0 17 3 * * *")
    public void purgeOldOffers() {
        long n = actions.deleteByCreatedAtBefore(Instant.now().minus(Duration.ofDays(30)));
        if (n > 0) log.info("Removed {} Telegram button record(s) older than 30 days", n);
    }
}
