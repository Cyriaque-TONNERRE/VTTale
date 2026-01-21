package dev.giopalma.vttale.kernel.command;

import dev.giopalma.vttale.api.command.CommandOptions;
import dev.giopalma.vttale.api.command.CommandRegistry;
import dev.giopalma.vttale.api.events.EventBus;
import dev.giopalma.vttale.api.events.EventContext;
import dev.giopalma.vttale.api.events.RegisterCommandRequest;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple implementation of CommandRegistry that stores commands and publishes
 * registration events.
 */
public class SimpleCommandRegistry implements CommandRegistry {
    private final EventBus eventBus;
    private final Map<String, RegisterCommandRequest> registeredCommands = new ConcurrentHashMap<>();

    public SimpleCommandRegistry(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public void registerCommand(String commandName, String commandDescription) {
        registerCommand(commandName, commandDescription, CommandOptions.defaults());
    }

    @Override
    public void registerCommand(String commandName, String commandDescription, CommandOptions options) {
        RegisterCommandRequest request = new RegisterCommandRequest(commandName, commandDescription, options);
        registeredCommands.put(commandName, request);
        eventBus.publish(request, new EventContext("KERNEL"));
    }

    @Override
    public Map<String, RegisterCommandRequest> getRegisteredCommands() {
        return Collections.unmodifiableMap(registeredCommands);
    }
}
