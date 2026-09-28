package info.trizub.clamav.webclient.model;

public enum AgentCommandStatus {
    /** Waiting for the agent to claim it on its next poll. */
    PENDING,
    /** Claimed by the agent, scan running on the machine. */
    DISPATCHED,
    /** The agent has sent the result. */
    DONE
}
