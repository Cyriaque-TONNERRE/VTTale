package org.vttale.vttale.api;

import org.vttale.vttale.api.command.CommandRegistry;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.module.ModuleRegistry;
import org.vttale.vttale.api.token.TokenRegistry;
import org.vttale.vttale.api.token.behavior.BehaviorDispatcher;

/**
 * The central service container for the VTTale system.
 * <p>
 * The Kernel provides access to core services such as :<br />
 * - {@link EventBus} for publish/subscribe event communication,<br />
 * - {@link CommandRegistry} for registering VTT commands,<br />
 * - {@link ModuleRegistry} for managing module lifecycle,<br />
 * - {@link TokenRegistry} for managing tokens,<br />
 * - {@link BehaviorDispatcher} for dispatching events to token behaviors.
 * <p>
 * It acts as the main entry point for modules to interact with the VTT infrastructure.
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

    /**
     * Returns the token registry for managing tokens.
     *
     * @return the token registry instance
     */
    TokenRegistry getTokenRegistry();

    /**
     * Returns the behavior dispatcher for sending events to token behaviors.
     * <p>
     * Use this to trigger behavior reactions when game events occur.
     *
     * @return the behavior dispatcher
     */
    BehaviorDispatcher getBehaviorDispatcher();
}
