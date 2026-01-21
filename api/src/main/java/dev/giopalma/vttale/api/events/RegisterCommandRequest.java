package dev.giopalma.vttale.api.events;

import dev.giopalma.vttale.api.command.CommandOptions;

/**
 * Event published when a module requests registration of a new command.
 * <p>
 * Platform adapters should subscribe to this event to register commands
 * with the underlying game engine.
 * </p>
 */
public class RegisterCommandRequest implements Event {
    private final String commandName;
    private final String commandDescription;
    private final CommandOptions options;

    /**
     * Creates a new command registration request with default options.
     *
     * @param commandName        the name of the command to register (without
     *                           leading slash)
     * @param commandDescription a brief description of the command's purpose
     */
    public RegisterCommandRequest(String commandName, String commandDescription) {
        this(commandName, commandDescription, CommandOptions.defaults());
    }

    /**
     * Creates a new command registration request.
     *
     * @param commandName        the name of the command to register (without
     *                           leading slash)
     * @param commandDescription a brief description of the command's purpose
     * @param options            the command options
     */
    public RegisterCommandRequest(String commandName, String commandDescription, CommandOptions options) {
        this.commandName = commandName;
        this.commandDescription = commandDescription;
        this.options = options;
    }

    /**
     * Returns the name of the command to register.
     *
     * @return the command name
     */
    public String getCommandName() {
        return commandName;
    }

    /**
     * Returns the description of the command.
     *
     * @return the command description
     */
    public String getCommandDescription() {
        return commandDescription;
    }

    /**
     * Returns the command options.
     *
     * @return the options
     */
    public CommandOptions getOptions() {
        return options;
    }
}
