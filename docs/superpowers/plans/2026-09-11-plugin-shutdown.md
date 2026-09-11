# Plan d'implémentation — Shutdown du plugin

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** brancher `disableAll()` sur `PluginBase.shutdown()` (appelé par Hytale pendant que monde, bus et registres sont vivants), fermer le registre après `disableAll()`, et documenter `onDisable` comme point de save.

**Architecture :** `VTTaleHytalePlugin` override `shutdown()` au lieu d'un hook JVM Runtime ; `SimpleModuleRegistry` gagne un drapeau `closed` (levé **dès l'entrée** de `disableAll()`) qui refuse `registerModule` et bloque `drainPending`. Javadoc `Module.onDisable` = contrat de save. Spec : `docs/superpowers/specs/2026-09-11-plugin-shutdown-design.md`.

**Tech Stack :** Java 25, Gradle wrapper (`./gradlew`), JUnit 5.

## Global Constraints

- Branche `feat/plugin-shutdown` — jamais de push direct sur `main` ; PR vers `main` ensuite.
- Commits : style conventionnel (`feat:`, `test:`, `docs:`, `refactor:`), plain, **sans trailer `Co-Authored-By`**.
- Code, commentaires, Javadoc en **anglais**.
- `api`/`kernel`/`module`/`gamesystem` : zéro import `com.hypixel.*` / `org.joml.*` (une mention `{@code world.execute}` dans de la Javadoc est du texte, pas un import — autorisé).
- `platform/hytale` : pas de tests unitaires (règle projet) ; vérification = compilation.
- Vérification finale obligatoire : `./gradlew :api:test :kernel:test :module:test :gamesystem:test` puis `./gradlew build`.

---

### Task 1: Fermer le registre après `disableAll()`

**Files:**
- Modify: `kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java`
- Test: `kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java`

**Interfaces:**
- Consumes: rien de nouveau — `registerModule(Module)`, `disableAll()`, `drainPending()` existants.
- Produces: contrat « registre fermé » — après `disableAll()`, `registerModule` logge une ERROR et refuse (pas d'`onEnable`, id non réservé) ; `drainPending` n'active plus rien. Task 3 s'appuie dessus (« registre fermé pour de bon après shutdown »).

- [ ] **Step 1: Créer la branche**

```bash
git checkout -b feat/plugin-shutdown
```

- [ ] **Step 2: Écrire les tests qui échouent**

Dans `SimpleModuleRegistryTest` :

a) **Remplacer** le test `disableAllReleasesIds` (il affirme le contraire du nouveau contrat) par :

```java
    @Test
    @DisplayName("after disableAll() the registry is closed: registerModule is refused")
    void registerAfterCloseIsRefused() {
        registry.registerModule(new RecordingModule("a", log));
        registry.disableAll();

        registry.registerModule(new RecordingModule("late", log));

        // Refused: no onEnable, no id reservation, nothing in the lists.
        assertIterableEquals(List.of("enable:a", "disable:a"), log);
    }
```

b) **Ajouter** — module enregistré depuis un `onDisable` (verrous Java réentrants : le code repasse dans le registre en pleine boucle de démontage) :

```java
    @Test
    @DisplayName("a module registered from inside an onDisable is refused, never enabled")
    void registrationFromOnDisableIsRefused() {
        Module selfish = new RecordingModule("selfish", log) {
            @Override
            public void onDisable() {
                log.add("disable:selfish:start");
                registry.registerModule(new RecordingModule("sneaky", log));
                log.add("disable:selfish:end");
            }
        };
        registry.registerModule(new RecordingModule("a", log));
        registry.registerModule(selfish);

        registry.disableAll();

        // Reverse order: selfish disables first and tries to smuggle "sneaky"
        // in — refused, because the registry closed before the loop. Otherwise
        // sneaky would be enabled mid-teardown, then cleared by modules.clear()
        // without ever seeing onDisable.
        assertIterableEquals(List.of(
                "enable:a", "enable:selfish",
                "disable:selfish:start", "disable:selfish:end",
                "disable:a"), log);
    }
```

c) **Ajouter** — service posé depuis un `onDisable` (réveille le drain, qui ne doit plus rien activer) :

```java
    @Test
    @DisplayName("a service registered from inside an onDisable wakes nothing")
    void serviceFromOnDisableWakesNothing() {
        Module provider = new RecordingModule("provider", log) {
            @Override
            public void onDisable() {
                log.add("disable:provider:start");
                kernel.registerService(SomeService.class, new SomeService() {
                });
                log.add("disable:provider:end");
            }
        };
        registry.registerModule(new RecordingModule("parked", log, Set.of(SomeService.class)));
        registry.registerModule(provider);

        registry.disableAll();

        // The service lands in the kernel, but the drain is closed: "parked"
        // must not activate mid-teardown (it would be cleared without
        // onDisable). It stays parked, and parked modules never see onDisable.
        assertIterableEquals(List.of(
                "enable:provider",
                "disable:provider:start", "disable:provider:end"), log);
    }
```

- [ ] **Step 3: Vérifier que les tests échouent**

Run: `./gradlew :kernel:test --tests "org.vttale.vttale.kernel.module.SimpleModuleRegistryTest"`
Expected: **FAIL** ×3
- `registerAfterCloseIsRefused` — actual `[enable:a, disable:a, enable:late]` (le registre accepte encore)
- `registrationFromOnDisableIsRefused` — actual contient `enable:sneaky`
- `serviceFromOnDisableWakesNothing` — actual contient `enable:parked`

- [ ] **Step 4: Implémenter le drapeau `closed`**

Dans `SimpleModuleRegistry` :

a) Champ (à côté de `private boolean dirty = false;`) :

```java
    // Set at the ENTRY of disableAll, before the onDisable loop: Java locks
    // are reentrant, so an onDisable that calls registerModule (or
    // registerService, which triggers drainPending) re-enters the registry
    // mid-teardown. Raised first, that code is refused instead of being
    // activated here and cleared below without ever seeing onDisable.
    // No reopen path: the plugin boots once per JVM, the registry stays closed.
    private boolean closed = false;
```

b) `registerModule`, première instruction :

```java
    @Override
    public synchronized void registerModule(Module module) {
        if (closed) {
            LOGGER.log(Level.ERROR, "Module " + idOf(module) + " refused: the registry is closed");
            return;
        }
        if (modules.contains(module) || pending.contains(module)) {
            return;
        }
        // ... reste inchangé
```

c) `drainPending`, début :

```java
    private synchronized void drainPending() {
        if (closed) {
            return;
        }
        if (activating > 0 || draining) {
            dirty = true;
            return;
        }
        // ... reste inchangé
```

d) `disableAll`, première instruction :

```java
    @Override
    public synchronized void disableAll() {
        // Closed BEFORE the loop, not after: an onDisable that re-enters the
        // registry must be refused, not activated mid-teardown (see the field
        // comment). Second call: empty lists, closed already true — a no-op.
        closed = true;
        for (int i = modules.size() - 1; i >= 0; i--) {
            // ... boucle inchangée
```

e) Javadoc de classe, remplacer « A module id is reserved at registration and never released until {@link #disableAll()}. » par :

```java
 * A module id is reserved at registration and released only by
 * {@link #disableAll()}, which also closes the registry for good:
 * afterwards {@link #registerModule} logs an error and refuses.
```

- [ ] **Step 5: Vérifier que tous les tests passent**

Run: `./gradlew :kernel:test`
Expected: PASS (les tests existants — dont `disableAllIsIdempotent`, `moduleWithMissingServiceIsParked`, `failedOnEnableStillWakesParkedDependents` — restent verts : second `disableAll` = listes vides, module parqué jamais activé).

- [ ] **Step 6: Commit**

```bash
git add kernel/src/main/java/org/vttale/vttale/kernel/module/SimpleModuleRegistry.java kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java
git commit -m "feat(kernel): close the module registry after disableAll"
```

---

### Task 2: Contrat « onDisable = save point » (test + Javadoc api)

**Files:**
- Modify: `api/src/main/java/org/vttale/vttale/api/module/Module.java` (Javadoc de `onDisable` uniquement)
- Test: `kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java`

**Interfaces:**
- Consumes: `EventBus.subscribe/publish`, `EventContext(String)`, `kernel.getService/registerService` (existants). Scaffolding `RecordingModule`/`SomeService` du Task 1 (même fichier de test).
- Produces: le contrat documenté que Task 3/4 citent (« `onDisable` is the save point »).

Note : la spec disait `VTTaleKernelTest` ; le test va dans `SimpleModuleRegistryTest` — le scaffolding (`RecordingModule`, `SomeService`) y vit déjà et le contrat testé est celui de `disableAll()`. Spec amendée en conséquence (commit du plan).

- [ ] **Step 1: Écrire le test (il doit passer tout de suite : le comportement existe déjà, le test le verrouille)**

Ajouter à la fin de `SimpleModuleRegistryTest` :

```java
    /** Marker event for the save-point contract test. */
    private static final class SaveFinishedEvent implements Event {
    }

    @Test
    @DisplayName("during onDisable, services and the event bus still work: the save point")
    void onDisableIsTheSavePoint() {
        List<String> received = new ArrayList<>();
        kernel.getEventBus().subscribe(SaveFinishedEvent.class, (event, context) ->
                received.add("handler"));
        registry.registerModule(new SomeServiceProviderModule("provider", log));
        registry.registerModule(new RecordingModule("saver", log) {
            @Override
            public void onDisable() {
                SomeService service = kernel.getService(SomeService.class);
                log.add("disable:saver:service=" + (service != null));
                kernel.getEventBus().publish(new SaveFinishedEvent(), new EventContext("KERNEL"));
            }
        });

        registry.disableAll();

        // Reverse order: saver disables first, reads a service published by
        // provider, notifies through the bus. This is what "save in onDisable"
        // relies on — nothing is unregistered during teardown.
        assertIterableEquals(List.of(
                "enable:provider", "enable:saver",
                "disable:saver:service=true",
                "disable:provider"), log);
        assertIterableEquals(List.of("handler"), received);
    }
```

Imports à ajouter en tête de fichier : `org.vttale.vttale.api.events.Event`, `org.vttale.vttale.api.events.EventContext`.

- [ ] **Step 2: Vérifier qu'il passe**

Run: `./gradlew :kernel:test --tests "org.vttale.vttale.kernel.module.SimpleModuleRegistryTest"`
Expected: PASS (`disable:saver:service=true` + handler notifié — le bus et les services survivent pendant `disableAll`).

- [ ] **Step 3: Documenter le contrat dans `Module.onDisable`**

Remplacer la Javadoc de `onDisable()` dans `api/src/main/java/org/vttale/vttale/api/module/Module.java` par :

```java
    /**
     * Called once when the module is disabled, in reverse activation order
     * (dependents before providers) — never twice, even if
     * {@code disableAll()} runs again. This is the save point: services are
     * still registered and the kernel bus still works, so serialize state
     * here.
     * <p>
     * Keep the save fast and synchronous: a save that blocks hangs the server
     * stop. Do not queue world work (a task submitted during shutdown may
     * never run) and do not register modules or services (the registry is
     * closed). Third-party modules: your plugin's Hytale registrations are
     * already torn down by the time this runs — undo those in your plugin's
     * own {@code shutdown()}, not here.
     * </p>
     */
    default void onDisable() {
    }
```

- [ ] **Step 4: Vérifier la compilation api**

Run: `./gradlew :api:compileJava :kernel:test`
Expected: BUILD SUCCESSFUL (aucun import nouveau — la mention « world » est du texte Javadoc).

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/org/vttale/vttale/api/module/Module.java kernel/src/test/java/org/vttale/vttale/kernel/module/SimpleModuleRegistryTest.java
git commit -m "docs(api): onDisable is the save point"
```

---

### Task 3: `VTTaleHytalePlugin.shutdown()`

**Files:**
- Modify: `platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/VTTaleHytalePlugin.java` (fichier entier ci-dessous)

**Interfaces:**
- Consumes: `ModuleRegistry.disableAll()` (Task 1 : ferme le registre), `PluginBase.shutdown()` — `protected void shutdown()`, appelé par `PluginManager.shutdown()` avant `eventBus.shutdown()` (vérifié dans les sources décompilées 0.6.4).
- Produces: le point d'entrée shutdown du plugin. Rien d'autre ne change.

- [ ] **Step 1: Réécrire le fichier**

Contenu complet de `VTTaleHytalePlugin.java` :

```java
package org.vttale.vttale.platform.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.VTTale;
import org.vttale.vttale.api.module.ModuleRegistry;
import org.vttale.vttale.gamesystem.dnd5e.DND5EGameSystem;
import org.vttale.vttale.kernel.VTTaleKernel;
import org.vttale.vttale.kernel.module.SimpleModuleRegistry;
import org.vttale.vttale.module.chat.ChatModule;
import org.vttale.vttale.module.diceroll.DiceRollModule;
import org.vttale.vttale.module.token.TokenModule;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * VTTale platform for Hytale. The only module aware of the game API.
 * <p>
 * Bootstrap order: create kernel, inject facade, register built-in modules
 * explicitly (no SPI). The token binder declares its TokenRegistry dependency
 * via requires(); the registry parks it until the service exists.
 * <p>
 * Shutdown: Hytale calls shutdown() on server stop, while the world and the
 * event bus are still alive — that is where modules save (see Module#onDisable).
 * <p>
 * Reload is not supported: the plugin boots once per JVM (VTTale.init throws
 * on re-init, and the module registry is closed for good after shutdown()).
 */
public class VTTaleHytalePlugin extends JavaPlugin {

    private ModuleRegistry modules;

    public VTTaleHytalePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Kernel kernel = new VTTaleKernel();
        VTTale.init(kernel);

        modules = kernel.getModuleRegistry();
        modules.registerModule(new HytaleAdapter(this));
        modules.registerModule(new ChatModule());
        modules.registerModule(new DiceRollModule());
        modules.registerModule(new TokenModule());
        modules.registerModule(new DND5EGameSystem());
        modules.registerModule(new HytaleTokenBinder(this));

        // Report modules still parked on missing services. instanceof, not a
        // cast: a diagnostic must never fail the boot if the impl changes.
        if (modules instanceof SimpleModuleRegistry registry) {
            registry.reportPendingModules();
        }

        getLogger().at(Level.INFO).log("VTTale ready");
    }

    @Override
    protected void shutdown() {
        if (modules == null) {
            return; // setup() failed before booting the kernel
        }
        modules.disableAll();
        getLogger().at(Level.INFO).log("VTTale shut down");
    }
}
```

(L'ancien `Runtime.getRuntime().addShutdownHook(...)` disparaît — il tournait après le démontage Hytale, dans la fenêtre des 3 s avant `Runtime.halt()`.)

- [ ] **Step 2: Vérifier la compilation**

Run: `./gradlew :platform:hytale:compileJava`
Expected: BUILD SUCCESSFUL — sinon la signature `protected void shutdown()` a changé dans la dépendance Hytale : vérifier avec `javap -cp` sur le jar résolu (`~/.gradle/caches/modules-2/files-2.1/com.hypixel.hytale/Server/...`) et ajuster.

- [ ] **Step 3: Commit**

```bash
git add platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/VTTaleHytalePlugin.java
git commit -m "feat(platform): disable modules from the Hytale plugin lifecycle"
```

---

### Task 4: Docs + vérification complète

**Files:**
- Modify: `docs/architecture.md` (insertion avant `## Game systems`, ligne 83)
- Modify: `docs/architecture.fr.md` (insertion avant `## Systèmes de jeu`, ligne 83)
- Modify: `CLAUDE.md` (une ligne sous « Architecture », après la puce « Reload is not supported »)

**Interfaces:**
- Consumes: le comportement des Tasks 1-3 (ce qui est documenté).
- Produces: rien de code — la doc que les tiers liront.

- [ ] **Step 1: Insérer dans `docs/architecture.md`** (fin de la section « Modules: lifecycle and registration », après le paragraphe « Hytale classloaders are isolated per JAR … third-party module. ») :

```markdown
**Shutdown**: Hytale calls the plugin's `shutdown()` on server stop, while
the world, the Hytale event bus and the registries are still alive. The
platform answers with `ModuleRegistry.disableAll()`: every module sees
`onDisable()` once, in reverse activation order (dependents before
providers), then the registry is closed for good — `registerModule` after
that is refused. `onDisable` is the **save point**: services stay registered
and the kernel bus still works there. Keep saves fast and synchronous (a
save that blocks hangs the server stop); do not queue world work
(`world.execute(...)` — a task queued during shutdown may never run) and do
not register modules or services there.

Third-party ordering: Hytale stops plugins in reverse dependency order, so
your plugin's own `shutdown()` and `cleanup()` run **before** VTTale's.
Saving kernel state from your module's `onDisable` is fine; touching your
own Hytale registrations there is too late — undo those in your plugin's
`shutdown()`.
```

- [ ] **Step 2: Miroir dans `docs/architecture.fr.md`** (même endroit, avant « ## Systèmes de jeu ») :

```markdown
**Arrêt** : Hytale appelle le `shutdown()` du plugin à l'arrêt du serveur,
pendant que le monde, le bus d'événements Hytale et les registres sont encore
vivants. La plateforme répond par `ModuleRegistry.disableAll()` : chaque
module voit `onDisable()` une fois, en ordre inverse d'activation (dépendants
avant fournisseurs), puis le registre est fermé pour de bon — un
`registerModule` ensuite est refusé. `onDisable` est le **point de save** :
les services restent enregistrés et le bus kernel y fonctionne. Gardez les
saves rapides et synchrones (un save qui bloque suspend l'arrêt du serveur) ;
n'y mettez pas de travail au monde en file (`world.execute(...)` — une tâche
soumise pendant l'arrêt peut ne jamais tourner) et n'y enregistrez ni module
ni service.

Ordre inter-plugins : Hytale arrête les plugins en ordre inverse des
dépendances, donc le `shutdown()` et le `cleanup()` de **votre** plugin
tournent **avant** ceux de VTTale. Sauvegarder l'état kernel depuis
l'`onDisable` de votre module marche ; toucher là à vos propres
enregistrements Hytale est trop tard — démontez-les dans le `shutdown()` de
votre plugin.
```

- [ ] **Step 3: Ajouter dans `CLAUDE.md`**, juste après la puce « Reload is not supported… » :

```markdown
- Shutdown: Hytale calls `PluginBase.shutdown()` (world and bus still alive)
  → `ModuleRegistry.disableAll()`; `onDisable` is the save point. The registry
  is closed after `disableAll()` — no registration afterwards.
```

- [ ] **Step 4: Vérification complète (obligatoire avant push)**

Run: `./gradlew :api:test :kernel:test :module:test :gamesystem:test && ./gradlew build`
Expected: BUILD SUCCESSFUL, tous les tests verts.

- [ ] **Step 5: Commit**

```bash
git add docs/architecture.md docs/architecture.fr.md CLAUDE.md
git commit -m "docs: plugin shutdown via the Hytale lifecycle, onDisable as save point"
```

---

## Notes d'exécution

- Ordre des tasks = ordre de dépendance réel (1 → 2 → 3 → 4) ; ne pas réordonner.
- Aucun test KNOWN LIMITATION touché : `disableAllReleasesIds` est **remplacé** (pas un test de limitation, un contrat inversé par le design).
- Validation en jeu (post-merge, manuel) : stop serveur → « VTTale shut down » dans les logs **avant** les lignes `eventBus.shutdown()` de l'arrêt Hytale.
