package org.vttale.vttale.api.module;

import org.vttale.vttale.api.Kernel;

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
