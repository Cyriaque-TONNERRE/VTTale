package org.vttale.vttale.api.module;

/**
 * Registry for managing module lifecycle.
 * <p>
 * The ModuleRegistry is responsible for registering modules and invoking
 * their lifecycle callbacks. Modules registered here will have their
 * {@link Module#onEnable(org.vttale.vttale.api.Kernel)} method called.
 * </p>
 */
public interface ModuleRegistry {

    /**
     * Registers and enables a module.
     * <p>
     * The module's {@link Module#onEnable} method will be called immediately
     * after registration.
     * </p>
     *
     * @param module the module to register
     */
    void registerModule(Module module);
}
