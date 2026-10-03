package info.trizub.clamav.webclient.model;

/**
 * What a Telegram button does. QUARANTINE and RESTORE turn into agent
 * commands (a plain restore only - allow-listing stays in the console); ACK
 * acknowledges the alert on the console and touches no machine.
 */
public enum TelegramActionType {
    QUARANTINE,
    RESTORE,
    ACK;

    /** The agent command a file button turns into; null for ACK. */
    public AgentCommandType agentCommandType() {
        return switch (this) {
            case QUARANTINE -> AgentCommandType.QUARANTINE;
            case RESTORE -> AgentCommandType.RESTORE;
            case ACK -> null;
        };
    }
}
