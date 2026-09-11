package org.vttale.vttale.api.module;

/**
 * Registry for managing module lifecycle.
 * <p>
 * A module is enabled immediately when every service returned by
 * {@link Module#requires()} is registered; otherwise it is parked and
 * activated when they appear. Registration order therefore does not matter
 * for service dependencies - only for event-driven bridging, which the
 * platform owns.
 * <p>
 * Each module id ({@link Module#id()}) is reserved at registration and
 * released only by {@link #disableAll()}: a module whose id is already taken
 * (enabled, parked, refused, or failed to enable) is refused.
 * <p>
 * A module that is refused, parked, or skipped after a failed
 * {@link Module#onEnable} never receives {@link Module#onDisable()}: cleaning
 * up anything it registered before throwing is its own responsibility.
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
     * Called once by the platform on server shutdown (or plugin unload).
     * Disables in reverse activation order - a dependent shuts down before its provider.
     * This closes the registry for good: afterwards {@link #registerModule} is
     * refused and nothing activates.
     */
    void disableAll();
}
