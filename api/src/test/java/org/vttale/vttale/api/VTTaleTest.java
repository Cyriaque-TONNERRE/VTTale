package org.vttale.vttale.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.command.CommandRegistry;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.module.ModuleRegistry;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * VTTale is a process-wide singleton with no reset, so its whole lifecycle has to be exercised
 * inside ONE test method: any second method would depend on execution order.
 * <p>
 * That constraint is itself the finding. As long as there is no VTTale#shutdown(), reloading the
 * plugin without restarting the server leaves VTTale permanently broken.
 */
class VTTaleTest {

    private static final class StubKernel implements Kernel {
        @Override public EventBus getEventBus() { return null; }
        @Override public CommandRegistry getCommandRegistry() { return null; }
        @Override public ModuleRegistry getModuleRegistry() { return null; }
        @Override public <T> T getService(Class<T> serviceClass) { return null; }
        @Override public <T> void registerService(Class<T> serviceClass, T service) { }
    }

    @Test
    @DisplayName("singleton lifecycle: fails before init, then locks permanently")
    void singletonLifecycle() {
        assertThrows(IllegalStateException.class, VTTale::getKernel,
                "getKernel() before init() must fail loudly");

        Kernel kernel = new StubKernel();
        VTTale.init(kernel);
        assertSame(kernel, VTTale.getKernel());

        assertThrows(IllegalStateException.class, () -> VTTale.init(new StubKernel()),
                "init() is not idempotent and there is no way to release the singleton");

        // Note: init(null) also reports IllegalStateException here, not NullPointerException,
        // because the "already initialized" guard runs before Objects.requireNonNull.
        assertThrows(IllegalStateException.class, () -> VTTale.init(null));
    }
}
