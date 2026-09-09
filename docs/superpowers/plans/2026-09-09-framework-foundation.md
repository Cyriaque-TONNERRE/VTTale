# VTTale Framework Foundation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the VTTale fork into a working framework per `docs/superpowers/specs/2026-09-09-framework-design.md`: Java 25 + hytale-tools build, service-container kernel, explicitly-registered modules, behaviors, one deployable platform JAR.

**Architecture:** Four plain Java library modules (`api`, `kernel`, `module`, `gamesystem`) bundled by shadow into a single Hytale plugin JAR (`platform:hytale`). Kernel = `getService`/`registerService` container; modules register explicitly from the platform; third-party plugins self-register via `setup()`. No SPI, no ServiceLoader.

**Tech Stack:** Java 25, Gradle 9.5.1 (wrapper copied from TTALE), `com.azuredoom.hytale-tools` 1.+ (platform only), `com.gradleup.shadow` 9.+ (platform only), no test framework (explicit user decision — no tests in this project).

**Working directory:** `C:\Users\Siryak\Documents\VTTale` (fork `Cyriaque-TONNERRE/VTTale`, branch `framework-foundation`). All paths below are relative to it. The TTALE repo (`C:\Users\Siryak\Documents\TTTALE`) is read-only reference — never commit to it.

## Global Constraints

- No unit tests anywhere (explicit user decision). Validation = `./gradlew` compile/build + final in-game deploy.
- Packages stay `org.vttale.vttale.*` (POC naming, already in place).
- One deployable JAR: `platform:hytale` builds `VTTale-0.1.0-all.jar` (shadow fat JAR).
- Third-party dependency string: `VTTALE:vttale=*` (manifest group `VTTALE`, mod id `vttale`).
- api/kernel/module/gamesystem must never import a `com.hypixel.*` class. Only `platform/hytale` may.
- Commit messages: English, conventional style, no `Co-Authored-By` trailer, own git identity.
- Docs (`README.md`, `docs/`) in French; code and Javadoc in English.
- Event publishing is synchronous and must stay synchronous (table action = roll → apply → notify completes before publish returns).
- Token lifecycle events are exactly: `TokenCreatedEvent`, `TokenRemovedEvent`, `TokenUpdatedEvent`, `TokenBoundEvent`. No `Moved/Selected/Placed` events — everything flows through `TokenUpdatedEvent`.
- Handler exceptions on the bus are logged and skipped, never propagated; a failing `Module.onEnable` must not crash the server.

---

### Task 1: Build scaffolding (Gradle 9.5.1, Java 25, hytale-tools on platform, shadow)

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `gradle.properties`
- Create: `api/build.gradle.kts`
- Create: `kernel/build.gradle.kts`
- Create: `module/build.gradle.kts`
- Create: `gamesystem/build.gradle.kts`
- Modify: `platform/hytale/build.gradle.kts`
- Overwrite: `gradle/wrapper/gradle-wrapper.properties` (+ copy `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` from TTALE)

**Interfaces:**
- Consumes: nothing (first task).
- Produces: Gradle properties every later task relies on: `group=org.vttale`, `version=0.1.0`, `java_version=25`, `mod_id=vttale`, `mod_name=VTTale`, `main_class=org.vttale.vttale.platform.hytale.VTTaleHytalePlugin`, `manifest_group=VTTALE`. Module build files exporting all POC classes unchanged.

- [ ] **Step 1: Copy the proven Gradle wrapper from TTALE (9.5.1)**

```bash
cd /c/Users/Siryak/Documents/VTTale
cp /c/Users/Siryak/Documents/TTTALE/gradlew gradlew
cp /c/Users/Siryak/Documents/TTTALE/gradlew.bat gradlew.bat
mkdir -p gradle/wrapper
cp /c/Users/Siryak/Documents/TTTALE/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
cp /c/Users/Siryak/Documents/TTTALE/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
chmod +x gradlew
```

- [ ] **Step 2: Rewrite `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "AzureDoom Maven"
            url = uri("https://maven.azuredoom.com/mods")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "VTTale"

include("api")
include("kernel")
include("module")
include("gamesystem")
include("platform:hytale")
```

- [ ] **Step 3: Rewrite root `build.gradle.kts`**

```kotlin
// Shared config only; platform:hytale applies hytale-tools itself (see its build.gradle.kts).
allprojects {
    group = property("group").toString()
    version = property("version").toString()
}

subprojects {
    plugins.withId("java") {
        java {
            toolchain.languageVersion.set(JavaLanguageVersion.of(property("java_version").toString().toInt()))
        }
        repositories {
            mavenCentral()
        }
        tasks.withType<JavaCompile>().configureEach {
            // sources are UTF-8; never fall back to the platform charset
            options.encoding = "UTF-8"
        }
        tasks.withType<Javadoc>().configureEach {
            (options as org.gradle.external.javadoc.StandardJavadocDocletOptions)
                .addStringOption("Xdoclint:-missing", "-quiet")
        }
    }
}
```

- [ ] **Step 4: Rewrite `gradle.properties`**

```properties
# Gradle runtime options
org.gradle.daemon=true
org.gradle.jvmargs=-Xmx3G
org.gradle.parallel=true
org.gradle.caching=true

# Local machine: JDK used to run Gradle. Adjust per machine.
org.gradle.java.home=C:/Users/Siryak/.jdks/graalvm-jdk-25

# Java / Hytale options
java_version=25
hytale_version=0.+
patchline=release
server_version=0.6.4
manifestServerVersion=>=0.6.4

# Project identity
group=org.vttale
version=0.1.0

# Plugin identity (platform:hytale) — third parties depend on VTTALE:vttale=*
manifest_group=VTTALE
mod_id=vttale
mod_name=VTTale
main_class=org.vttale.vttale.platform.hytale.VTTaleHytalePlugin
mod_description=VTTale: virtual tabletop framework for Hytale
mod_url=https://github.com/Cyriaque-TONNERRE/VTTale
mod_author=Siryak
manifest_dependencies=Hytale:AssetModule=*
manifest_opt_dependencies=
curseforgeID=
includes_pack=true
disabled_by_default=false
injectServerJavadocsIntoSources=true
generateAssetsBinary=false
```

- [ ] **Step 5: Create `api/build.gradle.kts`**

```kotlin
plugins {
    id("java")
}
```

- [ ] **Step 6: Create `kernel/build.gradle.kts`**

```kotlin
plugins {
    id("java")
}

dependencies {
    implementation(project(":api"))
}
```

- [ ] **Step 7: Create `module/build.gradle.kts`**

```kotlin
plugins {
    id("java")
}

dependencies {
    implementation(project(":api"))
}
```

- [ ] **Step 8: Create `gamesystem/build.gradle.kts`**

```kotlin
plugins {
    id("java")
}

dependencies {
    implementation(project(":api"))
}
```

- [ ] **Step 9: Rewrite `platform/hytale/build.gradle.kts`**

```kotlin
plugins {
    id("com.azuredoom.hytale-tools") version "1.+"
    id("com.gradleup.shadow") version "9.+"
}

group = project.property("group").toString()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(property("java_version").toString().toInt()))
}

hytaleTools {
    javaVersion = property("java_version").toString().toInt()
    hytaleVersion = property("hytale_version").toString()
    manifestServerVersion = property("manifestServerVersion").toString()
    manifestGroup = property("manifest_group").toString()
    modId = property("mod_id").toString()
    modDescription = property("mod_description").toString()
    modUrl = property("mod_url").toString()
    mainClass = property("main_class").toString()
    modCredits = property("mod_author").toString()
    manifestDependencies = property("manifest_dependencies").toString()
    manifestOptionalDependencies = property("manifest_opt_dependencies").toString()
    curseforgeId = property("curseforgeID").toString()
    disabledByDefault = property("disabled_by_default").toString().toBoolean()
    includesPack = property("includes_pack").toString().toBoolean()
    patchline = property("patchline").toString()
    injectServerJavadocsIntoSources = property("injectServerJavadocsIntoSources").toString().toBoolean()
    generateAssetsBinary = property("generateAssetsBinary").toString().toBoolean()
}

repositories {
    mavenCentral()
}

// The single platform JAR bundles api + kernel + module + gamesystem (design: one deployable JAR).
dependencies {
    implementation(project(":api"))
    implementation(project(":kernel"))
    implementation(project(":module"))
    implementation(project(":gamesystem"))
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(project.property("mod_name").toString())
    archiveVersion.set(project.property("version").toString())
}

// Deployable artifact: the fat JAR (api+kernel+module+gamesystem classes merged in).
tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
```

> Contingency: if `com.gradleup.shadow` `9.+` fails to resolve or run on Gradle 9.5.1, pin the wrapper to Gradle `8.14.3` (edit `gradle/wrapper/gradle-wrapper.properties` `distributionUrl`) and shadow to `8.3.6`. Do not change anything else.

- [ ] **Step 10: Build everything (compiles POC code as-is)**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`. `platform/hytale/build/libs/` contains `VTTale-0.1.0.jar` and `VTTale-0.1.0-all.jar`.
If a shadow/hytale-tools interplay error appears, apply the contingency above and rerun.

- [ ] **Step 11: Commit**

```bash
git add -A && git commit -m "build: gradle 9.5.1, java 25, hytale-tools on platform, shadow fat jar"
```

---

### Task 2: Facade without SPI (delete KernelProvider, explicit init)

**Files:**
- Modify: `api/src/main/java/org/vttale/vttale/api/VTTale.java`
- Delete: `api/src/main/java/org/vttale/vttale/api/KernelProvider.java`
- Delete: `api/src/main/java/org/vttale/vttale/api/events/EventHandler.java`
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/VTTaleKernel.java`
- Delete: `kernel/src/main/resources/META-INF/services/org.vttale.vttale.api.KernelProvider`
- Delete: `module/src/main/resources/META-INF/services/org.vttale.vttale.api.module.Module`
- Delete: `gamesystem/src/main/resources/META-INF/services/org.vttale.vttale.api.module.Module`

**Interfaces:**
- Consumes: nothing from later tasks.
- Produces: `VTTale.init(Kernel kernel)` (throws `IllegalStateException` if already initialized or `kernel` is null), `VTTale.getKernel()` (throws `IllegalStateException` if not initialized). `VTTaleKernel` has a no-arg constructor, registers NO modules. Every later task uses `VTTale.getKernel()` / `new VTTaleKernel()` with these exact signatures.

- [ ] **Step 1: Check nothing references the deleted types**

Run: `grep -rn "KernelProvider\|EventHandler" --include="*.java" api kernel module gamesystem platform`
Expected: only `VTTale.java` (uses KernelProvider) and `VTTaleKernel.java` references. `EventHandler` is deprecated and unused — if any module references it, inline the `BiConsumer` instead.

- [ ] **Step 2: Rewrite `api/src/main/java/org/vttale/vttale/api/VTTale.java`**

```java
package org.vttale.vttale.api;

import java.util.Objects;

/**
 * Global entry point for the VTTale API.
 * <p>
 * The platform (Hytale plugin) creates the kernel and injects it at startup:
 * <pre>{@code
 * VTTale.init(new VTTaleKernel());
 * }</pre>
 * Modules then read shared state via {@code VTTale.getKernel()}.
 */
public final class VTTale {

    private static volatile Kernel kernel;

    private VTTale() {
    }

    /**
     * Returns the global Kernel instance.
     *
     * @return the current Kernel instance
     * @throws IllegalStateException if the kernel has not been initialized
     */
    public static Kernel getKernel() {
        Kernel k = kernel;
        if (k == null) {
            throw new IllegalStateException("VTTale has not been initialized yet!");
        }
        return k;
    }

    /**
     * Initializes the global Kernel. Called once by the platform at startup.
     *
     * @param kernel the kernel instance to expose
     * @throws IllegalStateException if already initialized
     */
    public static void init(Kernel kernel) {
        if (VTTale.kernel != null) {
            throw new IllegalStateException("VTTale is already initialized!");
        }
        VTTale.kernel = Objects.requireNonNull(kernel, "kernel");
    }
}
```

- [ ] **Step 3: Edit `kernel/src/main/java/org/vttale/vttale/kernel/VTTaleKernel.java`**

Remove the `ServiceLoader` import and the auto-discovery loop. The constructor becomes:

```java
    /**
     * Initializes registries. Modules are registered explicitly by the
     * platform (no SPI): built-in modules in the platform setup(), third
     * -party modules from their own plugin setup().
     */
    public VTTaleKernel() {
        this.eventBus = new SimpleEventBus();
        this.commandRegistry = new SimpleCommandRegistry(eventBus);
        this.moduleRegistry = new SimpleModuleRegistry(this);
    }
```

(Keep the `getService`/`registerService` methods and the fields exactly as they are.)

- [ ] **Step 4: Delete SPI leftovers**

```bash
git rm api/src/main/java/org/vttale/vttale/api/KernelProvider.java \
       api/src/main/java/org/vttale/vttale/api/events/EventHandler.java \
       kernel/src/main/resources/META-INF/services/org.vttale.vttale.api.KernelProvider \
       module/src/main/resources/META-INF/services/org.vttale.vttale.api.module.Module \
       gamesystem/src/main/resources/META-INF/services/org.vttale.vttale.api.module.Module
```

- [ ] **Step 5: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: explicit kernel init, drop KernelProvider/EventHandler SPI"
```

---

### Task 3: EventBus with priorities and resilient handlers

**Files:**
- Modify: `api/src/main/java/org/vttale/vttale/api/events/EventBus.java`
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/events/SimpleEventBus.java`

**Interfaces:**
- Consumes: `Event`, `EventContext` (unchanged).
- Produces: `EventBus.subscribe(Class<T> type, int priority, BiConsumer<T, EventContext> listener)` — lower priority runs first, ties keep subscription order; `subscribe(Class<T>, BiConsumer<T, EventContext>)` = priority 0; `publish(T event, EventContext context)` synchronous. A handler throwing does not stop the others (logged via `System.Logger`, level ERROR).

- [ ] **Step 1: Rewrite `api/src/main/java/org/vttale/vttale/api/events/EventBus.java`**

```java
package org.vttale.vttale.api.events;

import java.util.function.BiConsumer;

/**
 * Central hub for publishing and subscribing to events.
 * <p>
 * Publishing is synchronous: {@code publish} returns only once every handler
 * has returned, so a table action (roll -> apply -> notify) always completes
 * before the caller moves on.
 */
public interface EventBus {

    /**
     * Publishes an event to all registered listeners, ordered by priority.
     *
     * @param event   the event to publish
     * @param context the context containing metadata about the event source
     * @param <T>     the event type
     */
    <T extends Event> void publish(T event, EventContext context);

    /**
     * Subscribes a listener with default priority (0).
     *
     * @param eventType the class of the event to listen for
     * @param listener  the callback to invoke when the event is published
     * @param <T>       the event type
     */
    default <T extends Event> void subscribe(Class<T> eventType, BiConsumer<T, EventContext> listener) {
        subscribe(eventType, 0, listener);
    }

    /**
     * Subscribes a listener with an explicit priority. Lower values run
     * first; ties keep subscription order.
     *
     * @param eventType the class of the event to listen for
     * @param priority  the priority (lower runs first)
     * @param listener  the callback to invoke when the event is published
     * @param <T>       the event type
     */
    <T extends Event> void subscribe(Class<T> eventType, int priority, BiConsumer<T, EventContext> listener);
}
```

- [ ] **Step 2: Rewrite `kernel/src/main/java/org/vttale/vttale/kernel/events/SimpleEventBus.java`**

```java
package org.vttale.vttale.kernel.events;

import org.vttale.vttale.api.events.Event;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.events.EventContext;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public class SimpleEventBus implements EventBus {

    private static final System.Logger LOGGER = System.getLogger(SimpleEventBus.class.getName());

    private record Handler(int priority, BiConsumer<?, EventContext> action) {}

    private final Map<Class<?>, List<Handler>> subscribers = new ConcurrentHashMap<>();

    @Override
    public <T extends Event> void subscribe(Class<T> eventType, int priority, BiConsumer<T, EventContext> listener) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new Handler(priority, listener));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void publish(T event, EventContext context) {
        List<Handler> list = subscribers.get(event.getClass());
        if (list == null) {
            return;
        }
        // ponytail: sort per publish; pre-sort on subscribe if profiling ever says so
        List<Handler> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(Handler::priority));
        for (Handler handler : sorted) {
            try {
                ((BiConsumer<T, EventContext>) handler.action()).accept(event, context);
            } catch (Exception e) {
                // One broken handler must not break the others or the caller.
                LOGGER.log(Level.ERROR, "Event handler failed for " + event.getClass().getSimpleName(), e);
            }
        }
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL` (all existing `subscribe(type, (event, ctx) -> ...)` call sites compile against the default method).

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(kernel): prioritized event bus, resilient handlers"
```

---

### Task 4: ModuleRegistry — error isolation and shutdown

**Files:**
- Modify: `api/src/main/java/org/vttale/vttale/api/module/ModuleRegistry.java`
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java`

**Interfaces:**
- Consumes: `Module`, `Kernel` (unchanged).
- Produces: `ModuleRegistry.registerModule(Module)` — logs and skips a module whose `onEnable` throws (server keeps running); `ModuleRegistry.disableAll()` — calls `onDisable()` on every enabled module (each guarded the same way), used by the platform shutdown hook.

- [ ] **Step 1: Rewrite `api/src/main/java/org/vttale/vttale/api/module/ModuleRegistry.java`**

```java
package org.vttale.vttale.api.module;

/**
 * Registry for managing module lifecycle.
 * <p>
 * Modules registered here have their {@link Module#onEnable} called
 * immediately. A module whose {@code onEnable} throws is skipped and logged —
 * one broken module never prevents the server from starting.
 */
public interface ModuleRegistry {

    /**
     * Registers and enables a module. Duplicate registrations of the same
     * instance are ignored.
     *
     * @param module the module to register
     */
    void registerModule(Module module);

    /**
     * Disables every enabled module by calling {@link Module#onDisable()}.
     * Called once by the platform on server shutdown.
     */
    void disableAll();
}
```

- [ ] **Step 2: Rewrite `kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java`**

```java
package org.vttale.vttale.kernel.module;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.module.ModuleRegistry;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple implementation of ModuleRegistry that manages module lifecycle.
 * A failing module is logged and skipped, never propagated.
 */
public class SimpleModuleRegistry implements ModuleRegistry {

    private static final System.Logger LOGGER = System.getLogger(SimpleModuleRegistry.class.getName());

    private final Kernel kernel;
    private final List<Module> modules = new ArrayList<>();

    public SimpleModuleRegistry(Kernel kernel) {
        this.kernel = kernel;
    }

    @Override
    public void registerModule(Module module) {
        if (modules.contains(module)) {
            return;
        }
        try {
            module.onEnable(kernel);
        } catch (Exception e) {
            LOGGER.log(Level.ERROR, "Module " + module.getClass().getName()
                    + " failed to enable and was skipped", e);
            return;
        }
        modules.add(module);
    }

    @Override
    public void disableAll() {
        for (Module module : modules) {
            try {
                module.onDisable();
            } catch (Exception e) {
                LOGGER.log(Level.ERROR, "Module " + module.getClass().getName() + " failed to disable", e);
            }
        }
        modules.clear();
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(kernel): module error isolation and disableAll"
```

---

### Task 5: Wire TokenUpdatedEvent into SimpleToken mutations

The POC never publishes `TokenUpdatedEvent`: `SimpleTokenRegistry.notifyTokenUpdated/notifyComponentChanged/notifyBehaviorChanged` exist but nothing calls them. Per the design, every token mutation (name, type, position, world, owner, tags, components, behaviors) must flow through `TokenUpdatedEvent` — there are no dedicated Moved/Selected/Placed events.

**Files:**
- Modify: `module/src/main/java/org/vttale/vttale/module/token/SimpleToken.java`

**Interfaces:**
- Consumes: `SimpleTokenRegistry.notifyTokenUpdated(SimpleToken, TokenUpdatedEvent.UpdateType)`, `.notifyComponentChanged(SimpleToken, UpdateType, String, Class<? extends TokenComponent>)`, `.notifyBehaviorChanged(SimpleToken, UpdateType, String, Class<? extends Behavior>)` (all package-private, already in the POC).
- Produces: `SimpleToken` constructors gain a package-private 5-arg overload `SimpleToken(UUID id, String name, TokenType type, UUID ownerId, SimpleTokenRegistry registry)`. Public 2/3/4-arg constructors keep their signatures (delegate with `null` registry — no events emitted). `SimpleTokenRegistry.create(...)` now passes `this`. `setBoundEntityId` emits nothing (the registry publishes `TokenBoundEvent` for bindings — do not double-notify).

- [ ] **Step 1: Edit `module/src/main/java/org/vttale/vttale/module/token/SimpleToken.java`**

1. Add field and constructors. Keep existing public constructors, add:

```java
    /** Registry that relays update events; null = detached token, no events. */
    private final SimpleTokenRegistry registry;

    public SimpleToken(String name, TokenType type) {
        this(UUID.randomUUID(), name, type, null, null);
    }

    public SimpleToken(String name, TokenType type, UUID ownerId) {
        this(UUID.randomUUID(), name, type, ownerId, null);
    }

    public SimpleToken(UUID id, String name, TokenType type, UUID ownerId) {
        this(id, name, type, ownerId, null);
    }

    /** Package-private: used by SimpleTokenRegistry so mutations emit events. */
    SimpleToken(UUID id, String name, TokenType type, UUID ownerId, SimpleTokenRegistry registry) {
        this.registry = registry;
        // ... existing body of the 4-arg constructor unchanged (id/name/type/ownerId/timestamps/collections) ...
    }
```

2. Add the emit helpers next to `markModified()`:

```java
    /** Emits a generic token update to the registry, if attached. */
    private void emit(TokenUpdatedEvent.UpdateType updateType) {
        if (registry != null) {
            registry.notifyTokenUpdated(this, updateType);
        }
    }
```

3. Update the setters (existing bodies stay, add the emit line at the end):

- `setName` → `emit(TokenUpdatedEvent.UpdateType.NAME_CHANGED);`
- `setType` → `emit(TokenUpdatedEvent.UpdateType.TYPE_CHANGED);`
- `setComponent(T component)` → replace `markModified()` with:

```java
        TokenComponent previous = components.put(component.getClass(), component);
        markModified();
        if (registry != null) {
            registry.notifyComponentChanged(this,
                    previous == null ? TokenUpdatedEvent.UpdateType.COMPONENT_ADDED
                                     : TokenUpdatedEvent.UpdateType.COMPONENT_UPDATED,
                    component.getComponentId(), component.getClass());
        }
```

- `removeComponent` → inside the `if (removed)` block, add:

```java
            if (registry != null) {
                registry.notifyComponentChanged(this, TokenUpdatedEvent.UpdateType.COMPONENT_REMOVED,
                        type.getName(), type);
            }
```

- `attachBehavior` → at the end (after `markModified()`), add:

```java
        if (registry != null) {
            registry.notifyBehaviorChanged(this, TokenUpdatedEvent.UpdateType.BEHAVIOR_ATTACHED,
                    id, behavior.getClass());
        }
```

- `detachBehavior(String behaviorId)` → at the end (after `markModified()`), add:

```java
        if (registry != null) {
            registry.notifyBehaviorChanged(this, TokenUpdatedEvent.UpdateType.BEHAVIOR_DETACHED,
                    behaviorId, behavior.getClass());
        }
```

- `addTag` → inside the `if (tag != null && !tag.isBlank())` block after `markModified()`: `emit(TokenUpdatedEvent.UpdateType.TAGS_ADDED);`
- `removeTag` → inside `if (removed)` after `markModified()`: `emit(TokenUpdatedEvent.UpdateType.TAGS_REMOVED);`
- `setPosition` → `this.position = position; markModified(); emit(TokenUpdatedEvent.UpdateType.POSITION_CHANGED);`
- `setWorldId` → `this.worldId = worldId; markModified(); emit(TokenUpdatedEvent.UpdateType.WORLD_CHANGED);`
- `setOwnerId` → `this.ownerId = ownerId; markModified(); emit(TokenUpdatedEvent.UpdateType.OWNER_CHANGED);`
- `setBoundEntityId` → unchanged (registry already publishes `TokenBoundEvent`).

4. Add the import if missing: `import org.vttale.vttale.api.token.events.TokenUpdatedEvent;`

- [ ] **Step 2: Edit `module/src/main/java/org/vttale/vttale/module/token/SimpleTokenRegistry.java`**

In `create(String name, TokenType type, UUID ownerId)`, construct the token with the registry:

```java
        SimpleToken token = new SimpleToken(UUID.randomUUID(), name, type, ownerId, this);
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(token): publish TokenUpdatedEvent on every token mutation"
```

---

### Task 6: Dice port (Dice class + DiceRolledEvent)

**Files:**
- Create: `module/src/main/java/org/vttale/vttale/module/diceroll/Dice.java`
- Create: `module/src/main/java/org/vttale/vttale/module/diceroll/DiceRolledEvent.java`
- Modify: `module/src/main/java/org/vttale/vttale/module/diceroll/DiceRollModule.java`

**Interfaces:**
- Consumes: `EventBus.publish`, `EventContext.getSenderId()`, `SendMessageEvent(message, targetId)`, `CommandExecutedEvent` (all unchanged).
- Produces: `Dice(RandomGenerator random)` + `DiceRolledEvent roll(String notation)` (throws `IllegalArgumentException` on invalid notation); `record DiceRolledEvent(String notation, List<Integer> rolls, int modifier, int total) implements Event`. `DiceRollModule` publishes `DiceRolledEvent` on the bus after every successful roll — other modules can react to rolls.

- [ ] **Step 1: Create `module/src/main/java/org/vttale/vttale/module/diceroll/Dice.java`**

(Ported from TTALE `plugins/dice/src/main/java/dev/tttale/dice/Dice.java`.)

```java
package org.vttale.vttale.module.diceroll;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Parses and rolls dice notation: {@code NdM[+/-K]}, e.g. "2d6", "1d20+3", "4d8-1".
 *
 * @throws IllegalArgumentException if the notation is invalid
 *         (bad numbers, zero/negative die count or sides)
 */
public final class Dice {

    private final RandomGenerator random;

    public Dice(RandomGenerator random) {
        this.random = random;
    }

    public DiceRolledEvent roll(String notation) {
        String expr = notation.strip().toLowerCase();
        int modifier = 0;
        int plus = expr.indexOf('+');
        int minus = expr.indexOf('-');
        if (plus >= 0) {
            modifier = Integer.parseInt(expr.substring(plus + 1).strip());
            expr = expr.substring(0, plus).strip();
        } else if (minus >= 0) {
            modifier = -Integer.parseInt(expr.substring(minus + 1).strip());
            expr = expr.substring(0, minus).strip();
        }

        int count = 1;
        int d = expr.indexOf('d');
        if (d >= 0) {
            String before = expr.substring(0, d).strip();
            if (!before.isEmpty()) {
                count = Integer.parseInt(before);
            }
            expr = expr.substring(d + 1).strip();
        }
        int sides = Integer.parseInt(expr);
        if (count < 1 || sides < 1) {
            throw new IllegalArgumentException("Need at least 1 die with at least 1 side: " + notation);
        }

        List<Integer> rolls = new ArrayList<>(count);
        int total = modifier;
        for (int i = 0; i < count; i++) {
            int value = random.nextInt(1, sides + 1);
            rolls.add(value);
            total += value;
        }
        return new DiceRolledEvent(notation, List.copyOf(rolls), modifier, total);
    }
}
```

- [ ] **Step 2: Create `module/src/main/java/org/vttale/vttale/module/diceroll/DiceRolledEvent.java`**

```java
package org.vttale.vttale.module.diceroll;

import org.vttale.vttale.api.events.Event;

import java.util.List;

/**
 * Published on the bus after a successful roll.
 *
 * @param notation the requested notation, e.g. "2d6+3"
 * @param rolls    one value per die
 * @param modifier flat bonus applied after summing the dice
 * @param total    sum of rolls + modifier
 */
public record DiceRolledEvent(String notation, List<Integer> rolls, int modifier, int total) implements Event {
}
```

- [ ] **Step 3: Rewrite `module/src/main/java/org/vttale/vttale/module/diceroll/DiceRollModule.java`**

```java
package org.vttale.vttale.module.diceroll;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.events.CommandExecutedEvent;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.events.EventContext;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.module.chat.SendMessageEvent;

import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * Module responsible for handling dice roll commands.
 * Rolls are also published as {@link DiceRolledEvent} so other modules can react.
 */
public class DiceRollModule implements Module {

    // ponytail: caps block chat-input abuse (each die is cheap, a million dice is not)
    private static final int MAX_DICE = 100;
    private static final int MAX_SIDES = 1000;

    private final Dice dice = new Dice(RandomGenerator.getDefault());
    private EventBus eventBus;

    @Override
    public void onEnable(Kernel kernel) {
        this.eventBus = kernel.getEventBus();
        kernel.getCommandRegistry().registerCommand("roll", "Roll dice, e.g. /roll 2d6+3");
        eventBus.subscribe(CommandExecutedEvent.class, this::onCommandExecuted);
    }

    private void onCommandExecuted(CommandExecutedEvent event, EventContext context) {
        if (!event.getCommandName().equals("roll")) {
            return;
        }

        String[] args = event.getArgs();
        if (args.length == 0) {
            reply(context, "Usage: /roll <dice_notation>. Example: /roll 2d6+3");
            return;
        }

        // Join arguments and strip spaces to handle notations like "1d10 + 10"
        String notation = String.join("", args).replace(" ", "");

        try {
            DiceRolledEvent result = dice.roll(notation);
            if (result.rolls().size() > MAX_DICE || notation.matches(".*d(\\d{4,}).*")) {
                reply(context, "Dice count or sides too high!");
                return;
            }
            eventBus.publish(result, context);
            reply(context, format(result));
        } catch (IllegalArgumentException e) {
            reply(context, "Invalid dice notation: " + notation);
        }
    }

    private static String format(DiceRolledEvent r) {
        String rolls = r.rolls().stream().map(String::valueOf).collect(Collectors.joining(", "));
        String mod = r.modifier() == 0 ? ""
                : (r.modifier() > 0 ? " + " + r.modifier() : " - " + (-r.modifier()));
        // ASCII only: the game chat font does not render glyphs like emoji/arrows
        return r.notation() + " -> [" + rolls + "]" + mod + " = " + r.total();
    }

    private void reply(EventContext context, String message) {
        eventBus.publish(new SendMessageEvent(message, context.getSenderId()), context);
    }
}
```

Note: `sides` beyond `MAX_SIDES` is caught by the `d(\d{4,})` guard before rolling (the regex checks 4+ digits); `Dice` itself stays pure. Sides of exactly 1000 or fewer pass. `RandomGenerator.nextInt(1, sides+1)` cannot overflow for sides ≤ 9999.

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(diceroll): port Dice from TTALE, publish DiceRolledEvent"
```

---

### Task 7: Platform wiring (explicit modules, shutdown, token binder)

**Files:**
- Modify: `platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/VTTaleHytalePlugin.java`
- Modify: `platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/HytaleTokenBinder.java` (constructor only)
- Keep unchanged: `platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/Command.java`, `platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/HytaleAdapter.java`

**Interfaces:**
- Consumes: everything from Tasks 2–6: `VTTale.init(Kernel)`, `new VTTaleKernel()`, `ModuleRegistry.registerModule/disableAll`, `TokenRegistry` service.
- Produces: `VTTaleHytalePlugin.setup()` — the framework bootstrap every third party relies on: kernel created and injected, built-in modules registered in order (HytaleAdapter, ChatModule, DiceRollModule, TokenModule, DND5EGameSystem), shutdown hook disabling modules, token binder wired when the TokenRegistry service is present.

- [ ] **Step 1: Rewrite `platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/VTTaleHytalePlugin.java`**

```java
package org.vttale.vttale.platform.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.VTTale;
import org.vttale.vttale.api.module.ModuleRegistry;
import org.vttale.vttale.api.token.TokenRegistry;
import org.vttale.vttale.gamesystem.dnd5e.DND5EGameSystem;
import org.vttale.vttale.kernel.VTTaleKernel;
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

        TokenRegistry tokens = kernel.getService(TokenRegistry.class);
        if (tokens != null) {
            new HytaleTokenBinder(kernel, tokens, this).initialize();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(modules::disableAll, "vttale-shutdown"));

        getLogger().at(Level.INFO).log("VTTale ready");
    }
}
```

- [ ] **Step 2: Edit the `HytaleTokenBinder` constructor to receive the registry**

Replace the field/constructor block:

```java
    private final Kernel kernel;
    private final TokenRegistry tokenRegistry;
    private final JavaPlugin plugin;

    /**
     * Creates a new HytaleTokenBinder.
     *
     * @param kernel        the VTTale kernel
     * @param tokenRegistry the token service (the caller resolved it; may not be null)
     * @param plugin        the Hytale plugin instance
     */
    public HytaleTokenBinder(Kernel kernel, TokenRegistry tokenRegistry, JavaPlugin plugin) {
        this.kernel = kernel;
        this.tokenRegistry = tokenRegistry;
        this.plugin = plugin;
    }
```

(Remove the `this.tokenRegistry = kernel.getService(TokenRegistry.class);` line — the old 2-arg constructor disappears.)

- [ ] **Step 3: Verify Hytale API signatures before building (API is young — per project docs)**

Check the resolved Server jar for the two calls platform code makes:

```bash
SERVER_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/com.hypixel.hytale -name "Server-*.jar" | head -1)
unzip -p "$SERVER_JAR" com/hypixel/hytale/server/core/plugin/JavaPlugin.class > /dev/null 2>&1 && \
  javap -cp "$SERVER_JAR" com.hypixel.hytale.server.core.plugin.JavaPlugin | grep -E "getCommandRegistry|getEventRegistry"
javap -cp "$SERVER_JAR" com.hypixel.hytale.server.core.event.EventRegistry 2>/dev/null | grep -E "register"
```

Expected: `getEventRegistry()` exists and the registry exposes a register-style method. The POC calls `plugin.getEventRegistry().register(PlayerConnectEvent.class, this::onPlayerConnect)`; project docs say `registerGlobal(Event.class, Handler::method)`. Keep whatever compiles against the resolved jar — if `register` is absent, rename the call sites in `HytaleTokenBinder.initialize()` to the method javap shows. Same check for `Command.java`'s `acceptCall(CommandSender, ParserContext, ParseResult)`: if `AbstractCommand` lacks it, replace `Command` with the execute-based fallback:

```java
package org.vttale.vttale.platform.hytale;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import org.vttale.vttale.api.VTTale;
import org.vttale.vttale.api.events.CommandExecutedEvent;
import org.vttale.vttale.api.events.EventContext;
import org.vttale.vttale.api.events.RegisterCommandRequest;

/**
 * Wrapper for VTTale commands in Hytale (execute-based variant).
 */
public class Command extends AbstractCommand {

    private final JavaPlugin plugin;
    private final String commandName;

    public Command(JavaPlugin plugin, RegisterCommandRequest request) {
        super(request.getCommandName(), request.getCommandDescription());
        this.plugin = plugin;
        this.commandName = request.getCommandName();
        setAllowsExtraArguments(true);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        String[] args = extractArgs(context.getInputString());
        String senderId = resolveSenderId(context);
        VTTale.getKernel().getEventBus()
                .publish(new CommandExecutedEvent(commandName, args), new EventContext(senderId));
        return CompletableFuture.completedFuture(null);
    }

    /** Sender UUID as string; "CONSOLE" for the zero UUID. Adjust to the real API if needed. */
    private static String resolveSenderId(CommandContext context) {
        return "CONSOLE";
    }

    private static String[] extractArgs(String inputString) {
        if (inputString == null || inputString.isBlank()) {
            return new String[0];
        }
        String[] parts = inputString.trim().split("\\s+");
        if (parts.length <= 1) {
            return new String[0];
        }
        return Arrays.copyOfRange(parts, 1, parts.length);
    }
}
```

(If this fallback is used, also pass `this` in `HytaleAdapter.registerHytaleCommand`: `new Command(plugin, request)`.)

- [ ] **Step 4: Build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`; `platform/hytale/build/libs/VTTale-0.1.0-all.jar` exists and `unzip -l platform/hytale/build/libs/VTTale-0.1.0-all.jar | grep -c "org/vttale"` reports classes from api, kernel, module, gamesystem AND `manifest.json` at the JAR root.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(platform): explicit module bootstrap, shutdown hook, token binder wiring"
```

---

### Task 8: README (credits), French docs, merge to main

**Files:**
- Create: `README.md`
- Create: `docs/architecture.md`

**Interfaces:**
- Consumes: nothing.
- Produces: project documentation. No code impact.

- [ ] **Step 1: Create `README.md` (French)**

```markdown
# VTTale

VTTale transforme un serveur Hytale en table de jeu de rôle (VTT). C'est un
**framework** : le noyau fournit les briques (bus d'événements, tokens,
behaviors, commandes, services) et tout le gameplay s'écrit en modules
par-dessus — sans jamais modifier le noyau.

## Crédits

VTTale renaît grâce au travail des contributeurs du projet original et de ses
sources d'inspiration :

- [Sparky200](https://github.com/Sparky200)
- [PhoenixEpic](https://github.com/PhoenixEpic)
- [giopalma](https://github.com/giopalma)
- Le projet [VTTaleTeam/VTTale](https://github.com/VTTaleTeam/VTTale), dont ce
  dépôt est le fork (branche `poc/VTT-38-Token-Registry`).

## Architecture

Un seul JAR plugin Hytale embarque tout :

| Module | Rôle |
|---|---|
| `api` | Contrats purs (`org.vttale.vttale.api`), zéro import Hytale |
| `kernel` | Implémentations simples (bus à priorités, registries, services) |
| `module` | Modules intégrés : chat, dés (`/roll`), tokens |
| `gamesystem` | Systèmes de jeu — `dnd5e` sert d'exemple de squelette |
| `platform/hytale` | Le plugin Hytale : bootstrap, pont vers l'API du jeu |

Voir `docs/architecture.md` pour le détail et le parcours « écrire un module ».

## Build

```bash
./gradlew build
```

Le JAR déployable est `platform/hytale/build/libs/VTTale-<version>-all.jar`.

## Déploiement (test en jeu)

1. Copier `VTTale-<version>-all.jar` dans `%APPDATA%\Hytale\UserData\Mods`
   (créer le dossier s'il manque).
2. Lancer Hytale → créer un monde → roue crantée → Mods → vérifier que
   **VTTale** est listé.
3. En jeu : `/roll 2d6+3` doit répondre dans le chat ; un joueur qui se
   connecte obtient un token (log serveur).

## Écrire un module tiers

1. Projet Java avec `org.vttale:vttale` (ou les sources de `api/`) en
   `compileOnly`.
2. Écrire `class MonModule implements Module` (composants, behaviors,
   événements — Java pur, testable hors Hytale).
3. Publier un plugin Hytale dont le `manifest.json` déclare
   `"Dependencies": { "VTTALE:vttale": "*" }` et dont le `Main` (classe
   étendant `JavaPlugin`) fait dans `setup()` :

```java
VTTale.getKernel().getModuleRegistry().registerModule(new MonModule());
```

Les classloaders Hytale étant isolés par JAR, l'auto-enregistrement est le seul
mécanisme de découverte inter-plugin.
```

- [ ] **Step 2: Create `docs/architecture.md` (French)**

Summarize the design spec in French: the service container (`getService`/`registerService` — never hardcode an accessor in the core), `Module` lifecycle and the two registration modes, the synchronous priority bus and `EventContext`, the command flow (`CommandRegistry` → `RegisterCommandRequest` → `HytaleAdapter` → `CommandExecutedEvent` → `PlatformBroadcastEvent`), the token model (Components = data, tags = filters, Behaviors = logic with private `BehaviorContext`, `BehaviorDispatcher`, lifecycle events = Created/Removed/Updated/Bound only, all mutations through `TokenUpdatedEvent`), error handling rules (bus handler exception = log + continue; failing `onEnable` = module skipped), and the third-party developer journey (copy it from the spec section « Parcours d'un dev tiers »). Point back to `docs/superpowers/specs/2026-09-09-framework-design.md` as the source of truth.

- [ ] **Step 3: Full build, final check**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit and merge to main**

```bash
git add -A && git commit -m "docs: README with original-project credits, French architecture doc"
git checkout main && git merge --no-ff framework-foundation -m "merge: VTTale framework foundation (Java 25 build, kernel services, modules, behaviors)"
git push origin main framework-foundation
```

## Validation finale (hors plan, en jeu)

Déployer `VTTale-0.1.0-all.jar` selon le README et vérifier : mod listé dans les Mods du monde, `/roll 2d6+3` répond, connexion d'un joueur crée un token (`HytaleTokenBinder` log), aucune stack trace au démarrage.
