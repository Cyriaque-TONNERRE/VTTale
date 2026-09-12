# VTTale Architecture

> Documentation en français : [architecture.fr.md](architecture.fr.md)

VTTale is a **framework**: the kernel provides the building blocks (event bus,
tokens, behaviors, commands, services), and gameplay is written as modules on
top — without ever modifying the kernel.

> **Sources of truth**: the dated specs in
> [`docs/superpowers/specs/`](superpowers/specs/) — framework design
> (2026-09-09), GameSystem contract (2026-09-10), module dependencies and
> deferred activation (2026-09-11). (Specs are written in French.)
> This document is a summary for module authors; on any divergence, the specs
> win.

## Overview

A single Hytale plugin JAR embeds the whole framework:

```
VTTale/
├── api/                 # pure contracts, org.vttale.vttale — ZERO Hytale imports
├── kernel/              # simple impls (VTTaleKernel, SimpleEventBus, registries)
├── module/              # built-in modules: chat, diceroll, token, clone
├── gamesystem/          # dnd5e — example skeleton of a game system
└── platform/hytale/     # THE Hytale plugin: bootstrap + bridge to the game API
```

Only `platform/hytale` knows the Hytale API. `api`, `kernel`, `module` and
`gamesystem` are pure Java and run outside the server.

## Kernel: facade + service container

`VTTale.getKernel()` is a static facade: the platform creates the kernel
(`new VTTaleKernel()`) and injects it via `VTTale.init(kernel)`. No SPI — a
single implementation, ours.

`Kernel` is an **extensible service container**:

- fixed: `getEventBus()`, `getCommandRegistry()`, `getModuleRegistry()`;
- extensible: `getService(Class<T>)` / `registerService(Class<T>, T)`.

Every new capability (`TokenRegistry`, `BehaviorDispatcher`, …) is a
**service registered by a module** — never a hardcoded accessor in the kernel.
A module that adds a capability publishes it this way; the others consume it
with `getService(...)`.

## Modules: lifecycle and registration

`Module` is the single extension point: `onEnable(Kernel)` / `onDisable()`,
plus two optional declarations:

- **`id()`** — a stable identifier, unique among all installed modules; the
  registry refuses duplicates. Default: fully qualified class name. Override
  with a namespaced id (`"vttale:chat"`) for readable logs. A module class is
  a singleton per server: two instances of the same class share the same
  default id, and the second is refused.
- **`requires()`** — services that must exist before activation, read via
  `getService` inside `onEnable` (e.g. `Set.of(DiceService.class)`). Empty by
  default.

**Registration order no longer matters for these dependencies**: a module
whose required services are missing is parked and activated when they appear.
At the end of boot, the platform logs an ERROR report of still-parked modules
with their missing services — a module that never activates is waiting for a
service that never arrived.

Event coupling is **not** a dependency: subscribing happens at enable time and
publishing at runtime, so no order is constrained. Never declare an event in
`requires()`.

Two registration modes:

| Mode | Who | How |
|---|---|---|
| **Bundled** | the platform | `registerModule(new ChatModule())`, … — a readable list; only `HytaleAdapter` must stay first (command bridge, invisible to `requires()`) |
| **Third-party** | the module's Hytale plugin | in its `setup()`: `VTTale.getKernel().getModuleRegistry().registerModule(new MyModule())` |

Hytale classloaders are isolated per JAR: no cross-JAR discovery mechanism
exists. Self-registration from `setup()` is therefore the only path for a
third-party module.

**Shutdown**: Hytale calls the plugin's `shutdown()` on server stop — or when
the plugin itself is unloaded — while the world, the Hytale event bus and the
registries are still alive. The
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

## Game systems

A game system (D&D 5e, Pathfinder 2e, …) is a `GameSystem`: a `Module` that
additionally carries its identity — `id()`, `version()`, `components()` (owned
component types, informational for now).

**Exactly one active per server.** `SimpleModuleRegistry` refuses a second
`GameSystem` **before** its `onEnable`: zero side effects, error log, the
server starts. The contract is read as:

- active system: `kernel.getService(GameSystem.class)`;
- registration: the system does
  `kernel.registerService(GameSystem.class, this)` in its `onEnable`
  (DiceService pattern) — **without that registration, the exclusivity guard
  cannot see your system**.

Writing a game system = writing a module that implements `GameSystem` instead
of `Module`; everything else (commands, events, behaviors, services) is
unchanged.

## Event bus

Synchronous and typed: `publish(event, EventContext)` /
`subscribe(Class, BiConsumer)`, with **priorities** (ascending order, lowest
first; ties = subscription order). A `publish` returns only after every
handler — a table action (roll → apply → notify) always completes before the
caller continues.

`EventContext` carries the `senderId`: player UUID, `"CONSOLE"` or
`"KERNEL"`.

## Command flow

Modules never see the Hytale API:

```
CommandRegistry.registerCommand(name, description[, options])
→ publishes RegisterCommandRequest
→ HytaleAdapter binds it to the Hytale command system
  (catching up on commands registered before its own activation)
→ CommandExecutedEvent (the module receives the invocation)
→ replies via PlatformBroadcastEvent (CONSOLE or PlayerRef)
```

Player-facing commands provided by built-in modules:

- `/clone [player]` — spawn a static figurine wearing the player's skin (one
  clone per player); `/unclone [player]` — remove it (module `clone`, token
  tagged `clone`).

## Token model

`Token` = identity + **Components** + **tags** + **Behaviors**:

- **Components** = game-system data placed by modules
  (`record StatBlock(...) implements TokenComponent`). Data only.
- **tags** = query filters (`TokenQuery`: type/tag/owner/range).
- **Behaviors** = attachable logic (`onAttach`/`onDetach`/`onEvent`),
  identified as `namespace:name`. Their state is **private**, in their
  `BehaviorContext` (typed KV) — never on the token.
- **`BehaviorEvent`**: a game event targeting `targetToken`/`sourceToken`,
  cancellable by convention (`isCancelled()`).
- **`BehaviorDispatcher`** (a service): `dispatch`, `dispatchTo(event, tokens)`,
  `broadcast`, global listeners.

A "reaction" (opportunity attack…) is therefore not a kernel module: it is a
Behavior a game system attaches to tokens. No hardcoded listeners.

When a token is removed, the platform despawns its bound non-player entity
(figurine lifecycle).

### Token lifecycle events

`TokenCreatedEvent`, `TokenRemovedEvent`, `TokenUpdatedEvent`,
`TokenBoundEvent` — **that is all**. Movement, selection, position and every
other mutation go through `TokenUpdatedEvent`: no dedicated
`Moved`/`Selected`/`Placed` events.

Typical flow:

```
/token move X → CommandRegistry → CommandExecutedEvent
→ token module: updates the token → publishes TokenUpdatedEvent
→ every module reacts freely to the bus event
→ targeted game logic = BehaviorDispatcher.dispatch(MyBehaviorEvent)
  → behaviors attached to the affected tokens (private state, cancellable)
```

## Error handling

- **Bus**: an exception in a handler is logged through the platform logger
  (never `printStackTrace`); the following handlers still run.
- **`Module.onEnable` that throws**: the module is skipped, error logged, the
  server starts. A broken module never kills the server. Services it
  registered before throwing stay in the kernel (no rollback) and wake their
  dependents like any other service.
- **Unsatisfied `requires()`**: the module stays parked (INFO log at park
  time); an ERROR report at the end of boot lists the missing services. A
  parked module never sees `onEnable` nor `onDisable`.
- **Second `GameSystem` refused**: refused before any side effect (no
  `onEnable` at all), error logged, the first system stays active.

## Third-party developer path

1. **Code**: `class MyModule implements Module` — components, behaviors,
   events, all pure Java: api, kernel and modules depend on no Hytale class
   and run outside the server.
2. **Delivery**:
   - **Third-party JAR** (standard case): a Hytale plugin whose manifest
     declares `Dependencies: VTTALE:vttale=*`; in `setup()`: register the
     module. The bridge classloader makes api/kernel visible.
   - **Bundled**: contribute to the repo → class referenced in the platform's
     registration list.
3. **Reuse**: kernel services (`getService(TokenRegistry.class)`,
   `getService(BehaviorDispatcher.class)`), other modules' events,
   `CommandRegistry` for your commands.
