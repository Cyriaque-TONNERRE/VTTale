package dev.giopalma.vttale.kernel.module;

import dev.giopalma.vttale.api.Kernel;
import dev.giopalma.vttale.api.module.Module;
import dev.giopalma.vttale.api.module.ModuleRegistry;

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
