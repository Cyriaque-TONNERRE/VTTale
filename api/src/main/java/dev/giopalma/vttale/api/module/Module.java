package dev.giopalma.vttale.api.module;

import dev.giopalma.vttale.api.Kernel;

/**
 * Represents a VTTale module that can be loaded and managed by the kernel.
 * <p>
 * Modules are the primary extension point for adding functionality to VTTale.
 * They can register commands, subscribe to events, and interact with other
 * modules through the kernel's services.
 * </p>
 * <p>
 * Modules are discovered automatically via Java's
 * {@link java.util.ServiceLoader}
 * mechanism. To register a module, add its fully qualified class name to
 * {@code META-INF/services/dev.giopalma.vttale.api.module.Module}.
 * </p>
 */
public interface Module {

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
     * Called when the module is disabled.
     * <p>
     * Use this method to clean up resources and unregister any listeners.
     * The default implementation does nothing.
     * </p>
     */
    default void onDisable() {
    }
}
