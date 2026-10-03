package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.AgentCommand;

/**
 * An agent has answered a file action. Published rather than called directly
 * so that whoever wants to tell someone about it (the Telegram bot) does not
 * have to sit in the dependency chain of the services that run the commands.
 */
public record FileActionCompletedEvent(AgentCommand command, boolean ok, String message, String quarantinePath) {}
