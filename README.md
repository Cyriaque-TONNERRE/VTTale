# VTTale

VTTale turns a Hytale server into a tabletop RPG platform (VTT). It is a
**framework**: the kernel provides the building blocks (event bus, tokens,
behaviors, commands, services) and all gameplay is written as modules on top —
without ever modifying the kernel.

> Documentation en français : [README.fr.md](README.fr.md)

## Credits

VTTale lives again thanks to the work of the original project's contributors
and its sources of inspiration:

- [Sparky200](https://github.com/Sparky200)
- [PhoenixEpic](https://github.com/PhoenixEpic)
- [giopalma](https://github.com/giopalma)
- The [VTTaleTeam/VTTale](https://github.com/VTTaleTeam/VTTale) project, which
  this repository forks (branch `poc/VTT-38-Token-Registry`).

## Architecture

A single Hytale plugin JAR embeds everything:

| Module | Role |
|---|---|
| `api` | Pure contracts (`org.vttale.vttale.api`), zero Hytale imports |
| `kernel` | Simple implementations (priority event bus, registries, services) |
| `module` | Built-in modules: chat, dice (`/roll`), tokens |
| `gamesystem` | Game systems — `dnd5e` is the example skeleton |
| `platform/hytale` | The Hytale plugin: bootstrap, bridge to the game API |

See `docs/architecture.md` for details and the "write a module" guide.

## Build

```bash
./gradlew build
```

The deployable JAR is `platform/hytale/build/libs/VTTale-<version>-all.jar`.

## Deployment (in-game test)

1. Copy `VTTale-<version>-all.jar` into `%APPDATA%\Hytale\UserData\Mods`
   (create the folder if missing).
2. Launch Hytale → create a world → cogwheel → Mods → check that **VTTale** is
   listed.
3. In game: `/roll 2d6+3` should answer in chat; a player who connects gets a
   token (server log).

## Writing a third-party module

1. Java project with `org.vttale:vttale` (or the `api/` sources) as
   `compileOnly`.
2. Write `class MyModule implements Module` (components, behaviors, events —
   pure Java, testable outside Hytale). Two optional declarations:
   - **`id()`** — unique identifier (`"myplugin:mymodule"`); default: fully
     qualified class name; duplicates are refused.
   - **`requires()`** — required services (`Set.of(DiceService.class)`): the
     module is parked and enabled as soon as they appear, so registration
     order no longer matters.
3. Ship a Hytale plugin whose `manifest.json` declares
   `"Dependencies": { "VTTALE:vttale": "*" }` and whose `Main` (class extending
   `JavaPlugin`) does the following in `setup()`:

```java
VTTale.getKernel().getModuleRegistry().registerModule(new MyModule());
```

Hytale classloaders are isolated per JAR, so self-registration is the only
cross-plugin discovery mechanism.

## Contributing

- Discussions in French **or English**; code, comments, commits, and Javadoc in
  English (conventional commits, no trailer).
- Technical work (feature, refactor, bugfix): branch → push → **Pull Request**
  to `main` → merge after review, branch deleted. Small touch-ups (docs,
  typos, minor fixes): direct commit to `main` is fine.
- Keep the suite green: `./gradlew :api:test :kernel:test :module:test
  :gamesystem:test` then `./gradlew build`.
- Architecture change: a dated spec first in `docs/superpowers/specs/`, then
  the code, then `docs/architecture.md`.
