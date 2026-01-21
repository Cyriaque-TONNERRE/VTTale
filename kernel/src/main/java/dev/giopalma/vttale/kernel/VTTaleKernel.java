package dev.giopalma.vttale.kernel;

import dev.giopalma.vttale.api.Kernel;
import dev.giopalma.vttale.api.command.CommandRegistry;
import dev.giopalma.vttale.api.events.EventBus;
import dev.giopalma.vttale.api.module.Module;
import dev.giopalma.vttale.api.module.ModuleRegistry;
import dev.giopalma.vttale.kernel.command.SimpleCommandRegistry;
import dev.giopalma.vttale.kernel.events.SimpleEventBus;
import dev.giopalma.vttale.kernel.module.SimpleModuleRegistry;

import java.util.ServiceLoader;

public class VTTaleKernel implements Kernel {

    private final EventBus eventBus;
    private final CommandRegistry commandRegistry;
    private final ModuleRegistry moduleRegistry;

    public VTTaleKernel() {
        this.eventBus = new SimpleEventBus();
        this.commandRegistry = new SimpleCommandRegistry(eventBus);
        this.moduleRegistry = new SimpleModuleRegistry(this);

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
}
