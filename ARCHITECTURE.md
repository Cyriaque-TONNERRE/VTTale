# VTT Project Architecture

## Overview

A system-agnostic Virtual Tabletop (VTT) core designed to run "headless" inside other game engines (Hytale, Minecraft).

**Inspired by [FoundryVTT](https://foundryvtt.com/):** VTTale follows architectural patterns similar to FoundryVTT, including:

- **Hook-based Event System**: Like FoundryVTT's `Hooks.on()` / `Hooks.call()`, VTTale uses a typed EventBus for inter-module communication
- **Modular Architecture**: Modules can extend functionality without modifying core code
- **Game System Abstraction**: Support for multiple game systems (D&D 5e, Pathfinder, etc.) via separate modules
- **Dynamic Command Registration**: Commands are registered at runtime, not hardcoded

Key differences from FoundryVTT:

- **Headless**: No UI layer, runs inside game engines as a plugin
- **Java-based**: Uses Java SPI for module discovery instead of JavaScript
- **Platform Agnostic**: Designed to work across multiple game engines via Adapters

## Tech Stack

- **Language:** Java
- **Build System:** Gradle (Multi-module)
- **Architecture:** Micro-kernel / Plug-in Based / Hexagonal (inspired by FoundryVTT)
- **Distribution:** Fat JAR (All-in-one platform plugin)

## Module Structure & Dependencies

The strictly enforced dependency flow is: `platform` -> `api` <- `kernel`.

1. **`api` (SDK)**
   - The "Contract". Contains Interfaces (`Module`, `EventBus`, `CommandProvider`) and Data Classes.
   - This is the only module third-party developers should depend on.
   - _Dependencies:_ None.

2. **`kernel` (Core)**
   - The "Runner". Manages Module Lifecycle and Event Bus implementation.
   - Discovered at runtime via Java SPI (`ServiceLoader`).
   - _Dependencies:_ `api`.

3. **`module` (Core Features) & `gamesystem` (Rulesets)**
   - **`module`**: Generic features like `chat`, `diceroll`.
   - **`gamesystem`**: Specific game systems like `dnd5e`.
   - Built-in logic included in the main distribution.
   - _Dependencies:_ `api`.

4. **`platform` (e.g., `hytale`, `minecraft`)**
   - The bridge. Translates Game Engine events into VTT Events.
   - This module acts as the entry point and bundles everything into a single distribution JAR.
   - _Dependencies:_ `api`, `kernel` (runtime), `module` (runtime), `Game Engine SDK`.

## Distribution & Extensibility

### 1. The Platform Plugin (Fat JAR)

The project is built as a **Single Fat JAR** for a specific platform (e.g., `vttale-hytale.jar`). This JAR encapsulates:

- The VTT Kernel.
- The Platform Adapter (Bootstrap).
- All built-in Modules and Game Systems.

### 2. Third-Party Extensions

Native game engine developers can extend VTTale by creating their own plugins:

1. They add `vttale-api` as a compile-time dependency.
2. They implement the `Module` interface.
3. They register their module via the `VTTale` API at runtime.
4. Since VTTale runs in the same JVM, they can interact with the global `EventBus` and `Registry`.

## Key Design Patterns

### 1. Type-Safe Event Bus

Communication happens via a typed Event Bus located in `kernel`.

- **Subscribe:** `bus.subscribe(MyEvent.class, (evt, ctx) -> ...)`
- **Publish:** `bus.publish(new MyEvent(), context)`

### 2. Event Context & Routing

Every event travels with an `EventContext` object containing the `SenderID` (UUID) and `SourceAdapter`. This allows the Adapter to route responses back to the correct user in the game world.

### 3. Dynamic Command Registry

Adapters do not hardcode commands. Commands are registered dynamically at runtime:

1. Module calls `kernel.getCommandRegistry().registerCommand("roll", "description")`.
2. Kernel stores the command in an internal registry AND publishes a `RegisterCommandRequest` event.
3. Platform Adapter subscribes to this request and registers it in the Game Engine.
4. When executed, Adapter publishes `CommandExecutedEvent` back to the bus.

**Catch-Up Pattern:** Unlike FoundryVTT which uses ordered lifecycle hooks (`init` → `setup` → `ready`), VTTale modules are loaded via Java SPI before the Platform Adapter is registered. To solve this timing issue:

- `CommandRegistry` maintains a `Map<String, String>` of all registered commands
- When an Adapter's `onEnable()` is called, it uses `getRegisteredCommands()` to catch up on commands registered before it was active
- This ensures no commands are lost due to registration order

```java
// In HytaleAdapter.onEnable():
// 1. Subscribe to future commands
kernel.getEventBus().subscribe(RegisterCommandRequest.class, this::registerHytaleCommand);

// 2. Catch up on already-registered commands
kernel.getCommandRegistry().getRegisteredCommands().forEach((name, desc) -> {
    registerHytaleCommand(new RegisterCommandRequest(name, desc), context);
});
```

## FoundryVTT Comparison

| Aspect               | FoundryVTT                       | VTTale                                        |
| -------------------- | -------------------------------- | --------------------------------------------- |
| **Language**         | JavaScript                       | Java                                          |
| **Event System**     | `Hooks.on()` / `Hooks.call()`    | `EventBus.subscribe()` / `EventBus.publish()` |
| **Module Discovery** | JSON manifest + script loading   | Java SPI (`ServiceLoader`)                    |
| **Lifecycle**        | `init` → `setup` → `ready` hooks | Immediate via SPI + catch-up pattern          |
| **UI**               | Full web-based UI                | Headless (uses host game's UI)                |
| **Extensibility**    | Modules & Systems                | Modules, Game Systems & Platform Adapters     |
| **Chat Commands**    | `CONFIG.ChatMessage.commands`    | Dynamic `CommandRegistry`                     |
