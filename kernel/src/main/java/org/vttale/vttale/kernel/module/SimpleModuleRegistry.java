package org.vttale.vttale.kernel.module;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.gamesystem.GameSystem;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.module.ModuleRegistry;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple implementation of ModuleRegistry that manages module lifecycle.
 * A failing module is logged and skipped, never propagated.
 * <p>
 * A module whose required services are not registered yet is parked and
 * activated when they appear (see {@link #drainPending()}). A module id is
 * reserved at registration and never released until {@link #disableAll()}.
 */
public class SimpleModuleRegistry implements ModuleRegistry {

    private static final System.Logger LOGGER = System.getLogger(SimpleModuleRegistry.class.getName());

    private final Kernel kernel;
    private final List<Module> modules = new CopyOnWriteArrayList<>();
    // Parked modules, in registration order: the drain activates them in that
    // order, so two parked game systems resolve deterministically.
    private final List<Module> pending = new ArrayList<>();
    // Reserved module ids -> owner. Reserved at registration, released by disableAll only.
    private final Map<String, Module> ids = new HashMap<>();
    // Re-entrancy guard: armed around onEnable, NOT around the drain loop. In
    // the chain registerModule -> onEnable -> registerService -> drainPending,
    // no drain is in progress yet at re-entrance time - only the activation
    // is. A counter, not a boolean: an onEnable may register another module.
    private int activating = 0;
    private boolean draining = false;
    private boolean dirty = false;

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
            return;
        }
        Module taken = ids.get(id);
        if (taken != null) {
            if (taken == module) {
                // Same instance again: it failed to enable or was refused, so it is
                // in neither list - this branch is the only trace of it.
                LOGGER.log(Level.ERROR, "Module " + id
                        + " is already registered but inactive (failed to enable or was refused);"
                        + " re-registration refused");
            } else {
                LOGGER.log(Level.ERROR, "Module id " + id + " is already taken by "
                        + taken.getClass().getName() + "; refusing " + module.getClass().getName());
            }
            return;
        }
        ids.put(id, module);
        Set<Class<?>> missing = unresolved(module);
        if (missing == null) {
            return;
        }
        if (!missing.isEmpty()) {
            pending.add(module);
            LOGGER.log(Level.INFO, "Module " + id + " parked, waiting for " + missing);
            return;
        }
        tryEnable(module);
    }

    /**
     * Single activation path, used by registerModule and by the drain of
     * parked modules. The id and the requirements are already resolved.
     */
    private synchronized void tryEnable(Module module) {
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
        activating++;
        boolean enabled = false;
        try {
            module.onEnable(kernel);
            enabled = true;
        } catch (Throwable e) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " failed to enable and was skipped", e);
        } finally {
            activating--;
        }
        if (enabled) {
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
        // Drained even when onEnable threw: it may have registered services before
        // failing, and those stay in the kernel (no rollback), so they must wake
        // their dependents like any other service. The nested drain that their
        // registration triggered was deferred by the activating guard; without this,
        // dirty stays set with nobody left to act on it.
        drainPending();
    }

    /**
     * Activates parked modules whose requirements are now met, in park order,
     * looping until stable so chains unfold. Never runs while an activation or
     * another drain is in progress: re-entrant calls only set {@code dirty}
     * and the outermost drain repeats. See the design spec,
     * "Pourquoi la garde anti-réentrance".
     * <p>
     * Each pass re-reads {@code requires()} (third-party code) for every parked
     * module. Invisible at boot scale; revisit if the parked set ever grows.
     */
    private synchronized void drainPending() {
        if (activating > 0 || draining) {
            dirty = true;
            return;
        }
        draining = true;
        try {
            do {
                dirty = false;
                // Copy: tryEnable's onEnable may register new modules, which parks them.
                for (Module module : List.copyOf(pending)) {
                    Set<Class<?>> missing = unresolved(module);
                    if (missing == null || !missing.isEmpty()) {
                        continue;
                    }
                    pending.remove(module);
                    tryEnable(module);
                }
            } while (dirty);
        } finally {
            draining = false;
        }
    }

    /**
     * Called by the kernel after a service registration: a parked module may
     * now resolve. Drains immediately when no activation or drain is in
     * progress; otherwise just marks the queue dirty for the outermost drain.
     * Kernel-internal on purpose: not part of the ModuleRegistry contract.
     */
    public void onServiceRegistered() {
        drainPending();
    }

    /**
     * Boot-time report: logs every still-parked module at ERROR with the
     * services it waits for. Called by the platform during setup(), after the
     * built-in modules are registered. Reports, never activates: parking is
     * normal while plugins load,
     * never-satisfied is the real problem - hence ERROR here, INFO at park
     * time. Known ceiling: modules parked after this call (third-party plugins
     * loading later) are only covered by their parking INFO line.
     */
    public synchronized void reportPendingModules() {
        for (Module module : pending) {
            Set<Class<?>> missing = unresolved(module);
            String waiting = missing == null ? "unreadable requirements" : missing.toString();
            LOGGER.log(Level.ERROR, "Module " + idOf(module) + " is still parked, waiting for " + waiting);
        }
    }

    /**
     * The required services not yet registered, or null if {@code requires()}
     * threw, returned null or contained null - the module is unreadable either
     * way and the caller refuses it. Never lets third-party code escape.
     */
    private Set<Class<?>> unresolved(Module module) {
        Set<Class<?>> required;
        try {
            required = module.requires();
        } catch (Throwable e) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " threw from requires() and was refused", e);
            return null;
        }
        if (required == null) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " returned null from requires() and was refused");
            return null;
        }
        Set<Class<?>> missing = new LinkedHashSet<>();
        for (Class<?> service : required) {
            if (service == null) {
                LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                        + " has a null entry in requires() and was refused");
                return null;
            }
            if (kernel.getService(service) == null) {
                missing.add(service);
            }
        }
        return missing;
    }

    /**
     * The module's id, or null if {@code id()} threw (logged) or returned
     * null (logged). One accurate ERROR per refusal. Never lets a third-party
     * {@code id()} escape.
     */
    private String safeId(Module module) {
        try {
            String id = module.id();
            if (id == null) {
                LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                        + " returned null from id() and was refused");
            }
            return id;
        } catch (Throwable e) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " threw from id() and was refused", e);
            return null;
        }
    }

    /** Renders a module id for a log line without letting third-party code escape. */
    private static String idOf(Module module) {
        try {
            return module.id();
        } catch (Throwable e) {
            return module.getClass().getName();
        }
    }

    // Synchronized like registerModule. Both methods run at boot/shutdown only,
    // so holding the lock across the callbacks costs nothing. A module
    // registered concurrently while the loop runs would otherwise be dropped by
    // clear() without ever seeing onDisable.
    // Note: services registered by these modules are NOT cleared - the kernel has no
    // unregister. getService(GameSystem.class) therefore still returns the disabled system.
    @Override
    public synchronized void disableAll() {
        // Reverse activation order: a dependent shuts down before its provider
        // (activation order is a topological order of the dependency graph).
        for (int i = modules.size() - 1; i >= 0; i--) {
            Module module = modules.get(i);
            try {
                module.onDisable();
            } catch (Throwable e) {
                LOGGER.log(Level.ERROR, "Module " + module.getClass().getName() + " failed to disable", e);
            }
        }
        modules.clear();
        pending.clear();
        ids.clear();
    }
}
