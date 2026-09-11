package org.vttale.vttale.kernel.module;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.gamesystem.GameSystem;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.token.TokenComponent;
import org.vttale.vttale.kernel.VTTaleKernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SimpleModuleRegistryTest {

    /** Records its lifecycle calls into a shared log so ordering can be asserted. */
    private static class RecordingModule implements Module {
        private final String name;
        private final List<String> log;
        private final boolean failOnEnable;
        private final boolean failOnDisable;
        Kernel seenKernel;

        @Override
        public String id() {
            return name;
        }

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

    /** Minimal GameSystem: logs its lifecycle and registers itself under GameSystem.class. */
    private static class StubGameSystem implements GameSystem {
        private final String id;
        private final List<String> log;
        Kernel seenKernel;

        StubGameSystem(String id, List<String> log) {
            this.id = id;
            this.log = log;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String version() {
            return "1.0";
        }

        @Override
        public Set<Class<? extends TokenComponent>> components() {
            return Set.of();
        }

        @Override
        public void onEnable(Kernel kernel) {
            this.seenKernel = kernel;
            log.add("enable:" + id);
            kernel.registerService(GameSystem.class, this);
        }

        @Override
        public void onDisable() {
            log.add("disable:" + id);
        }
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
    @DisplayName("a taken id is refused; a distinct id is not")
    void duplicateIdIsRefused() {
        registry.registerModule(new RecordingModule("a", log));
        registry.registerModule(new RecordingModule("a", log));
        registry.registerModule(new RecordingModule("b", log));

        assertIterableEquals(List.of("enable:a", "enable:b"), log);
    }

    @Test
    @DisplayName("a module that failed to enable keeps its id reserved")
    void failedModuleKeepsIdReserved() {
        registry.registerModule(new RecordingModule("a", log, true, false));
        registry.registerModule(new RecordingModule("a", log));

        assertIterableEquals(List.of("enable:a"), log);
    }

    @Test
    @DisplayName("a module whose id() throws is refused")
    void idThrowingIsRefused() {
        registry.registerModule(new RecordingModule("bad", log) {
            @Override
            public String id() {
                throw new IllegalStateException("boom");
            }
        });
        registry.registerModule(new RecordingModule("ok", log));

        assertIterableEquals(List.of("enable:ok"), log);
    }

    @Test
    @DisplayName("a module returning null from id() is refused")
    void idNullIsRefused() {
        registry.registerModule(new RecordingModule("bad", log) {
            @Override
            public String id() {
                return null;
            }
        });
        registry.registerModule(new RecordingModule("ok", log));

        assertIterableEquals(List.of("enable:ok"), log);
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

    @Test
    @DisplayName("a second game system is refused before any side effect")
    void secondGameSystemIsRefusedBeforeInit() {
        StubGameSystem first = new StubGameSystem("dnd5e", log);
        StubGameSystem second = new StubGameSystem("pf2e", log);

        registry.registerModule(first);
        registry.registerModule(second);

        // The refused system's onEnable() must not run AT ALL: that is the whole
        // point (init is not transactional - no rollback exists).
        assertIterableEquals(List.of("enable:dnd5e"), log);
        assertSame(first, kernel.getService(GameSystem.class));
        assertSame(kernel, first.seenKernel);
        assertNull(second.seenKernel);

        // And it is not in the registry either: disableAll() never reaches it.
        registry.disableAll();
        assertIterableEquals(List.of("enable:dnd5e", "disable:dnd5e"), log);
    }

    @Test
    @DisplayName("a game system is accepted while no other is active")
    void gameSystemAcceptedWhenNoneActive() {
        StubGameSystem gs = new StubGameSystem("dnd5e", log);

        registry.registerModule(gs);

        assertIterableEquals(List.of("enable:dnd5e"), log);
        assertSame(gs, kernel.getService(GameSystem.class));
    }

    @Test
    @DisplayName("plain modules are never affected by the game system guard")
    void plainModulesUnaffectedByGuard() {
        registry.registerModule(new StubGameSystem("dnd5e", log));
        registry.registerModule(new RecordingModule("chat", log));

        assertIterableEquals(List.of("enable:dnd5e", "enable:chat"), log);
    }
}
