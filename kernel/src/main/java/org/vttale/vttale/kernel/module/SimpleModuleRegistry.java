package org.vttale.vttale.kernel.module;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.module.ModuleRegistry;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple implementation of ModuleRegistry that manages module lifecycle.
 * A failing module is logged and skipped, never propagated.
 */
public class SimpleModuleRegistry implements ModuleRegistry {

    private static final System.Logger LOGGER = System.getLogger(SimpleModuleRegistry.class.getName());

    private final Kernel kernel;
    private final List<Module> modules = new ArrayList<>();

    public SimpleModuleRegistry(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void registerModule(Module module) {
        if (modules.contains(module)) {
            return;
        }
        try {
            module.onEnable(kernel);
        } catch (Exception e) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " failed to enable and was skipped", e);
            return;
        }
        modules.add(module);
    }

    @Override
    public void disableAll() {
        for (Module module : modules) {
            try {
                module.onDisable();
            } catch (Exception e) {
                LOGGER.log(Level.ERROR, "Module " + module.getClass().getName() + " failed to disable", e);
            }
        }
        modules.clear();
    }
}
