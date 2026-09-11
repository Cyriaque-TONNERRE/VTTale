# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working rules

- Discussions in **French**; code, comments, commit messages and Javadoc in **English**.
- Commit messages: conventional style, plain, **no `Co-Authored-By` trailer**.
- Feature work on a branch — never push technical work directly to `main`. Push the branch, open a PR to `main`, merge via GitHub once reviewed; delete the branch after merge (local + remote). Small non-technical changes (docs, typos) may commit directly to `main`.
- Before pushing or merging: tests + `./gradlew build` green (see Tests) — verified, not assumed.

## Project

VTTale turns a Hytale server into a tabletop RPG platform. It is a **framework**, not an app: the kernel provides the bricks (event bus, service container, tokens, behaviors, commands) and all gameplay is written as modules on top — never by modifying the kernel.

- Language: Java 25. Build: Gradle (wrapper 9.7.1, Kotlin DSL). `platform:hytale` uses `com.azuredoom.hytale-tools` (pinned) to resolve the Hytale Server dependency and generate `manifest.json`; `com.gradleup.shadow` bundles the single deployable fat JAR.
- Origin: fork of https://github.com/VTTaleTeam/VTTale (branch `poc/VTT-38-Token-Registry`). README credits the original contributors — keep them.
- Project documentation: `docs/architecture.md` (English) / `docs/architecture.fr.md` (French) — framework + module-author guide; `docs/superpowers/specs/` (dated design specs in French — one per feature, source of truth for its area). Update the architecture docs when changing architecture or commands.
- Old local references: Hytale decompiled sources (no assets) at `C:\Users\Siryak\Documents\HytaleSource\{HytaleServer,HytaleClient,Protocol}`; previous prototype repo at `C:\Users\Siryak\Documents\TTTALE` (read-only reference, never commit there).

## Architecture

One Gradle project, one deployable plugin JAR:

```
VTTale/
├── api/                 # pure contracts (org.vttale.vttale.api) — ZERO Hytale imports
├── kernel/              # simple impls: VTTaleKernel, SimpleEventBus, registries
├── module/              # built-in modules: chat, diceroll (DiceService), token
├── gamesystem/          # game systems as plain modules — dnd5e is the example skeleton
└── platform/hytale/     # THE Hytale plugin: bootstrap + the only Hytale-aware code
```

- **Kernel = service container**: `getService(Class)` / `registerService(Class, T)`. A new capability is a service registered by a module — never a hardcoded accessor in the kernel.
- **No SPI / no ServiceLoader**: built-in modules are registered explicitly in `VTTaleHytalePlugin.setup()`; third-party modules self-register from their own plugin `setup()` via `VTTale.getKernel().getModuleRegistry().registerModule(...)` (Hytale classloaders are isolated per JAR, so cross-JAR discovery is impossible — dependency string `VTTALE:vttale=*`).
- **Rules**: `api`/`kernel`/`module`/`gamesystem` never import `com.hypixel.*` or `org.joml.*`; only `platform/hytale` may. Modules talk through events and services, never by reaching into each other's packages (dice is the pattern: `DiceService` is callable, the `/roll` command only parses notation — commands are a thin frontend, little should flow through them).
- **Event bus**: synchronous, typed, priority-ordered (lower first, ties = subscription order). `publish` returns only after every handler returned. Handler/module failures are caught (`Throwable` at plugin boundaries), logged, and contained — one broken module never kills the server.
- **Tokens**: identity + Components (data) + tags (filters) + Behaviors (logic, private `BehaviorContext`, dispatched via `BehaviorDispatcher`). Lifecycle events are exactly `TokenCreatedEvent`, `TokenRemovedEvent`, `TokenUpdatedEvent`, `TokenBoundEvent` — every mutation flows through `TokenUpdatedEvent`; there are no Moved/Selected/Placed events.
- Reload is not supported: the plugin boots once per JVM (`VTTale.init` throws on re-init).
- Shutdown: Hytale calls `PluginBase.shutdown()` (world and bus still alive)
  → `ModuleRegistry.disableAll()`; `onDisable` is the save point. The registry
  is closed after `disableAll()` — no registration afterwards.

## Tests

- **Unit tests required** on `api`, `kernel`, `module`, `gamesystem` (JUnit 5).
  Verification = `./gradlew :api:test :kernel:test :module:test :gamesystem:test`,
  then `./gradlew build` (compile), then in-game validation: copy
  `platform/hytale/build/libs/VTTale-<version>-all.jar` to `%APPDATA%\Hytale\UserData\Mods`...
- Tests tagged `KNOWN LIMITATION` pin current buggy behaviour on purpose; when the
  fix lands, the test is expected to fail — replace it, do not delete it.
- `platform/hytale` is not unit-tested (needs the Hytale SDK) and is excluded from CI.

## Commands

```bash
./gradlew build                        # everything: JARs in <module>/build/libs/ (deploy = VTTale-<v>-all.jar)
./gradlew :module:build                # one module
```

## Hytale API notes

- Plugin entry point: class extending `JavaPlugin`, constructor takes `JavaPluginInit`, override `setup()`.
- Commands: `AbstractCommand` override `acceptCall(CommandSender, ParserContext, ParseResult)` (verified on Server 0.6.4); publish a `CommandExecutedEvent` on the kernel bus and let modules handle it. Replies route via `PlatformBroadcastEvent` (CONSOLE or `PlayerRef`).
- Events: `plugin.getEventRegistry().register(EventClass.class, handler)` (registry is on `PluginBase`).
- Logging: `getLogger()` is flogger-style — `getLogger().at(Level.INFO).log("...")`. Kernel-side classes use `java.lang.System.Logger`.
- `javax.annotation.Nonnull` comes with the Server dependency.
- The API is young and changes fast — verify signatures with `javap -cp` against the resolved jar (`~/.gradle/caches/modules-2/files-2.1/com.hypixel.hytale/Server/<v>/...`) or the decompiled sources before writing bridge code.
- World/entity mutations must run on the world thread: `world.execute(() -> ...)`.
- Vectors are `org.joml.*` (`x()/y()/z()`, `Rotation3f.yaw()/pitch()`) — there is no `com.hypixel.hytale.math.vector.Vector3d`.
