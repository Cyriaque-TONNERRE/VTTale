package org.vttale.vttale.kernel.module;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.gamesystem.GameSystem;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.module.ModuleRegistry;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple implementation of ModuleRegistry that manages module lifecycle.
 * A failing module is logged and skipped, never propagated.
 */
public class SimpleModuleRegistry implements ModuleRegistry {

    private static final System.Logger LOGGER = System.getLogger(SimpleModuleRegistry.class.getName());

    private final Kernel kernel;
    private final List<Module> modules = new CopyOnWriteArrayList<>();

    // Reserved module ids -> owner. Reserved at registration, released by disableAll only.
    private final Map<String, Module> ids = new HashMap<>();

    // Parked modules, in registration order: the drain (Task 3) activates them
    // in that order, so two parked game systems resolve deterministically.
    private final List<Module> pending = new ArrayList<>();

    public SimpleModuleRegistry(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public synchronized void registerModule(Module module) {
        if (modules.contains(module) || pending.contains(module)) {
            return;
        }
        String id = safeId(module);
        if (id == null) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " returned null from id() and was refused");
            return;
        }
        Module taken = ids.get(id);
        if (taken != null) {
            LOGGER.log(Level.ERROR, "Module id " + id + " is already taken by "
                    + taken.getClass().getName() + "; refusing " + module.getClass().getName());
            return;
        }
        ids.put(id, module);
        boolean isGameSystem = module instanceof GameSystem;
        // Exclusivity: at most one GameSystem per server. Refused BEFORE onEnable, so the
        // rejected module produces no side effect at all - nothing to roll back.
        if (isGameSystem) {
            GameSystem active = kernel.getService(GameSystem.class);
            if (active != null) {
                LOGGER.log(Level.ERROR, "A game system is already active (" + idOf(active)
                        + "); refusing " + module.getClass().getName());
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
        // The guard above only sees a system that published itself under GameSystem.class
        // (see GameSystem's javadoc). One that skipped it is left running on purpose -
        // refusing here would recreate the half-initialised module the guard exists to
        // prevent - but exclusivity no longer holds for it, so say so loudly.
        if (isGameSystem && kernel.getService(GameSystem.class) == null) {
            LOGGER.log(Level.ERROR, "Game system " + module.getClass().getName()
                    + " did not publish itself under GameSystem.class in onEnable;"
                    + " the exclusivity guard cannot see it");
        }
        modules.add(module);
    }

    /** Renders a module id for a log line without letting third-party code escape. */
    private static String idOf(Module module) {
        try {
            return module.id();
        } catch (Throwable e) {
            return module.getClass().getName();
        }
    }

    /**
     * The module's id, or null if {@code id()} threw (logged) or returned
     * null. Never lets a third-party {@code id()} escape.
     */
    private String safeId(Module module) {
        try {
            return module.id();
        } catch (Throwable e) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " threw from id() and was refused", e);
            return null;
        }
    }

    // Synchronized like registerModule: without it a module registered concurrently can be
    // appended after the loop's snapshot and then dropped by clear() without ever seeing
    // onDisable. Both methods run at boot/shutdown only, so holding the lock across the
    // callbacks costs nothing.
    // Note: services registered by these modules are NOT cleared - the kernel has no
    // unregister. getService(GameSystem.class) therefore still returns the disabled system.
    @Override
    public synchronized void disableAll() {
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
