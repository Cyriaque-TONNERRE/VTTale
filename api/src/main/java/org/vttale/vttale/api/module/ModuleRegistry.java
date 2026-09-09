package org.vttale.vttale.api.module;

/**
 * Registry for managing module lifecycle.
 * <p>
 * Modules registered here have their {@link Module#onEnable} called
 * immediately. A module whose {@code onEnable} throws is skipped and logged —
 * one broken module never prevents the server from starting.
 */
public interface ModuleRegistry {

    /**
     * Registers and enables a module. Duplicate registrations of the same
     * instance are ignored.
     * <p>
     * A module skipped after a failed {@link Module#onEnable} never receives
     * {@link Module#onDisable()}: cleaning up anything it registered before
     * throwing is its own responsibility.
     *
     * @param module the module to register
     */
    void registerModule(Module module);

    /**
     * Disables every enabled module by calling {@link Module#onDisable()}.
     * Called once by the platform on server shutdown.
     */
    void disableAll();
}
