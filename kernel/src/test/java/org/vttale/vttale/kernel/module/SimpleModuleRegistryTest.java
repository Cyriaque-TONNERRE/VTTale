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
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        private final Set<Class<?>> requires;
        Kernel seenKernel;

        RecordingModule(String name, List<String> log) {
            this(name, log, false, false, Set.of());
        }

        RecordingModule(String name, List<String> log, boolean failOnEnable, boolean failOnDisable) {
            this(name, log, failOnEnable, failOnDisable, Set.of());
        }

        RecordingModule(String name, List<String> log, Set<Class<?>> requires) {
            this(name, log, false, false, requires);
        }

        private RecordingModule(String name, List<String> log, boolean failOnEnable,
                boolean failOnDisable, Set<Class<?>> requires) {
            this.name = name;
            this.log = log;
            this.failOnEnable = failOnEnable;
            this.failOnDisable = failOnDisable;
            this.requires = requires;
        }

        @Override
        public String id() {
            return name;
        }

        @Override
        public Set<Class<?>> requires() {
            return requires;
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

    /** Marker service for tests; registered by the provider modules below. */
    private interface SomeService {
    }

    /** Second marker service, independent of {@link SomeService}. */
    private interface OtherService {
    }

    /** Logs its enable, then publishes itself as SomeService. */
    private static class SomeServiceProviderModule extends RecordingModule implements SomeService {
        SomeServiceProviderModule(String name, List<String> log, Set<Class<?>> requires) {
            super(name, log, requires);
        }

        SomeServiceProviderModule(String name, List<String> log) {
            super(name, log);
        }

        @Override
        public void onEnable(Kernel kernel) {
            super.onEnable(kernel);
            kernel.registerService(SomeService.class, this);
        }
    }

    /** Logs its enable, then publishes itself as OtherService. */
    private static class OtherServiceProviderModule extends RecordingModule implements OtherService {
        OtherServiceProviderModule(String name, List<String> log) {
            super(name, log);
        }

        @Override
        public void onEnable(Kernel kernel) {
            super.onEnable(kernel);
            kernel.registerService(OtherService.class, this);
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
    @DisplayName("disableAll disables every module in reverse activation order and clears the registry")
    void disableAllIsIdempotent() {
        registry.registerModule(new RecordingModule("a", log));
        registry.registerModule(new RecordingModule("b", log));

        registry.disableAll();
        registry.disableAll();

        assertIterableEquals(
                List.of("enable:a", "enable:b", "disable:b", "disable:a"), log,
                "the second disableAll() must be a no-op");
    }

    @Test
    @DisplayName("disableAll releases reserved ids")
    void disableAllReleasesIds() {
        registry.registerModule(new RecordingModule("a", log));
        registry.disableAll();

        registry.registerModule(new RecordingModule("a", log));
        registry.disableAll();

        assertIterableEquals(List.of("enable:a", "disable:a", "enable:a", "disable:a"), log);
    }

    @Test
    @DisplayName("a module that throws on disable does not block the others")
    void failingDisableIsContained() {
        registry.registerModule(new RecordingModule("bad", log, false, true));
        registry.registerModule(new RecordingModule("good", log));

        assertDoesNotThrow(() -> registry.disableAll());
        assertIterableEquals(
                List.of("enable:bad", "enable:good", "disable:good", "disable:bad"), log);
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

    @Test
    @DisplayName("a module with unmet requirements is parked, not enabled")
    void moduleWithMissingServiceIsParked() {
        registry.registerModule(new RecordingModule("late", log, Set.of(SomeService.class)));

        assertEquals(List.of(), log);

        // A parked module never saw onEnable, so it never sees onDisable.
        registry.disableAll();
        assertEquals(List.of(), log);
    }

    @Test
    @DisplayName("a parked module activates when its provider registers later")
    void parkedModuleActivatesWhenProviderArrives() {
        registry.registerModule(new RecordingModule("consumer", log, Set.of(SomeService.class)));
        registry.registerModule(new SomeServiceProviderModule("provider", log));

        assertIterableEquals(List.of("enable:provider", "enable:consumer"), log);
    }

    @Test
    @DisplayName("a chain of parked modules unfolds in dependency order")
    void chainUnfoldsInOrder() {
        registry.registerModule(new RecordingModule("a", log, Set.of(SomeService.class)));
        registry.registerModule(new SomeServiceProviderModule("b", log, Set.of(OtherService.class)));
        registry.registerModule(new OtherServiceProviderModule("c", log));

        assertIterableEquals(List.of("enable:c", "enable:b", "enable:a"), log);
    }

    @Test
    @DisplayName("no parked module activates while a provider's onEnable is running")
    void noActivationDuringOnEnable() {
        Module provider = new RecordingModule("p", log) {
            @Override
            public void onEnable(Kernel kernel) {
                log.add("P:start");
                kernel.registerService(SomeService.class, new SomeService() {
                });
                kernel.registerService(OtherService.class, new OtherService() {
                });
                log.add("P:end");
            }
        };
        // The consumer requires ONLY the first service: requiring both would
        // mask the bug (it could not resolve at the first registerService anyway).
        registry.registerModule(new RecordingModule("c", log, Set.of(SomeService.class)));
        registry.registerModule(provider);

        assertIterableEquals(List.of("P:start", "P:end", "enable:c"), log,
                "the consumer must not activate between the provider's two registerService calls");
    }

    @Test
    @DisplayName("two parked game systems: first registered wins, deterministically")
    void twoParkedGameSystemsFirstRegisteredWins() {
        StubGameSystem gs1 = new StubGameSystem("gs1", log) {
            @Override
            public Set<Class<?>> requires() {
                return Set.of(SomeService.class);
            }
        };
        StubGameSystem gs2 = new StubGameSystem("gs2", log) {
            @Override
            public Set<Class<?>> requires() {
                return Set.of(SomeService.class);
            }
        };

        registry.registerModule(gs1);
        registry.registerModule(gs2);
        registry.registerModule(new SomeServiceProviderModule("provider", log));

        assertIterableEquals(List.of("enable:provider", "enable:gs1"), log);
        assertSame(gs1, kernel.getService(GameSystem.class));
    }

    @Test
    @DisplayName("a module whose requires() throws is refused")
    void requiresThrowingIsRefused() {
        registry.registerModule(new RecordingModule("bad", log) {
            @Override
            public Set<Class<?>> requires() {
                throw new IllegalStateException("boom");
            }
        });
        registry.registerModule(new RecordingModule("ok", log));

        assertIterableEquals(List.of("enable:ok"), log);
    }

    @Test
    @DisplayName("a module returning null from requires() is refused")
    void requiresNullIsRefused() {
        registry.registerModule(new RecordingModule("bad", log) {
            @Override
            public Set<Class<?>> requires() {
                return null;
            }
        });
        registry.registerModule(new RecordingModule("ok", log));

        assertIterableEquals(List.of("enable:ok"), log);
    }

    @Test
    @DisplayName("a module with a null entry in requires() is refused")
    void requiresNullEntryIsRefused() {
        registry.registerModule(new RecordingModule("bad", log) {
            @Override
            public Set<Class<?>> requires() {
                Set<Class<?>> withNull = new java.util.HashSet<>();
                withNull.add(null);
                return withNull;
            }
        });
        registry.registerModule(new RecordingModule("ok", log));

        assertIterableEquals(List.of("enable:ok"), log);
    }
}
