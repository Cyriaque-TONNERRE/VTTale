# GameSystem Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `GameSystem` into a distinct api contract (id, version, owned components) registered as a kernel service, with hard exclusivity enforced by `SimpleModuleRegistry` before `onEnable` runs.

**Architecture:** `GameSystem extends Module` in `api` (`org.vttale.vttale.api.gamesystem`). Each game system self-registers as `kernel.registerService(GameSystem.class, this)` in `onEnable` (DiceService pattern). Exclusivity is a check-then-act guard in `SimpleModuleRegistry.registerModule`, placed **before** `onEnable` so a refused system produces zero side effects (no unsubscribe/unregister APIs exist to roll back with). `registerModule` becomes `synchronized` to close the check-then-act window across plugin classloaders. `VTTaleKernel.registerService` stays permissive (`put`).

**Tech Stack:** Java 25, Gradle 9.7.1 (Kotlin DSL), JUnit 5 (configured globally in root `build.gradle.kts` for every `java` subproject).

**Spec:** `docs/superpowers/specs/2026-09-10-gamesystem-contract-design.md` (source of truth)

## Global Constraints

- Work on branch `feat/gamesystem-contract` (already created; spec commits `354fd87`, `e22d487` are on it).
- `api`, `kernel`, `gamesystem`, `module` must never import `com.hypixel.*` or `org.joml.*`.
- Code, comments, commit messages in **English**; conventional commit style, **no `Co-Authored-By` trailer**.
- Commit only the files listed in each task — the working tree contains an unrelated modified `CLAUDE.md` that must stay out of every commit.
- JUnit 5 deps come from the root `subprojects` block; only project deps need to be declared per-module.
- Full verification: `./gradlew :api:test :kernel:test :module:test :gamesystem:test` then `./gradlew build`.
- In-game validation (copy fat JAR to `%APPDATA%\Hytale\UserData\Mods`) is a **manual user step**, performed after the merge, not by the executor.

---

### Task 1: `GameSystem` contract in `api`

**Files:**
- Create: `api/src/main/java/org/vttale/vttale/api/gamesystem/GameSystem.java`

**Interfaces:**
- Consumes: `org.vttale.vttale.api.module.Module` (existing), `org.vttale.vttale.api.token.TokenComponent` (existing).
- Produces: `interface GameSystem extends Module` with `String id()`, `String version()`, `Set<Class<? extends TokenComponent>> components()`. Tasks 2 and 3 import exactly this type from `org.vttale.vttale.api.gamesystem`.

No api test: the contract has no behavior of its own (no default methods, no state); it is exercised behaviorally by the kernel and gamesystem tests in Tasks 2–3. Do not invent a fake-implementation test here.

- [ ] **Step 1: Create the interface**

```java
package org.vttale.vttale.api.gamesystem;

import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.token.TokenComponent;

import java.util.Set;

/**
 * A ruleset (D&D 5e, Pathfinder 2e, ...) as a {@link Module} carrying its
 * own identity. A game system is a module with lifecycle, commands and
 * event subscriptions, plus metadata that lets the kernel answer
 * "which ruleset is active?".
 * <p>
 * Exactly one game system may be active per server: {@code SimpleModuleRegistry}
 * refuses a second one before its {@code onEnable} runs. Consumers read the
 * active system with {@code kernel.getService(GameSystem.class)}.
 */
public interface GameSystem extends Module {

    /** Stable ruleset identifier, e.g. {@code "dnd5e"}. Namespace it if collisions ever matter. */
    String id();

    /** Ruleset version. */
    String version();

    /**
     * Token component types this system contributes.
     * <p>
     * Informational only: no kernel behaviour reads this today. It becomes load-bearing
     * when several systems can coexist, or when persistence needs to route codecs.
     */
    Set<Class<? extends TokenComponent>> components();
}
```

- [ ] **Step 2: Compile and run the api tests**

Run: `./gradlew :api:compileJava :api:test`
Expected: BUILD SUCCESSFUL, all existing tests pass (no new test in this task).

- [ ] **Step 3: Commit**

```bash
git add api/src/main/java/org/vttale/vttale/api/gamesystem/GameSystem.java
git commit -m "feat(api): add GameSystem contract"
```

---

### Task 2: Exclusivity guard in `SimpleModuleRegistry` (kernel)

**Files:**
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java`
- Test: `kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java`

**Interfaces:**
- Consumes: `org.vttale.vttale.api.gamesystem.GameSystem` (Task 1), existing `VTTaleKernel.getService/registerService`.
- Produces: `SimpleModuleRegistry.registerModule` is now `synchronized` and refuses a second `GameSystem` when `kernel.getService(GameSystem.class)` is already set — logging `A game system is already active (<id>); refusing <class>` and returning without calling `onEnable`. No other signature changes.

- [ ] **Step 1: Add the test double and three failing tests**

Append the stub class inside `SimpleModuleRegistryTest` (next to `RecordingModule`, same style) and the three tests below:

```java
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
```

```java
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
```

Add these imports to the existing import block of the test file:

```java
import org.vttale.vttale.api.gamesystem.GameSystem;
import org.vttale.vttale.api.token.TokenComponent;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertNull;
```

- [ ] **Step 2: Run the tests, verify the new ones fail**

Run: `./gradlew :kernel:test --tests "org.vttale.vttale.kernel.module.SimpleModuleRegistryTest"`
Expected: FAIL — `secondGameSystemIsRefusedBeforeInit` fails because the guard does not exist yet (`second`'s `onEnable` runs, log contains `enable:pf2e`). The two other new tests pass already; the existing six must still pass.

- [ ] **Step 3: Implement the guard**

Rewrite `registerModule` in `SimpleModuleRegistry` (add import `org.vttale.vttale.api.gamesystem.GameSystem`; everything else in the file unchanged):

```java
    @Override
    public synchronized void registerModule(Module module) {
        if (modules.contains(module)) {
            return;
        }
        // Exclusivity: at most one GameSystem per server. Refused BEFORE onEnable, so the
        // rejected module produces no side effect at all - nothing to roll back.
        if (module instanceof GameSystem candidate) {
            GameSystem active = kernel.getService(GameSystem.class);
            if (active != null) {
                LOGGER.log(Level.ERROR, "A game system is already active (" + active.id()
                        + "); refusing " + candidate.getClass().getName());
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
        modules.add(module);
    }
```

`synchronized` closes the check-then-act window: two plugins self-registering from different threads must not both pass the null check and activate two systems. `onEnable` under the lock is fine — startup path, never hot; the lock is reentrant, so a module registering another module stays correct.

- [ ] **Step 4: Run the full kernel suite**

Run: `./gradlew :kernel:test`
Expected: BUILD SUCCESSFUL — the three new tests pass, and all pre-existing tests still pass, notably `failingModuleIsSkipped` and `duplicateInstanceIsIgnored`. `VTTaleKernelTest#registrationOverwritesSilently` is untouched and must still pass (`registerService` stays a plain `put`).

- [ ] **Step 5: Commit**

```bash
git add kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java
git commit -m "feat(kernel): refuse a second GameSystem before it initializes"
```

---

### Task 3: `DND5EGameSystem` implements the contract (gamesystem)

**Files:**
- Modify: `gamesystem/src/main/java/org/vttale/vttale/gamesystem/dnd5e/DND5EGameSystem.java`
- Modify: `gamesystem/build.gradle.kts`
- Test: Create `gamesystem/src/test/java/org/vttale/vttale/gamesystem/dnd5e/DND5EGameSystemTest.java`

**Interfaces:**
- Consumes: `GameSystem` (Task 1), `VTTaleKernel` as test-only dependency.
- Produces: `DND5EGameSystem` active as `kernel.getService(GameSystem.class)` after `onEnable`, with `id() = "dnd5e"`, `version() = "1.0"`, `components()` returning an empty set (no dnd5e `TokenComponent` exists yet; first candidates: `StatBlock`, character sheet).

- [ ] **Step 1: Add the test-only kernel dependency**

`gamesystem/build.gradle.kts` becomes (mirrors `module/build.gradle.kts`):

```kotlin
plugins {
    id("java")
}

dependencies {
    implementation(project(":api"))

    // Test-only: the gamesystem tests wire the module onto a real kernel.
    // This edge intentionally does NOT exist in the production dependency graph.
    testImplementation(project(":kernel"))
}
```

- [ ] **Step 2: Write the failing test**

```java
package org.vttale.vttale.gamesystem.dnd5e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.gamesystem.GameSystem;
import org.vttale.vttale.kernel.VTTaleKernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DND5EGameSystemTest {

    private Kernel kernel;
    private DND5EGameSystem system;

    @BeforeEach
    void setUp() {
        kernel = new VTTaleKernel();
        system = new DND5EGameSystem();
    }

    @Test
    @DisplayName("onEnable registers the system under GameSystem.class")
    void registersItselfAsService() {
        system.onEnable(kernel);

        assertSame(system, kernel.getService(GameSystem.class));
        // Services are keyed by the exact class passed at registration.
        assertNull(kernel.getService(DND5EGameSystem.class));
    }

    @Test
    @DisplayName("carries its identity")
    void identity() {
        assertEquals("dnd5e", system.id());
        assertEquals("1.0", system.version());
    }

    @Test
    @DisplayName("owns no component type yet")
    void ownsNoComponents() {
        assertTrue(system.components().isEmpty());
    }
}
```

- [ ] **Step 3: Run it, verify it fails**

Run: `./gradlew :gamesystem:test`
Expected: COMPILATION ERROR — `DND5EGameSystem` does not implement `GameSystem` / does not override `id()`.

- [ ] **Step 4: Implement**

Replace the whole content of `DND5EGameSystem.java`:

```java
package org.vttale.vttale.gamesystem.dnd5e;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.gamesystem.GameSystem;
import org.vttale.vttale.api.token.TokenComponent;

import java.util.Set;

/**
 * Example game system: the D&D 5e ruleset skeleton. Gameplay (StatBlock,
 * character sheet components, behaviors) is added on top of this contract.
 */
public class DND5EGameSystem implements GameSystem {

    @Override
    public String id() {
        return "dnd5e";
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
        // DiceService pattern: the capability is a service registered by its module.
        kernel.registerService(GameSystem.class, this);
    }
}
```

- [ ] **Step 5: Run the gamesystem tests, verify they pass**

Run: `./gradlew :gamesystem:test`
Expected: BUILD SUCCESSFUL, 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add gamesystem/build.gradle.kts gamesystem/src/main/java/org/vttale/vttale/gamesystem/dnd5e/DND5EGameSystem.java gamesystem/src/test/java/org/vttale/vttale/gamesystem/dnd5e/DND5EGameSystemTest.java
git commit -m "feat(gamesystem): DND5E as the first GameSystem"
```

---

### Task 4: Documentation + full verification

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- Consumes: nothing. Produces: updated module-author documentation matching the shipped behavior.

- [ ] **Step 1: Add the "Systèmes de jeu" section**

In `docs/architecture.md`, insert this section **after** « Modules : cycle de vie et enregistrement » (before « Bus d'événements »):

```markdown
## Systèmes de jeu

Un système de jeu (D&D 5e, Pathfinder 2e, …) est un `GameSystem` :
un `Module` qui porte en plus son identité — `id()`, `version()`,
`components()` (types de composants possédés, informatif pour l'instant).

**Un seul actif par serveur.** `SimpleModuleRegistry` refuse un second
`GameSystem` **avant** son `onEnable` : zéro effet de bord, log d'erreur,
le serveur démarre. Le contrat est lu ainsi :

- système actif : `kernel.getService(GameSystem.class)` ;
- enregistrement : le système fait `kernel.registerService(GameSystem.class, this)`
  dans son `onEnable` (pattern DiceService).

Écrire un système de jeu = écrire un module qui implémente `GameSystem`
au lieu de `Module` ; le reste (commandes, events, behaviors, services)
ne change pas.
```

- [ ] **Step 2: Update the error-handling section**

In « Gestion d'erreurs », add this bullet after the `Module.onEnable` one:

```markdown
- **Second `GameSystem` refusé** : refusé avant tout effet de bord (pas
  d'`onEnable` du tout), log d'erreur, le premier système reste actif.
```

- [ ] **Step 3: Full test + build verification**

Run: `./gradlew :api:test :kernel:test :module:test :gamesystem:test`
Expected: BUILD SUCCESSFUL, zero failures.

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (compiles the platform too, produces the fat JAR).

- [ ] **Step 4: Commit**

```bash
git add docs/architecture.md
git commit -m "docs: game systems as exclusive GameSystem modules"
```

---

## After the plan (not executor tasks)

- In-game validation is a **manual user step**: copy `platform/hytale/build/libs/VTTale-<version>-all.jar` to `%APPDATA%\Hytale\UserData\Mods` and boot a server. Expected: startup log shows dnd5e enabled, `/roll` etc. still work.
- Branch finishing (`--no-ff` merge to `main`, push, delete branch) goes through superpowers:finishing-a-development-branch.
