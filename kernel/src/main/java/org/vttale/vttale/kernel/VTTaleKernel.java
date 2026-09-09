package org.vttale.vttale.kernel;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.command.CommandRegistry;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.module.ModuleRegistry;
import org.vttale.vttale.kernel.command.SimpleCommandRegistry;
import org.vttale.vttale.kernel.events.SimpleEventBus;
import org.vttale.vttale.kernel.module.SimpleModuleRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VTTaleKernel implements Kernel {

    private final EventBus eventBus;
    private final CommandRegistry commandRegistry;
    private final ModuleRegistry moduleRegistry;
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    /**
     * Initializes registries. Modules are registered explicitly by the
     * platform (no SPI): built-in modules in the platform setup(), third-party
     * modules from their own plugin setup().
     */
    public VTTaleKernel() {
        this.eventBus = new SimpleEventBus();
        this.commandRegistry = new SimpleCommandRegistry(eventBus);
        this.moduleRegistry = new SimpleModuleRegistry(this);
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
    public <T> T getService(Class<T> serviceClass) {
        return serviceClass.cast(services.get(serviceClass));
    }

    @Override
    public <T> void registerService(Class<T> serviceClass, T service) {
        services.put(serviceClass, service);
    }
}
