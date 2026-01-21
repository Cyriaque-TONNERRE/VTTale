package dev.giopalma.vttale.platform.hytale;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.ParserContext;

import dev.giopalma.vttale.api.VTTale;
import dev.giopalma.vttale.api.events.CommandExecutedEvent;
import dev.giopalma.vttale.api.events.EventContext;
import dev.giopalma.vttale.api.events.RegisterCommandRequest;

import dev.giopalma.vttale.api.command.CommandOptions;

/**
 * Wrapper for VTTale commands in Hytale.
 * Uses the same pattern as LuckPerms for Hytale command handling.
 */
public class Command extends AbstractCommand {

    private final String commandName;
    private final CommandOptions options; // TODO: Use Options to filter commands in Hytale

    public Command(RegisterCommandRequest request) {
        super(request.getCommandName(), request.getCommandDescription());
        this.commandName = request.getCommandName();
        this.options = request.getOptions();
        setAllowsExtraArguments(true);
    }

    /**
     * Main entry point for command execution (like LuckPerms pattern).
     * This is called when the command is invoked.
     */
    @Override
    public CompletableFuture<Void> acceptCall(
            CommandSender sender,
            ParserContext parserContext,
            ParseResult parseResult) {

        // Get the full input string and extract arguments
        String inputString = parserContext.getInputString();
        String[] args = extractArgs(inputString);

        // Create event context with sender's UUID (or "CONSOLE" for console commands)
        String senderId = sender.getUuid().toString().equals("00000000-0000-0000-0000-000000000000")
                ? "CONSOLE"
                : sender.getUuid().toString();
        EventContext eventContext = new EventContext(senderId);

        // Publish the command execution event to the VTT event bus
        CommandExecutedEvent event = new CommandExecutedEvent(commandName, args);
        VTTale.getKernel().getEventBus().publish(event, eventContext);

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Not used - we use acceptCall instead.
     */
    @Override
    protected CompletableFuture<Void> execute(CommandContext ctx) {
        throw new UnsupportedOperationException("Use acceptCall instead");
    }

    /**
     * Extracts command arguments from the input string.
     * The input string format is: "/commandName arg1 arg2 arg3"
     * We need to remove the command name and return the remaining arguments.
     */
    private String[] extractArgs(String inputString) {
        if (inputString == null || inputString.isBlank()) {
            return new String[0];
        }

        // Split by whitespace
        String[] parts = inputString.trim().split("\\s+");

        if (parts.length <= 1) {
            // Only the command name, no arguments
            return new String[0];
        }

        // Skip the first element (command name like "/roll") and return the rest
        return Arrays.copyOfRange(parts, 1, parts.length);
    }

    @Override
    protected boolean canGeneratePermission() {
        return false; // VTTale handles permissions differently
    }
}
