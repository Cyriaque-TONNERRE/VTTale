package org.vttale.vttale.api.module;

import org.vttale.vttale.api.Kernel;

import java.util.Set;

/**
 * Represents a VTTale module that can be loaded and managed by the kernel.
 * <p>
 * Modules are the primary extension point for adding functionality to VTTale.
 * They can register commands, subscribe to events, and interact with other
 * modules through the kernel's services.
 * </p>
 * <p>
 * Modules are registered explicitly. Built-in modules are registered by the
 * platform at startup: {@code registerModule(new ChatModule())}, and so on.
 * Third-party modules register themselves from their Hytale plugin
 * {@code setup()} via
 * {@code VTTale.getKernel().getModuleRegistry().registerModule(...)}.
 * Hytale classloaders are isolated per JAR, so self-registration is the only
 * cross-JAR mechanism.
 * </p>
 */
public interface Module {

    /**
     * Stable identifier, unique across all installed modules: the registry
     * refuses a module whose id is already taken (enabled or pending), and the
     * id stays reserved even if the module is later refused or fails to
     * enable. Default: fully qualified class name - unique across packages.
     * Override with a namespaced id ({@code "vttale:chat"}) for readable logs.
     * A module class is therefore a singleton per server: two instances of the
     * same class collide on the same default id.
     */
    default String id() {
        return getClass().getName();
    }

    /**
     * Services that must be registered before this module can enable, read
     * with {@code kernel.getService(...)} inside {@link #onEnable}. Empty by
     * default. Registration order stops mattering for these: the registry
     * parks the module until every entry resolves.
     * <p>
     * Event coupling is NOT a dependency: subscriptions happen at enable time
     * and publications at runtime, so two modules that talk through events do
     * not constrain each other's order. Declaring one here creates a false
     * constraint, and two modules listening to each other create a deadlock
     * this registry cannot resolve.
     */
    default Set<Class<?>> requires() {
        return Set.of();
    }

    /**
     * Called when the module is enabled.
     * <p>
     * Use this method to register commands, subscribe to events, and
     * initialize any required resources.
     * </p>
     *
     * @param kernel the kernel instance providing access to core services
     */
    void onEnable(Kernel kernel);

    /**
     * Called once when the module is disabled, in reverse activation order
     * (dependents before providers) — never twice, even if
     * {@code disableAll()} runs again. This is the save point: services are
     * still registered and the kernel bus still works, so serialize state
     * here.
     * <p>
     * Keep the save fast and synchronous: a save that blocks hangs the server
     * stop. Do not queue world work (a task submitted during shutdown may
     * never run) and do not register modules or services (the registry is
     * closed). Third-party modules: your plugin's Hytale registrations are
     * already torn down by the time this runs — undo those in your plugin's
     * own {@code shutdown()}, not here.
     * </p>
     */
    default void onDisable() {
    }
}
