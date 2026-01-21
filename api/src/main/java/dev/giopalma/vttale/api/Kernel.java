package dev.giopalma.vttale.api;

import dev.giopalma.vttale.api.command.CommandRegistry;
import dev.giopalma.vttale.api.events.EventBus;
import dev.giopalma.vttale.api.module.ModuleRegistry;

/**
 * The central service container for the VTTale system.
 * <p>
 * The Kernel provides access to core services such as the {@link EventBus},
 * {@link CommandRegistry}, and {@link ModuleRegistry}. It acts as the main
 * entry point for modules to interact with the VTT infrastructure.
 * </p>
 *
 * @see VTTale#getKernel()
 */
public interface Kernel {

    /**
     * Returns the global event bus for publishing and subscribing to events.
     *
     * @return the event bus instance
     */
    EventBus getEventBus();

    /**
     * Returns the command registry for registering VTT commands.
     *
     * @return the command registry instance
     */
    CommandRegistry getCommandRegistry();

    /**
     * Returns the module registry for managing module lifecycle.
     *
     * @return the module registry instance
     */
    ModuleRegistry getModuleRegistry();
}
