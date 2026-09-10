package org.vttale.vttale.kernel.module;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.gamesystem.GameSystem;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.module.ModuleRegistry;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple implementation of ModuleRegistry that manages module lifecycle.
 * A failing module is logged and skipped, never propagated.
 */
public class SimpleModuleRegistry implements ModuleRegistry {

    private static final System.Logger LOGGER = System.getLogger(SimpleModuleRegistry.class.getName());

    private final Kernel kernel;
    private final List<Module> modules = new CopyOnWriteArrayList<>();

    public SimpleModuleRegistry(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public synchronized void registerModule(Module module) {
        if (modules.contains(module)) {
            return;
        }
        // Exclusivity: at most one GameSystem per server. Refused BEFORE onEnable, so the
        // rejected module produces no side effect at all - nothing to roll back.
        if (module instanceof GameSystem candidate) {
            GameSystem active = kernel.getService(GameSystem.class);
            if (active != null) {
                LOGGER.log(Level.ERROR, "A game system is already active (" + active.id()
                        + "); refusing " + candidate.getClass().getName());
                return;
            }
        }
        try {
            module.onEnable(kernel);
        } catch (Throwable e) {
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
            } catch (Throwable e) {
                LOGGER.log(Level.ERROR, "Module " + module.getClass().getName() + " failed to disable", e);
            }
        }
        modules.clear();
    }
}
