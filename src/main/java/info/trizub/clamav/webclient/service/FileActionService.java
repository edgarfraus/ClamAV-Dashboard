package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AgentCommand;
import info.trizub.clamav.webclient.model.AgentCommandStatus;
import info.trizub.clamav.webclient.model.AgentCommandType;
import info.trizub.clamav.webclient.model.ScanJob;
import info.trizub.clamav.webclient.model.ScanVerdict;
import org.springframework.stereotype.Service;

/**
 * Asking an agent to quarantine or restore one file of an alert. Shared by the
 * alert page and the Telegram buttons, so both go through exactly the same
 * checks: the console decides what may be asked, the agent then re-checks on
 * the machine that it is safe.
 */
@Service
public class FileActionService {

    private final AgentCommandService agentCommands;

    public FileActionService(AgentCommandService agentCommands) {
        this.agentCommands = agentCommands;
    }

    /** Why this action cannot be requested now, or null when it can. */
    public String problem(ScanJob job, String path, AgentCommandType type) {
        if (job == null) return "Alert not found.";
        if (job.getVerdict() != ScanVerdict.VIRUS_FOUND) return "This alert has no detected files.";
        if (job.getEndpoint() == null) return "The endpoint of this alert no longer exists.";
        if (!job.getEndpoint().isFileActionsSupported()) {
            return "The agent on " + job.getEndpoint().getName()
                    + " does not support file actions yet: reinstall it from Admin > Endpoints.";
        }
        if (path == null || path.isBlank()) return "No file given.";
        for (AgentCommand c : agentCommands.fileActionsForJob(job.getId())) {
            if (c.getStatus() != AgentCommandStatus.DONE && path.equals(c.getFilePath())) {
                return "An action on this file is already waiting for the agent.";
            }
        }
        if (type == AgentCommandType.QUARANTINE) {
            if (job.getQuarantineMap().containsKey(path)) return "This file is already in quarantine.";
            if (!job.getFoundVirusesMap().containsKey(path)) return "This file is not part of the alert.";
        } else if (type == AgentCommandType.RESTORE || type == AgentCommandType.RESTORE_ALLOW) {
            if (!job.getQuarantineMap().containsKey(path)) {
                return "The console does not know where this file is in quarantine, so it cannot restore it.";
            }
        } else {
            return "Not a file action: " + type;
        }
        return null;
    }

    /** Queues the action; throws IllegalStateException with the reason when it cannot be. */
    public AgentCommand request(ScanJob job, String path, AgentCommandType type, String username) {
        String problem = problem(job, path, type);
        if (problem != null) throw new IllegalStateException(problem);
        String target = type == AgentCommandType.QUARANTINE
                ? path
                : job.getQuarantineMap().get(path) + "\n" + path;
        return agentCommands.enqueueFileAction(job.getEndpoint(), job.getId(), type, target, username);
    }
}
