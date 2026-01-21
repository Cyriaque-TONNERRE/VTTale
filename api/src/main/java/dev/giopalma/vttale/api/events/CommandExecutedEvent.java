package dev.giopalma.vttale.api.events;

/**
 * Event published by a platform adapter when a registered command is executed.
 * <p>
 * Modules that register commands via
 * {@link dev.giopalma.vttale.api.command.CommandRegistry}
 * should subscribe to this event to handle command execution.
 * </p>
 */
public class CommandExecutedEvent implements Event {
    private final String commandName;
    private final String[] args;

    /**
     * Creates a new command executed event.
     *
     * @param commandName the name of the executed command (without leading slash)
     * @param args        the command arguments as an array of strings
     */
    public CommandExecutedEvent(String commandName, String[] args) {
        this.commandName = commandName;
        this.args = args;
    }

    /**
     * Returns the name of the executed command.
     *
     * @return the command name
     */
    public String getCommandName() {
        return commandName;
    }

    /**
     * Returns the arguments passed to the command.
     *
     * @return the command arguments
     */
    public String[] getArgs() {
        return args;
    }
}
