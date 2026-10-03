package info.trizub.clamav.webclient.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One button offered in a Telegram alert: "quarantine this file" or "restore
 * this file". The button itself carries only this row's random id - never the
 * path or the action - so a modified Telegram client cannot forge an action on
 * another file, and an id is good for one use and a limited time.
 */
@Entity
@Table(name = "telegram_actions")
public class TelegramAction {

    public enum Status { OFFERED, USED, EXPIRED }

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 64)
    private String jobId;

    /** The file a QUARANTINE / RESTORE button is about; empty for ACK. */
    @Column(nullable = false, length = 4096)
    private String filePath;

    // A value added here needs the CHECK constraint dropped on existing
    // databases: see SchemaFixup.migrateTelegramActionType.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TelegramActionType type;

    @Column(length = 64)
    private String chatId;

    /** The Telegram message carrying the button; set once the message is sent. */
    private Long messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OFFERED;

    /** The agent command it turned into, once confirmed. */
    private Long commandId;

    @Column(length = 128)
    private String usedBy;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant expiresAt;

    public TelegramAction() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public TelegramActionType getType() { return type; }
    public void setType(TelegramActionType type) { this.type = type; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Long getCommandId() { return commandId; }
    public void setCommandId(Long commandId) { this.commandId = commandId; }
    public String getUsedBy() { return usedBy; }
    public void setUsedBy(String usedBy) { this.usedBy = usedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
