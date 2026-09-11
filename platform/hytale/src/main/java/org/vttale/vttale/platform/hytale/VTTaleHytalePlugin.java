package org.vttale.vttale.platform.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.VTTale;
import org.vttale.vttale.api.module.ModuleRegistry;
import org.vttale.vttale.gamesystem.dnd5e.DND5EGameSystem;
import org.vttale.vttale.kernel.VTTaleKernel;
import org.vttale.vttale.kernel.module.SimpleModuleRegistry;
import org.vttale.vttale.module.chat.ChatModule;
import org.vttale.vttale.module.diceroll.DiceRollModule;
import org.vttale.vttale.module.token.TokenModule;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * VTTale platform for Hytale. The only module aware of the game API.
 * <p>
 * Bootstrap order: create kernel, inject facade, register built-in modules
 * explicitly (no SPI). The token binder declares its TokenRegistry dependency
 * via requires(); the registry parks it until the service exists.
 * <p>
 * Shutdown: Hytale calls shutdown() on server stop, while the world and the
 * event bus are still alive — that is where modules save (see Module#onDisable).
 * <p>
 * Reload is not supported: the plugin boots once per JVM (VTTale.init throws
 * on re-init, and the module registry is closed for good after shutdown()).
 */
public class VTTaleHytalePlugin extends JavaPlugin {

    // volatile: setup() and shutdown() may run on different threads. PluginManager
    // does hold its write lock across both today, but not depending on that is one
    // keyword.
    private volatile ModuleRegistry modules;

    public VTTaleHytalePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Kernel kernel = new VTTaleKernel();
        VTTale.init(kernel);

        modules = kernel.getModuleRegistry();
        modules.registerModule(new HytaleAdapter(this));
        modules.registerModule(new ChatModule());
        modules.registerModule(new DiceRollModule());
        modules.registerModule(new TokenModule());
        modules.registerModule(new DND5EGameSystem());
        modules.registerModule(new HytaleTokenBinder(this));

        // Report modules still parked on missing services. instanceof, not a
        // cast: a diagnostic must never fail the boot if the impl changes.
        if (modules instanceof SimpleModuleRegistry registry) {
            registry.reportPendingModules();
        }

        getLogger().at(Level.INFO).log("VTTale ready");
    }

    @Override
    protected void shutdown() {
        if (modules == null) {
            return; // setup() failed before booting the kernel
        }
        modules.disableAll();
        getLogger().at(Level.INFO).log("VTTale shut down");
    }
}
