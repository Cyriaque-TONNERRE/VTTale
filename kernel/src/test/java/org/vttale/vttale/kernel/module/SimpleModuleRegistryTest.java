package org.vttale.vttale.kernel.module;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.kernel.VTTaleKernel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SimpleModuleRegistryTest {

    /** Records its lifecycle calls into a shared log so ordering can be asserted. */
    private static class RecordingModule implements Module {
        private final String name;
        private final List<String> log;
        private final boolean failOnEnable;
        private final boolean failOnDisable;
        Kernel seenKernel;

        RecordingModule(String name, List<String> log) {
            this(name, log, false, false);
        }

        RecordingModule(String name, List<String> log, boolean failOnEnable, boolean failOnDisable) {
            this.name = name;
            this.log = log;
            this.failOnEnable = failOnEnable;
            this.failOnDisable = failOnDisable;
        }

        @Override
        public void onEnable(Kernel kernel) {
            this.seenKernel = kernel;
            log.add("enable:" + name);
            if (failOnEnable) {
                throw new IllegalStateException("cannot enable " + name);
            }
        }

        @Override
        public void onDisable() {
            log.add("disable:" + name);
            if (failOnDisable) {
                throw new IllegalStateException("cannot disable " + name);
            }
        }
    }

    private Kernel kernel;
    private SimpleModuleRegistry registry;
    private List<String> log;

    @BeforeEach
    void setUp() {
        kernel = new VTTaleKernel();
        registry = new SimpleModuleRegistry(kernel);
        log = new ArrayList<>();
    }

    @Test
    @DisplayName("registering enables the module with the kernel")
    void registerEnables() {
        RecordingModule module = new RecordingModule("a", log);
        registry.registerModule(module);

        assertIterableEquals(List.of("enable:a"), log);
        assertSame(kernel, module.seenKernel);
    }

    @Test
    @DisplayName("the same instance registered twice is enabled once")
    void duplicateInstanceIsIgnored() {
        RecordingModule module = new RecordingModule("a", log);
        registry.registerModule(module);
        registry.registerModule(module);

        assertIterableEquals(List.of("enable:a"), log);
    }

    @Test
    @DisplayName("two distinct instances of the same class are both enabled")
    void distinctInstancesAreNotDeduplicated() {
        registry.registerModule(new RecordingModule("a", log));
        registry.registerModule(new RecordingModule("a", log));

        // Deduplication is by identity (List#contains -> equals), not by module type.
        assertEquals(2, log.size());
    }

    @Test
    @DisplayName("a module that fails to enable is skipped, not half-registered")
    void failingModuleIsSkipped() {
        RecordingModule broken = new RecordingModule("broken", log, true, false);
        RecordingModule healthy = new RecordingModule("healthy", log);

        assertDoesNotThrow(() -> registry.registerModule(broken));
        registry.registerModule(healthy);
        registry.disableAll();

        // "broken" must never see onDisable(): it was never successfully enabled.
        assertIterableEquals(
                List.of("enable:broken", "enable:healthy", "disable:healthy"), log);
    }

    @Test
    @DisplayName("disableAll disables every module in registration order and clears the registry")
    void disableAllIsIdempotent() {
        registry.registerModule(new RecordingModule("a", log));
        registry.registerModule(new RecordingModule("b", log));

        registry.disableAll();
        registry.disableAll();

        assertIterableEquals(
                List.of("enable:a", "enable:b", "disable:a", "disable:b"), log,
                "the second disableAll() must be a no-op");
    }

    @Test
    @DisplayName("a module that throws on disable does not block the others")
    void failingDisableIsContained() {
        registry.registerModule(new RecordingModule("bad", log, false, true));
        registry.registerModule(new RecordingModule("good", log));

        assertDoesNotThrow(() -> registry.disableAll());
        assertIterableEquals(
                List.of("enable:bad", "enable:good", "disable:bad", "disable:good"), log);
    }
}
