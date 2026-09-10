# Module Dependency Deferral Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modules declare an `id()` and service `requires()`; the registry parks modules whose services are missing and activates them when the services appear.

**Architecture:** Reactive deferral in `SimpleModuleRegistry` (no topological sort): one activation path `tryEnable`, a parked-module list drained in park order, and a re-entrancy guard (`activating` counter) armed around `onEnable` itself so no parked module activates in the middle of another module's `onEnable`. Module ids are reserved at registration and released only by `disableAll`.

**Tech Stack:** Java 25, Gradle 9.7.1 (Kotlin DSL), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-11-module-dependency-deferral-design.md` (source of truth; read it before deviating)

## Global Constraints

- Branch: work on `feat/module-dependency-deferral` (already created and checked out).
- `api`, `kernel`, `module`, `gamesystem` must never import `com.hypixel.*` or `org.joml.*` — only `platform/hytale` may.
- No SPI / no `ServiceLoader`.
- Code, comments, commit messages: **English**. Conventional commits, plain, **no `Co-Authored-By` trailer**.
- Tests: JUnit 5, required on `api` and `kernel`. Log content is never asserted ("tester les logs, c'est tester l'habillage").
- Verification commands: `./gradlew :api:test :kernel:test :module:test :gamesystem:test` then `./gradlew build`.
- Design invariants baked into the spec, do not weaken: guard covers `onEnable` (counter, not boolean), drain scans `pending` in registration order over a **copy** of the list, `disableAll` iterates in reverse activation order and clears `modules`/`pending`/`ids`, a refused or failed module never receives `onDisable`.

---

### Task 1: Module contract — `id()` and `requires()` defaults

**Files:**
- Modify: `api/src/main/java/org/vttale/vttale/api/module/Module.java`
- Test: `api/src/test/java/org/vttale/vttale/api/module/ModuleDefaultsTest.java` (create)

**Interfaces:**
- Consumes: nothing new.
- Produces: `default String id()` (FQN of the class) and `default Set<Class<?>> requires()` (empty set) on `org.vttale.vttale.api.module.Module`. All later tasks rely on these exact signatures.

- [ ] **Step 1: Write the failing test**

Create `api/src/test/java/org/vttale/vttale/api/module/ModuleDefaultsTest.java`:

```java
package org.vttale.vttale.api.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModuleDefaultsTest {

    /** Minimal module: no override, so it exercises the defaults. */
    private static class BareModule implements Module {
        @Override
        public void onEnable(org.vttale.vttale.api.Kernel kernel) {
        }
    }

    @Test
    @DisplayName("default id is the fully qualified class name")
    void defaultIdIsFullyQualifiedName() {
        assertEquals(BareModule.class.getName(), new BareModule().id());
    }

    @Test
    @DisplayName("default requires no service")
    void defaultRequiresNothing() {
        assertEquals(Set.of(), new BareModule().requires());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :api:test`
Expected: BUILD FAILED — compile error in `ModuleDefaultsTest`: cannot find symbol `id()` / `requires()`.

- [ ] **Step 3: Implement the contract**

Full new content of `api/src/main/java/org/vttale/vttale/api/module/Module.java` (keeps the existing class-level javadoc, adds the imports and the two default methods after the class javadoc):

```java
package org.vttale.vttale.api.module;

import org.vttale.vttale.api.Kernel;

import java.util.Set;

/**
 * Represents a VTTale module that can be loaded and managed by the kernel.
 * <p>
 * Modules are the primary extension point for adding functionality to VTTale.
 * They can register commands, subscribe to events, and interact with other
 * modules through the kernel's services.
 * </p>
 * <p>
 * Modules are registered explicitly. Built-in modules are registered by the
 * platform at startup: {@code registerModule(new ChatModule())}, and so on.
 * Third-party modules register themselves from their Hytale plugin
 * {@code setup()} via
 * {@code VTTale.getKernel().getModuleRegistry().registerModule(...)}.
 * Hytale classloaders are isolated per JAR, so self-registration is the only
 * cross-JAR mechanism.
 * </p>
 */
public interface Module {

    /**
     * Stable identifier, unique across all installed modules: the registry
     * refuses a module whose id is already taken (enabled or pending), and the
     * id stays reserved even if the module is later refused or fails to
     * enable. Default: fully qualified class name - unique across packages.
     * Override with a namespaced id ({@code "vttale:chat"}) for readable logs.
     * A module class is therefore a singleton per server: two instances of the
     * same class collide on the same default id.
     */
    default String id() {
        return getClass().getName();
    }

    /**
     * Services that must be registered before this module can enable, read
     * with {@code kernel.getService(...)} inside {@link #onEnable}. Empty by
     * default. Registration order stops mattering for these: the registry
     * parks the module until every entry resolves.
     * <p>
     * Event coupling is NOT a dependency: subscriptions happen at enable time
     * and publications at runtime, so two modules that talk through events do
     * not constrain each other's order. Declaring one here creates a false
     * constraint, and two modules listening to each other create a deadlock
     * this registry cannot resolve.
     */
    default Set<Class<?>> requires() {
        return Set.of();
    }

    /**
     * Called when the module is enabled.
     * <p>
     * Use this method to register commands, subscribe to events, and
     * initialize any required resources.
     * </p>
     *
     * @param kernel the kernel instance providing access to core services
     */
    void onEnable(Kernel kernel);

    /**
     * Called when the module is disabled.
     * <p>
     * Use this method to clean up resources and unregister any listeners.
     * The default implementation does nothing.
     * </p>
     */
    default void onDisable() {
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :api:test`
Expected: BUILD SUCCESSFUL — all api tests pass, including the two new ones.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/org/vttale/vttale/api/module/Module.java api/src/test/java/org/vttale/vttale/api/module/ModuleDefaultsTest.java
git commit -m "feat(api): module id and service requirements contract"
```

---

### Task 2: Id deduplication in the registry

**Files:**
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java`
- Modify: `api/src/main/java/org/vttale/vttale/api/gamesystem/GameSystem.java` (javadoc only)
- Test: `kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java`

**Interfaces:**
- Consumes: `Module.id()` from Task 1.
- Produces: registry state `ids` (`Map<String, Module>`), helpers `safeId(Module)`, `idOf(Module)`. `registerModule` refuses a module whose id is taken; the id stays reserved when the module is refused or fails to enable; released only by `disableAll` (Task 4 clears it).

- [ ] **Step 1: Write the failing tests**

In `SimpleModuleRegistryTest.java`:

1. Make `RecordingModule` declare its id (add this override inside the class, right after the fields):

```java
        @Override
        public String id() {
            return name;
        }
```

2. Replace the whole `distinctInstancesAreNotDeduplicated` test with:

```java
    @Test
    @DisplayName("a taken id is refused; a distinct id is not")
    void duplicateIdIsRefused() {
        registry.registerModule(new RecordingModule("a", log));
        registry.registerModule(new RecordingModule("a", log));
        registry.registerModule(new RecordingModule("b", log));

        assertIterableEquals(List.of("enable:a", "enable:b"), log);
    }
```

3. Add these tests:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kernel:test --tests "org.vttale.vttale.kernel.module.SimpleModuleRegistryTest"`
Expected: FAIL — `duplicateIdIsRefused`, `failedModuleKeepsIdReserved`, `idThrowingIsRefused` fail (today no id check exists: the second "a" enables, the throwing-id module enables). `idNullIsRefused` fails the same way.

- [ ] **Step 3: Implement**

In `SimpleModuleRegistry.java`:

1. Add imports `java.util.HashMap`, `java.util.Map`.

2. Add the state field after `modules`:

```java
    // Reserved module ids -> owner. Reserved at registration, released by disableAll only.
    private final Map<String, Module> ids = new HashMap<>();
```

3. In `registerModule`, replace the duplicate-instance check and add the id check. The method becomes:

```java
    @Override
    public synchronized void registerModule(Module module) {
        if (modules.contains(module) || pending.contains(module)) {
            return;
        }
        String id = safeId(module);
        if (id == null) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " returned null from id() and was refused");
            return;
        }
        Module taken = ids.get(id);
        if (taken != null) {
            LOGGER.log(Level.ERROR, "Module id " + id + " is already taken by "
                    + taken.getClass().getName() + "; refusing " + module.getClass().getName());
            return;
        }
        ids.put(id, module);
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
        try {
            module.onEnable(kernel);
        } catch (Throwable e) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " failed to enable and was skipped", e);
            return;
        }
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
```

Note: `pending.contains(module)` needs the field — add it now, after `modules`:

```java
    // Parked modules, in registration order: the drain (Task 3) activates them
    // in that order, so two parked game systems resolve deterministically.
    private final List<Module> pending = new ArrayList<>();
```

with import `java.util.ArrayList`. Task 3 then only fills and drains it.

4. Replace the `describe(GameSystem)` helper with a general one (GameSystem extends Module, so every call site keeps working):

```java
    /** Renders a module id for a log line without letting third-party code escape. */
    private static String idOf(Module module) {
        try {
            return module.id();
        } catch (Throwable e) {
            return module.getClass().getName();
        }
    }
```

5. Add `safeId` (used above):

```java
    /**
     * The module's id, or null if {@code id()} threw (logged) or returned
     * null. Never lets a third-party {@code id()} escape.
     */
    private String safeId(Module module) {
        try {
            return module.id();
        } catch (Throwable e) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " threw from id() and was refused", e);
            return null;
        }
    }
```

6. In `api/src/main/java/org/vttale/vttale/api/gamesystem/GameSystem.java`, replace the `id()` javadoc — id is now the dedup key, the old "if collisions ever matter" wording is obsolete:

```java
    /**
     * Stable ruleset identifier, e.g. {@code "dnd5e"}.
     * <p>
     * Must be unique across all installed modules: the module registry
     * reserves ids at registration and refuses duplicates. Namespace it
     * ({@code "vttale:dnd5e"}).
     */
    String id();
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :kernel:test :api:test`
Expected: BUILD SUCCESSFUL — new tests pass; all existing tests (`secondGameSystemIsRefusedBeforeInit`, etc.) still pass (stub ids "dnd5e"/"pf2e" are distinct).

- [ ] **Step 5: Commit**

```bash
git add kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java api/src/main/java/org/vttale/vttale/api/gamesystem/GameSystem.java kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java
git commit -m "feat(kernel): refuse modules whose id is already taken"
```

---

### Task 3: Deferred activation — parking and the re-entrancy guard

**Files:**
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java`
- Test: `kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java`

**Interfaces:**
- Consumes: `Module.requires()` (Task 1), `ids`/`safeId`/`idOf` and `pending` field (Task 2).
- Produces: private methods `tryEnable(Module)`, `drainPending()`, `unresolved(Module)`; fields `activating` (int), `draining`, `dirty` (booleans). `registerModule` parks instead of enabling when requirements are missing. GameSystem guard and post-publish check now live inside `tryEnable`.

- [ ] **Step 1: Write the failing tests**

In `SimpleModuleRegistryTest.java`:

1. Extend `RecordingModule` with a `requires` value. Change the fields block and constructors to (keep the existing two constructors working):

```java
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

        // onEnable and onDisable keep their existing bodies exactly as they are
        // today (log line, optional throw, seenKernel assignment).
    }
```

2. Add test service types and provider modules (as top-level members of the test class):

```java
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
```

3. Add the tests:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kernel:test --tests "org.vttale.vttale.kernel.module.SimpleModuleRegistryTest"`
Expected: BUILD FAILED — compile error: constructor `RecordingModule(String, List<String>, Set<Class<?>>)` does not exist yet. (That compile failure is the red state.)

- [ ] **Step 3: Implement**

Rewrite `SimpleModuleRegistry.java` to its full new content:

```java
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
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " returned null from id() and was refused");
            return;
        }
        Module taken = ids.get(id);
        if (taken != null) {
            LOGGER.log(Level.ERROR, "Module id " + id + " is already taken by "
                    + taken.getClass().getName() + "; refusing " + module.getClass().getName());
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
        try {
            module.onEnable(kernel);
        } catch (Throwable e) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " failed to enable and was skipped", e);
            return;
        } finally {
            activating--;
        }
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
        drainPending();
    }

    /**
     * Activates parked modules whose requirements are now met, in park order,
     * looping until stable so chains unfold. Never runs while an activation or
     * another drain is in progress: re-entrant calls only set {@code dirty}
     * and the outermost drain repeats. See the design spec,
     * "Pourquoi la garde anti-réentrance".
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
     * null. Never lets a third-party {@code id()} escape.
     */
    private String safeId(Module module) {
        try {
            return module.id();
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
        for (Module module : modules) {
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
```

Notes for the implementer:
- `disableAll` here still iterates forward and clears all three collections; Task 4 changes the order semantics. Clearing `pending`/`ids` here is required by `moduleWithMissingServiceIsParked` (parked must not survive shutdown).
- On failed `onEnable`, no drain happens (the catch returns before it): never cascade activations from a half-broken provider.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :kernel:test`
Expected: BUILD SUCCESSFUL — new tests pass; every pre-existing test still passes, in particular `secondGameSystemIsRefusedBeforeInit` (guard now inside `tryEnable`, same observable behavior) and `registerEnables`/`duplicateInstanceIsIgnored`.

- [ ] **Step 5: Commit**

```bash
git add kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java
git commit -m "feat(kernel): park modules until their required services exist"
```

---

### Task 4: Reverse disable order

**Files:**
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java` (`disableAll` only)
- Test: `kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java`

**Interfaces:**
- Consumes: `modules` list in activation order (guaranteed by Task 3: a dependent only activates after its provider registered the service, so activation order is a topological order of the dependency graph).
- Produces: `disableAll` iterates in reverse activation order; dependents shut down before providers.

- [ ] **Step 1: Write the failing tests**

1. Update `disableAllIsIdempotent` (it pins registration order today; the contract is now reverse activation order):

```java
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
```

2. Add:

```java
    @Test
    @DisplayName("disableAll releases reserved ids")
    void disableAllReleasesIds() {
        registry.registerModule(new RecordingModule("a", log));
        registry.disableAll();

        registry.registerModule(new RecordingModule("a", log));
        registry.disableAll();

        assertIterableEquals(List.of("enable:a", "disable:a", "enable:a", "disable:a"), log);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kernel:test --tests "org.vttale.vttale.kernel.module.SimpleModuleRegistryTest"`
Expected: FAIL — `disableAllIsIdempotent` (order is still forward), `disableAllReleasesIds` (ids are cleared, so this should already pass — if it passes, fine; the order test is the driver).

- [ ] **Step 3: Implement**

Replace the body of `disableAll`:

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :kernel:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java
git commit -m "feat(kernel): disable modules in reverse activation order"
```

---

### Task 5: Service registrations wake parked modules

**Files:**
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/VTTaleKernel.java`
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java` (add `onServiceRegistered()`)
- Test: `kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java`

**Interfaces:**
- Consumes: `drainPending()` (Task 3, private).
- Produces: `public void onServiceRegistered()` on `SimpleModuleRegistry` — kernel-internal, deliberately NOT on the `ModuleRegistry` interface. `VTTaleKernel` holds its field typed `SimpleModuleRegistry` (the `Kernel` interface still exposes `ModuleRegistry`).

- [ ] **Step 1: Write the failing test**

Add to `SimpleModuleRegistryTest.java`:

```java
    @Test
    @DisplayName("a service registered outside any module wakes parked modules")
    void directServiceRegistrationWakesParkedModule() {
        // Use the kernel's own registry: it is the one its registerService notifies.
        SimpleModuleRegistry kernelRegistry = (SimpleModuleRegistry) kernel.getModuleRegistry();
        kernelRegistry.registerModule(new RecordingModule("consumer", log, Set.of(SomeService.class)));
        assertEquals(List.of(), log);

        kernel.registerService(SomeService.class, new SomeService() {
        });

        assertIterableEquals(List.of("enable:consumer"), log);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kernel:test --tests "org.vttale.vttale.kernel.module.SimpleModuleRegistryTest"`
Expected: FAIL — `directServiceRegistrationWakesParkedModule` (nothing drains on a bare `registerService`, the consumer stays parked).

- [ ] **Step 3: Implement**

1. In `SimpleModuleRegistry.java`, add after `drainPending()`:

```java
    /**
     * Called by the kernel after a service registration: a parked module may
     * now resolve. Drains immediately when no activation or drain is in
     * progress; otherwise just marks the queue dirty for the outermost drain.
     * Kernel-internal on purpose: not part of the ModuleRegistry contract.
     */
    public void onServiceRegistered() {
        drainPending();
    }
```

2. In `VTTaleKernel.java`: change the field declaration and constructor assignment to the concrete type, notify on registration. Full new content:

```java
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
    // Concrete type: registerService must reach onServiceRegistered(), which is
    // kernel-internal and deliberately absent from the ModuleRegistry interface.
    private final SimpleModuleRegistry moduleRegistry;
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
        // A parked module may have been waiting for exactly this service.
        moduleRegistry.onServiceRegistered();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :kernel:test`
Expected: BUILD SUCCESSFUL — including `registrationOverwritesSilently` in `VTTaleKernelTest` (the notify-on-register does not change overwrite semantics).

- [ ] **Step 5: Commit**

```bash
git add kernel/src/main/java/org/vttale/vttale/kernel/VTTaleKernel.java kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java
git commit -m "feat(kernel): service registrations wake parked modules"
```

---

### Task 6: Boot-time report of still-parked modules + platform wiring

**Files:**
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java` (add `reportPendingModules()`)
- Modify: `api/src/main/java/org/vttale/vttale/api/module/ModuleRegistry.java` (javadoc only)
- Modify: `platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/VTTaleHytalePlugin.java`
- Test: `kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java`

**Interfaces:**
- Consumes: `pending`, `unresolved(Module)`, `idOf(Module)` (Tasks 2–3).
- Produces: `public synchronized void reportPendingModules()` on `SimpleModuleRegistry`; `VTTaleHytalePlugin.setup()` calls it via `instanceof` (a diagnostic must never fail the boot).

- [ ] **Step 1: Write the failing tests**

Add to `SimpleModuleRegistryTest.java`:

```java
    @Test
    @DisplayName("the boot report is a safe no-op with nothing parked")
    void reportPendingIsSafeWithNothingParked() {
        assertDoesNotThrow(registry::reportPendingModules);
    }

    @Test
    @DisplayName("the boot report runs with parked modules and activates nothing")
    void reportPendingWithParkedModulesActivatesNothing() {
        registry.registerModule(new RecordingModule("waiting", log, Set.of(SomeService.class)));

        assertDoesNotThrow(registry::reportPendingModules);
        assertEquals(List.of(), log);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kernel:test --tests "org.vttale.vttale.kernel.module.SimpleModuleRegistryTest"`
Expected: BUILD FAILED — compile error: `reportPendingModules()` does not exist.

- [ ] **Step 3: Implement**

1. In `SimpleModuleRegistry.java`, add after `onServiceRegistered()`:

```java
    /**
     * Boot-time report: logs every still-parked module at ERROR with the
     * services it waits for. Called once by the platform at the end of
     * setup(). Reports, never activates: parking is normal while plugins load,
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
```

2. In `api/src/main/java/org/vttale/vttale/api/module/ModuleRegistry.java`, replace the class javadoc (contract now covers parking, id reservation, reverse disable order, report):

```java
/**
 * Registry for managing module lifecycle.
 * <p>
 * A module is enabled immediately when every service returned by
 * {@link Module#requires()} is registered; otherwise it is parked and
 * activated when they appear. Registration order therefore does not matter
 * for service dependencies - only for event-driven bridging, which the
 * platform owns.
 * <p>
 * Each module id ({@link Module#id()}) is reserved at registration and
 * released only by {@link #disableAll()}: a module whose id is already taken
 * (enabled, parked, refused, or failed to enable) is refused.
 * <p>
 * A module that is refused, parked, or skipped after a failed
 * {@link Module#onEnable} never receives {@link Module#onDisable()}: cleaning
 * up anything it registered before throwing is its own responsibility.
 */
```

(Leave `registerModule` and `disableAll` method javadocs as they are, except `disableAll` gains one line: `Disables in reverse activation order - a dependent shuts down before its provider.`)

3. In `VTTaleHytalePlugin.java`, add the import and the report call. The `setup()` becomes:

```java
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
        if (modules instanceof org.vttale.vttale.kernel.module.SimpleModuleRegistry registry) {
            registry.reportPendingModules();
        }

        TokenRegistry tokens = kernel.getService(TokenRegistry.class);
        if (tokens != null) {
            new HytaleTokenBinder(kernel, tokens, this).initialize();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(modules::disableAll, "vttale-shutdown"));

        getLogger().at(Level.INFO).log("VTTale ready");
    }
```

(Use a proper import `org.vttale.vttale.kernel.module.SimpleModuleRegistry` at the top instead of the FQN in the `instanceof` — match the file's existing import style.)

- [ ] **Step 4: Verify**

Run: `./gradlew :kernel:test :platform:hytale:compileJava`
Expected: BUILD SUCCESSFUL — kernel tests pass; the platform bridge compiles (platform is not unit-tested per CLAUDE.md).

- [ ] **Step 5: Commit**

```bash
git add kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java api/src/main/java/org/vttale/vttale/api/module/ModuleRegistry.java platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/VTTaleHytalePlugin.java kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java
git commit -m "feat(kernel): boot-time report of still-parked modules"
```

---

### Task 7: Documentation and full verification

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- Consumes: the finished implementation (Tasks 1–6).
- Produces: module-author documentation matching the shipped behavior.

- [ ] **Step 1: Update the module lifecycle section**

In `docs/architecture.md`, replace the whole section `## Modules : cycle de vie et enregistrement` (from that heading up to but not including `## Systèmes de jeu`) with:

```markdown
## Modules : cycle de vie et enregistrement

`Module` est l'unique point d'extension : `onEnable(Kernel)` / `onDisable()`,
plus deux déclarations optionnelles :

- **`id()`** — identifiant stable, unique parmi tous les modules installés ;
  le registre refuse un doublon. Default : nom de classe pleinement qualifié.
  Overridez avec un id namespacé (`"vttale:chat"`) pour des logs lisibles.
  Une classe de module est un singleton par serveur : deux instances de la
  même classe partagent le même id par défaut, la seconde est refusée.
- **`requires()`** — services qui doivent exister avant l'activation, lus via
  `getService` dans `onEnable` (ex. `Set.of(DiceService.class)`). Vide par
  défaut.

**L'ordre d'enregistrement n'a plus d'importance pour ces dépendances** : un
module dont les services requis manquent est mis en parc et s'active au
moment où ils apparaissent. En fin de démarrage, la plateforme logge en
ERROR les modules encore en attente avec les services manquants — un module
qui ne s'active jamais a un service requis jamais arrivé.

Le couplage par événements n'est **pas** une dépendance : abonnement à
l'activation, publication à l'exécution, aucun ordre contraint. Ne déclarez
jamais un événement en `requires()`.

Deux modes d'enregistrement :

| Mode | Qui | Comment |
|---|---|---|
| **Embarqué** | le platform | `registerModule(new ChatModule())`, … — liste lisible ; seul `HytaleAdapter` doit rester premier (pont de commandes, invisible à `requires()`) |
| **Tiers** | le plugin Hytale du module | dans son `setup()` : `VTTale.getKernel().getModuleRegistry().registerModule(new MonModule())` |

Les classloaders Hytale sont isolés par JAR : aucun mécanisme de découverte
cross-JAR n'existe. L'auto-enregistrement dans `setup()` est donc le seul
chemin pour un module tiers.
```

- [ ] **Step 2: Update the error-handling section**

In the same file, add this bullet to the `## Gestion d'erreurs` list, after the `onEnable` bullet:

```markdown
- **`requires()` jamais satisfaites** : le module reste parqué (log INFO à la
  mise en parc) ; rapport ERROR en fin de démarrage listant les services
  manquants. Un module parqué ne voit jamais `onEnable` ni `onDisable`.
```

- [ ] **Step 3: Run the full verification**

Run: `./gradlew :api:test :kernel:test :module:test :gamesystem:test`
Expected: BUILD SUCCESSFUL — all module test suites green.

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — everything compiles, fat JAR produced in `platform/hytale/build/libs/`.

- [ ] **Step 4: Commit**

```bash
git add docs/architecture.md
git commit -m "docs: module requirements and id contract in architecture guide"
```

- [ ] **Step 5: In-game validation (manual, outside CI)**

Copy `platform/hytale/build/libs/VTTale-<version>-all.jar` to `%APPDATA%\Hytale\UserData\Mods`, boot a server, and check the log: `VTTale ready`, no `parked, waiting for` lines, no `still parked` report. This step is done by the human operator, not by CI.
