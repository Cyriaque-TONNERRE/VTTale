package org.vttale.vttale.api.command;

import org.vttale.vttale.api.events.RegisterCommandRequest;

import java.util.Map;

/**
 * Registry for managing VTT commands.
 * <p>
 * Commands registered here will trigger a {@link RegisterCommandRequest} event
 * that platform adapters can subscribe to for native command registration.
 * </p>
 */
public interface CommandRegistry {

    /**
     * Registers a new command with default options.
     *
     * @param commandName        the name of the command (e.g., "roll")
     * @param commandDescription a brief description of the command
     */
    void registerCommand(String commandName, String commandDescription);

    /**
     * Registers a new command with custom options.
     *
     * @param commandName        the name of the command (e.g., "roll")
     * @param commandDescription a brief description of the command
     * @param options            the command options
     */
    void registerCommand(String commandName, String commandDescription, CommandOptions options);

    /**
     * Returns all currently registered commands.
     * <p>
     * This is useful for platform adapters that need to catch up on commands
     * that were registered before the adapter was initialized.
     * </p>
     *
     * @return an unmodifiable map of command names to their registration requests
     */
    Map<String, RegisterCommandRequest> getRegisteredCommands();
}
