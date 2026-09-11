# Player Clone Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/clone [player]` spawns a static humanoid figurine wearing the source player's skin, tracked as a VTTale token; `/unclone [player]` removes it.

**Architecture:** Pure module `module/clone` owns commands, the one-clone-per-player rule and the token lifecycle. A `PlayerCloneService` interface (same pattern as `DiceService`) exposes the two platform-only capabilities — resolving a player and spawning the skinned NPC. `HytaleTokenBinder` implements it; the module registry parks `PlayerCloneModule` until the service exists, so registration order in `setup()` does not matter. Token removal (`/unclone`) triggers despawn reactively: the binder's `TokenRemovedEvent` handler removes any bound non-player entity.

**Tech Stack:** Java 25, Gradle multi-module (`api`, `module`, `platform/hytale`), JUnit 5, Hytale Server SDK 0.6.x (only in `platform/hytale`).

**Spec:** `docs/superpowers/specs/2026-09-11-player-clone-design.md`

## Global Constraints

- Discussions in French; code, comments, commit messages and Javadoc in English.
- Commit messages: conventional style, no `Co-Authored-By` trailer.
- `api`/`kernel`/`module`/`gamesystem` must NEVER import `com.hypixel.*` or `org.joml.*` — only `platform/hytale` may.
- Unit tests live in `module/src/test/java/...`; run them with `./gradlew :module:test`.
- Full gate before merge: `./gradlew :module:test :api:test :kernel:test :gamesystem:test` then `./gradlew build`.
- Chat replies go through `SendMessageEvent(message, senderId)`; the ChatModule prefixes them with `[VTT] ` and routes to the sender.
- Hytale world/entity mutations must run inside `world.execute(() -> ...)`.
- Verify Hytale signatures with `javap -cp` against the resolved Server jar before trusting decompiled-source guesses (API moves fast).

---

### Task 1: `PlayerCloneService` interface + `PlayerCloneModule` with `/clone`

**Files:**
- Create: `module/src/main/java/org/vttale/vttale/module/clone/PlayerCloneService.java`
- Create: `module/src/main/java/org/vttale/vttale/module/clone/PlayerCloneModule.java`
- Test: `module/src/test/java/org/vttale/vttale/module/clone/PlayerCloneModuleTest.java`

**Interfaces:**
- Consumes: `TokenRegistry` (`api/.../token/TokenRegistry.java`: `create(String, TokenType, UUID)`, `bindToEntity(UUID, UUID)`, `getByOwner(UUID)`, `remove(UUID)`), `Token` (`addTag(String)`, `getTags()`, `getOwnerId()`, `getName()`, `getBoundEntityId()`), `CoreTokenType.PLAYER_CHARACTER`, `CommandExecutedEvent`, `SendMessageEvent(String message, String targetId)`, `Module` (`requires()`, `onEnable(Kernel)`).
- Produces: `PlayerCloneService` with exactly these three methods (used by Task 3):
  ```java
  CompletableFuture<UUID> spawnClone(UUID sourcePlayerUuid);
  UUID resolvePlayer(String playerName);   // null if unknown/offline
  String playerName(UUID playerUuid);      // null if offline
  ```
  `PlayerCloneModule.CLONE_TAG = "clone"` (public constant). Module id: `"vttale:clone"`. Commands registered: `clone`, `unclone`.

- [ ] **Step 1: Write the failing tests**

Create `module/src/test/java/org/vttale/vttale/module/clone/PlayerCloneModuleTest.java`:

```java
package org.vttale.vttale.module.clone;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.events.CommandExecutedEvent;
import org.vttale.vttale.api.events.EventContext;
import org.vttale.vttale.api.token.CoreTokenType;
import org.vttale.vttale.api.token.Token;
import org.vttale.vttale.api.token.TokenRegistry;
import org.vttale.vttale.kernel.VTTaleKernel;
import org.vttale.vttale.module.chat.ChatModule;
import org.vttale.vttale.module.chat.PlatformBroadcastEvent;
import org.vttale.vttale.module.token.TokenModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the real chain a player triggers:
 * CommandExecutedEvent -> PlayerCloneModule -> (fake PlayerCloneService) -> TokenRegistry.
 * <p>
 * Only the platform spawn is faked; everything else runs on a real kernel.
 */
class PlayerCloneModuleTest {

    private static final String PLAYER = "3f7c1f5e-0000-0000-0000-000000000001";
    private static final UUID PLAYER_ID = UUID.fromString(PLAYER);
    private static final UUID ENTITY = UUID.fromString("3f7c1f5e-0000-0000-0000-0000000000aa");

    private VTTaleKernel kernel;
    private FakeCloneService clones;
    private List<PlatformBroadcastEvent> out;

    /** Records calls and answers immediately (completed futures keep tests synchronous). */
    private static class FakeCloneService implements PlayerCloneService {
        final Map<String, UUID> players = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();
        final List<UUID> spawnCalls = new ArrayList<>();
        boolean failNextSpawn;

        @Override
        public CompletableFuture<UUID> spawnClone(UUID sourcePlayerUuid) {
            spawnCalls.add(sourcePlayerUuid);
            if (failNextSpawn) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
            return CompletableFuture.completedFuture(ENTITY);
        }

        @Override
        public UUID resolvePlayer(String playerName) {
            return players.get(playerName);
        }

        @Override
        public String playerName(UUID playerUuid) {
            return names.get(playerUuid);
        }
    }

    @BeforeEach
    void setUp() {
        kernel = new VTTaleKernel();
        out = new ArrayList<>();
        kernel.getEventBus().subscribe(PlatformBroadcastEvent.class, (e, ctx) -> out.add(e));
        kernel.getModuleRegistry().registerModule(new ChatModule());
        kernel.getModuleRegistry().registerModule(new TokenModule());

        clones = new FakeCloneService();
        clones.players.put("Bob", PLAYER_ID);
        clones.names.put(PLAYER_ID, "Bob");
        kernel.registerService(PlayerCloneService.class, clones);

        kernel.getModuleRegistry().registerModule(new PlayerCloneModule());
    }

    private void run(String command, String senderId, String... args) {
        kernel.getEventBus().publish(
                new CommandExecutedEvent(command, args), new EventContext(senderId));
    }

    private String lastMessage() {
        assertEquals(1, out.size(), "exactly one reply expected");
        return out.getFirst().getFormattedMessage();
    }

    private TokenRegistry tokens() {
        return kernel.getService(TokenRegistry.class);
    }

    @Test
    @DisplayName("the module declares /clone and /unclone")
    void wiring() {
        assertTrue(kernel.getCommandRegistry().getRegisteredCommands().containsKey("clone"));
        assertTrue(kernel.getCommandRegistry().getRegisteredCommands().containsKey("unclone"));
    }

    @Test
    @DisplayName("/clone spawns via the service, then creates and binds the clone token")
    void cloneHappyPath() {
        run("clone", PLAYER);

        assertEquals(1, clones.spawnCalls.size());
        assertEquals(PLAYER_ID, clones.spawnCalls.getFirst());

        Token token = tokens().getByOwner(PLAYER_ID).stream()
                .filter(t -> t.getTags().contains(PlayerCloneModule.CLONE_TAG))
                .findFirst().orElse(null);
        assertNotNull(token, "a clone token must exist for Bob");
        assertEquals("Bob (clone)", token.getName());
        assertTrue(token.isType(CoreTokenType.PLAYER_CHARACTER));
        assertEquals(PLAYER_ID, token.getOwnerId().orElse(null));
        assertEquals(ENTITY, token.getBoundEntityId().orElse(null));
        assertEquals("[VTT] Clone spawned.", lastMessage());
    }

    @Test
    @DisplayName("/clone with a player name clones that player, not the sender")
    void cloneByName() {
        UUID bob = PLAYER_ID;
        UUID alice = UUID.fromString("3f7c1f5e-0000-0000-0000-000000000002");
        clones.players.put("Alice", alice);
        clones.names.put(alice, "Alice");

        run("clone", PLAYER, "Alice");

        assertEquals(List.of(alice), clones.spawnCalls);
        assertEquals("[VTT] Clone spawned.", lastMessage());
    }

    @Test
    @DisplayName("too many arguments prints usage")
    void usage() {
        run("clone", PLAYER, "Bob", "extra");
        assertEquals("[VTT] Usage: /clone [player]", lastMessage());
        assertTrue(clones.spawnCalls.isEmpty());
    }

    @Test
    @DisplayName("console must name a player")
    void consoleNeedsTarget() {
        run("clone", "CONSOLE");
        assertEquals("[VTT] Console must specify a player: /clone <player>", lastMessage());
        assertTrue(clones.spawnCalls.isEmpty());
    }

    @Test
    @DisplayName("an unknown or offline player name is reported")
    void unknownPlayer() {
        run("clone", PLAYER, "Nobody");
        assertEquals("[VTT] No online player named Nobody", lastMessage());
        assertTrue(clones.spawnCalls.isEmpty());
    }

    @Test
    @DisplayName("a player can only have one clone: second /clone is a no-op with a message")
    void oneCloneMax() {
        run("clone", PLAYER);
        assertEquals(1, clones.spawnCalls.size());
        out.clear();

        run("clone", PLAYER, "Bob");
        assertEquals("[VTT] Bob already has a clone.", lastMessage());
        assertEquals(1, clones.spawnCalls.size(), "the service must not be called twice");
        assertEquals(1, tokens().getByOwner(PLAYER_ID).size(), "no extra token created");
    }

    @Test
    @DisplayName("a spawn still in flight counts as existing: no double spawn")
    void spawnInFlightIsDeduplicated() {
        CompletableFuture<UUID> gate = new CompletableFuture<>();
        clones = new FakeCloneService() {
            @Override
            public CompletableFuture<UUID> spawnClone(UUID sourcePlayerUuid) {
                spawnCalls.add(sourcePlayerUuid);
                return gate;
            }
        };
        kernel.registerService(PlayerCloneService.class, clones);
        kernel.getModuleRegistry().registerModule(new PlayerCloneModule());

        run("clone", PLAYER);
        run("clone", PLAYER);
        assertEquals(1, clones.spawnCalls.size(), "the second /clone must not spawn again");

        gate.complete(ENTITY); // token is created on completion, message goes out
        assertEquals("[VTT] Clone spawned.", lastMessage());
        assertEquals(1, tokens().getByOwner(PLAYER_ID).size());
    }

    @Test
    @DisplayName("a failed spawn creates nothing and reports the error")
    void spawnFailure() {
        clones.failNextSpawn = true;
        run("clone", PLAYER);

        assertEquals("[VTT] Clone failed: boom", lastMessage());
        assertEquals(0, tokens().getByOwner(PLAYER_ID).size(), "no orphan token on failure");
    }

    @Test
    @DisplayName("offline source at completion time falls back to the uuid in the token name")
    void nameFallbackOnOffline() {
        clones = new FakeCloneService() {
            @Override
            public String playerName(UUID playerUuid) {
                return null; // player left between spawn and token creation
            }
        };
        kernel.registerService(PlayerCloneService.class, clones);
        kernel.getModuleRegistry().registerModule(new PlayerCloneModule());

        run("clone", PLAYER);

        Token token = tokens().getByOwner(PLAYER_ID).getFirst();
        assertEquals(PLAYER_ID + " (clone)", token.getName());
    }

    @Test
    @DisplayName("other commands are ignored")
    void ignoresOtherCommands() {
        run("roll", PLAYER, "2d6");
        assertEquals(0, out.size(), "no reply for a command this module does not own");
        assertTrue(clones.spawnCalls.isEmpty(), "no spawn for a command this module does not own");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :module:test --tests "org.vttale.vttale.module.clone.PlayerCloneModuleTest"`
Expected: COMPILATION ERROR — `PlayerCloneService` and `PlayerCloneModule` do not exist.

- [ ] **Step 3: Create the service interface**

Create `module/src/main/java/org/vttale/vttale/module/clone/PlayerCloneService.java`:

```java
package org.vttale.vttale.module.clone;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Platform-side capabilities needed to clone a player's appearance.
 * <p>
 * Implemented by the platform adapter (HytaleTokenBinder on Hytale), which
 * alone may touch the game API: reading a player's skin and spawning the
 * skinned NPC. The pure {@link PlayerCloneModule} owns everything else.
 * <p>
 * The service spawns only — it never creates or binds tokens. The module
 * creates the clone token after a successful spawn, so a failure leaves
 * nothing behind.
 */
public interface PlayerCloneService {

    /**
     * Spawns a humanoid entity wearing the skin of the source player, at the
     * player's current position.
     *
     * @param sourcePlayerUuid the player whose skin and position to clone
     * @return the spawned entity's UUID
     */
    CompletableFuture<UUID> spawnClone(UUID sourcePlayerUuid);

    /**
     * Resolves an online player's username to its UUID.
     *
     * @param playerName the username to resolve
     * @return the player's UUID, or null if unknown or offline
     */
    UUID resolvePlayer(String playerName);

    /**
     * Returns an online player's username.
     *
     * @param playerUuid the player's UUID
     * @return the username, or null if the player is offline
     */
    String playerName(UUID playerUuid);
}
```

- [ ] **Step 4: Create the module**

Create `module/src/main/java/org/vttale/vttale/module/clone/PlayerCloneModule.java`:

```java
package org.vttale.vttale.module.clone;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.events.CommandExecutedEvent;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.events.EventContext;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.token.CoreTokenType;
import org.vttale.vttale.api.token.Token;
import org.vttale.vttale.api.token.TokenRegistry;
import org.vttale.vttale.module.chat.SendMessageEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player clones as static figurines: /clone spawns an entity wearing the
 * source player's skin and tracks it as a token; /unclone removes the token
 * (the platform despawns the bound entity on TokenRemovedEvent).
 * <p>
 * One clone per source player, enforced by a registry lookup
 * (owner + {@link #CLONE_TAG}) plus an in-flight guard: the bus is
 * synchronous but the spawn completes later, so two /clone in a row must not
 * both pass the lookup check.
 */
public class PlayerCloneModule implements Module {

    /** Tag marking clone tokens, used by the one-clone-per-player lookup. */
    public static final String CLONE_TAG = "clone";

    private static final String NAME_SUFFIX = " (clone)";

    private EventBus eventBus;
    private TokenRegistry tokens;
    private PlayerCloneService clones;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "vttale:clone";
    }

    @Override
    public Set<Class<?>> requires() {
        return Set.of(TokenRegistry.class, PlayerCloneService.class);
    }

    @Override
    public void onEnable(Kernel kernel) {
        this.eventBus = kernel.getEventBus();
        this.tokens = kernel.getService(TokenRegistry.class);
        this.clones = kernel.getService(PlayerCloneService.class);
        kernel.getCommandRegistry().registerCommand("clone",
                "Spawn a static clone of a player, e.g. /clone Bob");
        kernel.getCommandRegistry().registerCommand("unclone",
                "Remove a player's clone, e.g. /unclone Bob");
        eventBus.subscribe(CommandExecutedEvent.class, this::onCommand);
    }

    private void onCommand(CommandExecutedEvent event, EventContext context) {
        switch (event.getCommandName()) {
            case "clone" -> onClone(event, context);
            case "unclone" -> onUnclone(event, context);
            default -> { /* not ours */ }
        }
    }

    private void onClone(CommandExecutedEvent event, EventContext context) {
        String[] args = event.getArgs();
        if (args.length > 1) {
            reply(context, "Usage: /clone [player]");
            return;
        }
        UUID source = resolveTarget(args, context);
        if (source == null) {
            return; // reply already sent
        }
        if (pending.contains(source) || findClone(source) != null) {
            reply(context, sourceName(source) + " already has a clone.");
            return;
        }
        pending.add(source);
        clones.spawnClone(source).whenComplete((entityId, error) -> {
            pending.remove(source);
            if (error != null) {
                reply(context, "Clone failed: " + error.getMessage());
                return;
            }
            Token token = tokens.create(sourceName(source) + NAME_SUFFIX,
                    CoreTokenType.PLAYER_CHARACTER, source);
            token.addTag(CLONE_TAG);
            tokens.bindToEntity(token.getId(), entityId);
            reply(context, "Clone spawned.");
        });
    }

    private void onUnclone(CommandExecutedEvent event, EventContext context) {
        String[] args = event.getArgs();
        if (args.length > 1) {
            reply(context, "Usage: /unclone [player]");
            return;
        }
        UUID source = resolveTarget(args, context);
        if (source == null) {
            return;
        }
        Token clone = findClone(source);
        if (clone == null) {
            reply(context, sourceName(source) + " has no clone.");
            return;
        }
        tokens.remove(clone);
        reply(context, "Removed " + sourceName(source) + "'s clone.");
    }

    /**
     * Resolves the clone target: the sender themself when no argument is
     * given, the named player otherwise. Replies and returns null when the
     * target cannot be resolved.
     */
    private UUID resolveTarget(String[] args, EventContext context) {
        if (args.length == 0) {
            if ("CONSOLE".equals(context.getSenderId())) {
                reply(context, "Console must specify a player: /clone <player>");
                return null;
            }
            return UUID.fromString(context.getSenderId());
        }
        UUID resolved = clones.resolvePlayer(args[0]);
        if (resolved == null) {
            reply(context, "No online player named " + args[0]);
        }
        return resolved;
    }

    private Token findClone(UUID source) {
        for (Token token : tokens.getByOwner(source)) {
            if (token.getTags().contains(CLONE_TAG)) {
                return token;
            }
        }
        return null;
    }

    private String sourceName(UUID source) {
        String name = clones.playerName(source);
        return name != null ? name : source.toString();
    }

    private void reply(EventContext context, String message) {
        eventBus.publish(new SendMessageEvent(message, context.getSenderId()), context);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :module:test --tests "org.vttale.vttale.module.clone.PlayerCloneModuleTest"`
Expected: all 10 tests PASS. If `spawnInFlightIsDeduplicated` fails with two replies, the second `/clone` ran before the guard was set — check that `pending.add(source)` happens before `spawnClone` is invoked (it does, in Step 4; do not "fix" it by moving the add into the callback).

- [ ] **Step 6: Run the whole module suite (no regressions)**

Run: `./gradlew :module:test`
Expected: BUILD SUCCESSFUL, zero failures (existing chat/dice/token tests still pass).

- [ ] **Step 7: Commit**

```bash
git add module/src/main/java/org/vttale/vttale/module/clone module/src/test/java/org/vttale/vttale/module/clone
git commit -m "feat(module): player clone module with /clone and PlayerCloneService"
```

---

### Task 2: `/unclone` tests are already in Task 1 — verify and skip

**Files:** none.

The `/unclone` happy path and its no-clone branch ship in Task 1 (`onUnclone` in `PlayerCloneModule`) but lack dedicated tests there. Add them now — reviewer gate on their own cycle:

- [ ] **Step 1: Add the failing tests** to `PlayerCloneModuleTest`:

```java
    @Test
    @DisplayName("/unclone removes the clone token")
    void uncloneRemovesToken() {
        run("clone", PLAYER);
        out.clear();

        run("unclone", PLAYER, "Bob");

        assertEquals("[VTT] Removed Bob's clone.", lastMessage());
        assertTrue(tokens().getByOwner(PLAYER_ID).stream()
                .noneMatch(t -> t.getTags().contains(PlayerCloneModule.CLONE_TAG)));
    }

    @Test
    @DisplayName("/unclone without an existing clone says so")
    void uncloneWithoutClone() {
        run("unclone", PLAYER);

        assertEquals("[VTT] Bob has no clone.", lastMessage());
    }
```

- [ ] **Step 2: Run** `./gradlew :module:test --tests "org.vttale.vttale.module.clone.PlayerCloneModuleTest"`
Expected: PASS (the implementation from Task 1 already covers these branches — if either fails, fix `onUnclone`, not the test).

- [ ] **Step 3: Commit**

```bash
git add module/src/test/java/org/vttale/vttale/module/clone/PlayerCloneModuleTest.java
git commit -m "test(module): cover /unclone happy path and missing-clone branch"
```

---

### Task 3: `HytaleTokenBinder` implements `PlayerCloneService` (platform)

**Files:**
- Modify: `platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/HytaleTokenBinder.java`

**Interfaces:**
- Consumes: `PlayerCloneService` (Task 1, exact signatures above). Hytale API (verified in decompiled sources 0.6.x; re-verify with `javap` before writing):
  - `Universe.get().getPlayer(UUID)` → `PlayerRef` or null; `PlayerRef.getTransform()` → `.getPosition()` (`Vector3dc`), `.getUsername()`.
  - `Universe.get().getPlayerByUsername(String, NameMatching.EXACT)` → `PlayerRef` or null (`com.hypixel.hytale.server.core.NameMatching`).
  - `World.getEntityStore().getStore()` → `Store<EntityStore>`; `World.getEntity(UUID)` → `Entity` or null; `Entity.getReference()` → `Ref<EntityStore>`.
  - `store.getComponent(ref, PlayerSkinComponent.getComponentType())` → `PlayerSkinComponent` or null; `.getPlayerSkin()` → `PlayerSkin`; copy-ctor `new PlayerSkin(PlayerSkin)`.
  - `CosmeticsModule.get().createModel(PlayerSkin)` → `Model`.
  - `NPCPlugin.get().getIndex(String)` → int (invalid name resolves to an index whose role lookup fails); `NPCPlugin.get().spawnEntity(store, roleIndex, position, rotation, model, postSpawn)` → `Pair<Ref<EntityStore>, NPCEntity>` or **null** (invalid role); postSpawn is `TriConsumer<NPCEntity, Ref<EntityStore>, Store<EntityStore>>`.
  - `store.getComponent(ref, UUIDComponent.getComponentType()).getUuid()` → entity UUID (as in `NPCSpawnCommand`).
  - `NPCPlugin.get().getRoleTemplateNames(true)` → `List<String>`.
- Produces: `HytaleTokenBinder implements PlayerCloneService`; the service is registered in `onEnable`. `onTokenRemoved` now despawns the bound non-player entity (this is what makes `/unclone` remove the figurine). Constant `NPC_ROLE_NAME` (placeholder value until in-game validation).

- [ ] **Step 1: Make `HytaleTokenBinder` implement the service and register it**

In `HytaleTokenBinder.java` — change the class declaration and imports:

```java
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.core.protocol.PlayerSkin; // adjust import to com.hypixel.hytale.protocol.PlayerSkin if that is the resolved package
import org.vttale.vttale.module.clone.PlayerCloneService;
```

(Delete that last-but-one line if the compiler complains; the correct package per the decompiled sources is `com.hypixel.hytale.protocol.PlayerSkin`. Resolve at compile time, keep exactly one import.)

Class declaration and fields:

```java
public class HytaleTokenBinder implements Module, PlayerCloneService {

    // Set after the first in-game validation of /clone: pick a spawnable
    // humanoid role from Server/NPC/Roles. Wrong value = /clone fails with
    // "unknown NPC role" and logs the valid names.
    private static final String NPC_ROLE_NAME = "Humanoid";
```

In `onEnable(Kernel kernel)`, right after the existing token-event subscriptions:

```java
        // Other modules (PlayerCloneModule) spawn skinned figurines through us.
        kernel.registerService(PlayerCloneService.class, this);

        // Discovery aid until NPC_ROLE_NAME is validated in game.
        try {
            LOGGER.info("Spawnable NPC roles: " + String.join(", ",
                    NPCPlugin.get().getRoleTemplateNames(true)));
        } catch (RuntimeException e) {
            LOGGER.warning("Could not list NPC roles (NPC plugin not loaded yet?): " + e.getMessage());
        }
```

- [ ] **Step 2: Implement the three service methods**

Add to `HytaleTokenBinder`:

```java
    // ==================== PlayerCloneService ====================

    @Override
    public CompletableFuture<UUID> spawnClone(UUID sourcePlayerUuid) {
        CompletableFuture<UUID> future = new CompletableFuture<>();

        PlayerRef player = Universe.get().getPlayer(sourcePlayerUuid);
        if (player == null) {
            future.completeExceptionally(
                    new IllegalArgumentException("No online player with uuid " + sourcePlayerUuid));
            return future;
        }

        World world = Universe.get().getDefaultWorld();
        world.execute(() -> {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                Entity source = world.getEntity(sourcePlayerUuid);
                if (source == null) {
                    future.completeExceptionally(new IllegalStateException("Source entity not found"));
                    return;
                }
                PlayerSkinComponent skinComponent = store.getComponent(
                        source.getReference(), PlayerSkinComponent.getComponentType());
                if (skinComponent == null) {
                    future.completeExceptionally(
                            new IllegalStateException("Player has no skin component"));
                    return;
                }
                // Defensive copy: the component may be mutated by a later skin update.
                PlayerSkin skin = new PlayerSkin(skinComponent.getPlayerSkin());
                Model model = CosmeticsModule.get().createModel(skin);

                Vector3d position = new Vector3d(player.getTransform().getPosition());
                int roleIndex = NPCPlugin.get().getIndex(NPC_ROLE_NAME);
                var pair = NPCPlugin.get().spawnEntity(store, roleIndex, position, null, model,
                        (npc, ref, entityStore) -> entityStore.putComponent(ref,
                                PlayerSkinComponent.getComponentType(),
                                new PlayerSkinComponent(skin)));
                if (pair == null) {
                    future.completeExceptionally(new IllegalStateException(
                            "Unknown NPC role \"" + NPC_ROLE_NAME + "\". Available: "
                                    + NPCPlugin.get().getRoleTemplateNames(true)));
                    return;
                }
                future.complete(store.getComponent(pair.first(),
                        UUIDComponent.getComponentType()).getUuid());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        return future;
    }

    @Override
    public UUID resolvePlayer(String playerName) {
        PlayerRef player = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT);
        return player != null ? player.getUuid() : null;
    }

    @Override
    public String playerName(UUID playerUuid) {
        PlayerRef player = Universe.get().getPlayer(playerUuid);
        return player != null ? player.getUsername() : null;
    }
```

- [ ] **Step 3: Despawn the figurine when its token is removed**

Replace the body of `onTokenRemoved` (it currently only logs) — `/unclone` removes the token and this handler is what makes the entity disappear:

```java
    private void onTokenRemoved(TokenRemovedEvent event, EventContext context) {
        if (disabled) {
            return;
        }
        // A removed token means a removed figurine: despawn its bound entity.
        // Player entities are never despawned this way.
        event.getBoundEntityId().ifPresent(entityId -> {
            World world = event.getToken()
                    .map(this::getWorldForToken)
                    .orElseGet(Universe.get()::getDefaultWorld);
            world.execute(() -> {
                Entity entity = world.getEntity(entityId);
                if (entity != null && !(entity instanceof Player)) {
                    entity.remove();
                    LOGGER.fine("Despawned entity " + entityId
                            + " for removed token " + event.getTokenName());
                }
            });
        });
    }
```

- [ ] **Step 4: Compile the platform**

Run: `./gradlew :platform:hytale:compileJava`
Expected: BUILD SUCCESSFUL. If a Hytale signature differs from the decompiled sources (e.g. `spawnEntity` overload), fix the call site per `javap -cp` against the resolved Server jar (`~/.gradle/caches/modules-2/files-2.1/com.hypixel.hytale/Server/<v>/...`) — do not guess.

- [ ] **Step 5: Run the module suite (imports of `module` from `platform` must not affect it)**

Run: `./gradlew :module:test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/HytaleTokenBinder.java
git commit -m "feat(platform): HytaleTokenBinder spawns skinned clones via PlayerCloneService"
```

---

### Task 4: Register the module, update docs, full gate

**Files:**
- Modify: `platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/VTTaleHytalePlugin.java`
- Modify: `docs/architecture.md`
- Modify: `docs/architecture.fr.md`

**Interfaces:**
- Consumes: `PlayerCloneModule` (Task 1, constructor takes nothing).
- Produces: nothing new — the boot registers one more module.

- [ ] **Step 1: Register the module in `setup()`**

In `VTTaleHytalePlugin.java`, add the import:

```java
import org.vttale.vttale.module.clone.PlayerCloneModule;
```

and one registration line after `registerModule(new TokenModule());`:

```java
        modules.registerModule(new TokenModule());
        modules.registerModule(new PlayerCloneModule());
```

Registration order does not matter: `PlayerCloneModule` declares `requires() = {TokenRegistry, PlayerCloneService}` and the registry parks it until both exist.

- [ ] **Step 2: Update the architecture docs (both languages)**

In `docs/architecture.md` and `docs/architecture.fr.md`, find the command list (search for `/roll` with Grep). Add to the English one:

```markdown
- `/clone [player]` — spawn a static figurine wearing the player's skin (one clone per player); `/unclone [player]` — remove it (module `clone`, token tagged `clone`).
```

and to the French one:

```markdown
- `/clone [joueur]` — crée une figurine statique portant le skin du joueur (un seul clone par joueur) ; `/unclone [joueur]` — la supprime (module `clone`, token taggé `clone`).
```

Also mention the new module in the module list of each doc if one exists (same `[VTT]`-adjacent section as `chat`, `diceroll`, `token`): `clone` — player-clone figurines / figurines clonant un joueur.

- [ ] **Step 3: Full test + build gate**

Run: `./gradlew :api:test :kernel:test :module:test :gamesystem:test`
Expected: BUILD SUCCESSFUL, zero failures.

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Deployable JAR refreshed at `platform/hytale/build/libs/VTTale-<version>-all.jar`.

- [ ] **Step 4: Commit**

```bash
git add platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/VTTaleHytalePlugin.java docs/architecture.md docs/architecture.fr.md
git commit -m "feat(platform): register clone module, document /clone and /unclone"
```

- [ ] **Step 5: In-game validation (manual, not automatable)**

Copy `platform/hytale/build/libs/VTTale-<version>-all.jar` to `%APPDATA%\Hytale\UserData\Mods`, boot a server with two clients (or one + console), then:
1. Boot log shows `Spawnable NPC roles: ...` — set `NPC_ROLE_NAME` in `HytaleTokenBinder` to a spawnable humanoid role name from that list; rebuild and redeploy.
2. `/clone` — a figurine with your skin appears at your position; `/clone` again → "already has a clone"; `/clone <other player>` works; `/unclone` removes the figurine.
3. Fix `NPC_ROLE_NAME`, commit the final value:

```bash
git add platform/hytale/src/main/java/org/vttale/vttale/platform/hytale/HytaleTokenBinder.java
git commit -m "fix(platform): set validated NPC role name for player clones"
```

---

## Self-Review

- **Spec coverage:** commandes `/clone`/`/unclone` + cible par pseudo (Task 1), règle 1-max + garde in-flight (Task 1), spawn-first / pas de token orphelin (Task 1 `whenComplete`), interface `PlayerCloneService` trois méthodes (Task 1), impl binder + recette skin + `registerService` (Task 3), despawn réactif sur `TokenRemovedEvent` (Task 3 — c'est l'implémentation concrète du « `/unclone` = despawn + remove » de la spec, sans grossir l'interface), parking via `requires()` (Task 1 `requires` + Task 4 note), log des rôles + constante `NPC_ROLE_NAME` (Tasks 3 et 4 étape 5), gestion d'erreurs table (Tests Task 1), docs architecture FR/EN (Task 4). Couvert.
- **Placeholder scan:** `NPC_ROLE_NAME = "Humanoid"` est une valeur provisoire assumée par la spec (« point ouvert ») avec une procédure de validation explicite (Task 4, étape 5) — pas un TBD. Aucun autre TODO.
- **Type consistency:** `spawnClone(UUID) -> CompletableFuture<UUID>`, `resolvePlayer(String) -> UUID`, `playerName(UUID) -> String`, `CLONE_TAG = "clone"`, id `"vttale:clone"` — identiques entre Task 1 (définition), Task 3 (impl) et Task 4 (enregistrement). Les messages de test correspondent aux chaînes du module (`[VTT] ` préfixé par ChatModule).
