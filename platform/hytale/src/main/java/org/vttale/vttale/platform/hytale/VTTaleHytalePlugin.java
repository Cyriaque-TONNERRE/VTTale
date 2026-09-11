package org.vttale.vttale.platform.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.VTTale;
import org.vttale.vttale.api.module.ModuleRegistry;
import org.vttale.vttale.api.token.TokenRegistry;
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
 * explicitly (no SPI), wire the token binder when the token service exists,
 * then disable everything cleanly on shutdown.
 * <p>
 * Reload is not supported: the plugin boots once per JVM (VTTale.init throws
 * on re-init, and setup() registers a shutdown hook that a second boot would pile up).
 */
public class VTTaleHytalePlugin extends JavaPlugin {

    public VTTaleHytalePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Kernel kernel = new VTTaleKernel();
        VTTale.init(kernel);

        ModuleRegistry modules = kernel.getModuleRegistry();
        modules.registerModule(new HytaleAdapter(this));
        modules.registerModule(new ChatModule());
        modules.registerModule(new DiceRollModule());
        modules.registerModule(new TokenModule());
        modules.registerModule(new DND5EGameSystem());

        // Report modules still parked on missing services. instanceof, not a
        // cast: a diagnostic must never fail the boot if the impl changes.
        if (modules instanceof SimpleModuleRegistry registry) {
            registry.reportPendingModules();
        }

        TokenRegistry tokens = kernel.getService(TokenRegistry.class);
        if (tokens != null) {
            new HytaleTokenBinder(kernel, tokens, this).initialize();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(modules::disableAll, "vttale-shutdown"));

        getLogger().at(Level.INFO).log("VTTale ready");
    }
}
