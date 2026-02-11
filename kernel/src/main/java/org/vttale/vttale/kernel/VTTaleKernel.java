package org.vttale.vttale.kernel;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.command.CommandRegistry;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.module.ModuleRegistry;
import org.vttale.vttale.api.token.TokenRegistry;
import org.vttale.vttale.api.token.behavior.BehaviorDispatcher;
import org.vttale.vttale.kernel.command.SimpleCommandRegistry;
import org.vttale.vttale.kernel.events.SimpleEventBus;
import org.vttale.vttale.kernel.module.SimpleModuleRegistry;
import org.vttale.vttale.kernel.token.SimpleTokenRegistry;
import org.vttale.vttale.kernel.token.behavior.SimpleBehaviorDispatcher;

import java.util.ServiceLoader;

public class VTTaleKernel implements Kernel {

    private final EventBus eventBus;
    private final CommandRegistry commandRegistry;
    private final ModuleRegistry moduleRegistry;
    private final TokenRegistry tokenRegistry;
    private final BehaviorDispatcher behaviorDispatcher;

    /**
     * Initializes registries; registers modules via service provider
     */
    public VTTaleKernel() {
        this.eventBus = new SimpleEventBus();
        this.commandRegistry = new SimpleCommandRegistry(eventBus);
        this.moduleRegistry = new SimpleModuleRegistry(this);
        this.tokenRegistry = new SimpleTokenRegistry(this);
        this.behaviorDispatcher = new SimpleBehaviorDispatcher(tokenRegistry);

        // Auto-discover and register modules via SPI
        ServiceLoader<Module> loader = ServiceLoader.load(Module.class);
        for (Module module : loader) {
            moduleRegistry.registerModule(module);
        }
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }
    @Override
    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }
    @Override
    public ModuleRegistry getModuleRegistry() {
        return moduleRegistry;
    }

    @Override
    public TokenRegistry getTokenRegistry() {
        return tokenRegistry;
    }

    @Override
    public BehaviorDispatcher getBehaviorDispatcher() {
        return behaviorDispatcher;
    }
}
