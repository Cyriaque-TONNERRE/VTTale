package org.vttale.vttale.kernel.module;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple implementation of ModuleRegistry that manages module lifecycle.
 */
public class SimpleModuleRegistry implements ModuleRegistry {
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
        modules.add(module);
        module.onEnable(kernel);
    }
}
