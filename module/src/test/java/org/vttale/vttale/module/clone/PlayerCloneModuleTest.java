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
        boot(new FakeCloneService());
        clones.players.put("Bob", PLAYER_ID);
        clones.names.put(PLAYER_ID, "Bob");
    }

    /**
     * Boots a fresh kernel around the given service. A swapped-in service
     * needs a fresh kernel because a module captured the previous one at
     * enable time, and the registry refuses a second module with the id
     * "vttale:clone" — a plain re-registration would be silently ignored.
     */
    private void boot(FakeCloneService service) {
        kernel = new VTTaleKernel();
        out = new ArrayList<>();
        kernel.getEventBus().subscribe(PlatformBroadcastEvent.class, (e, ctx) -> out.add(e));
        kernel.getModuleRegistry().registerModule(new ChatModule());
        kernel.getModuleRegistry().registerModule(new TokenModule());
        clones = service;
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
        boot(clones);

        run("clone", PLAYER);
        run("clone", PLAYER);
        assertEquals(1, clones.spawnCalls.size(), "the second /clone must not spawn again");
        assertEquals(1, out.size(), "the second /clone is answered while the spawn is in flight");
        out.clear();

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
    @DisplayName("a failed spawn releases the in-flight guard: /clone can be retried")
    void retryAfterFailedSpawn() {
        clones.failNextSpawn = true;
        run("clone", PLAYER);
        assertEquals("[VTT] Clone failed: boom", lastMessage());

        clones.failNextSpawn = false;
        out.clear();
        run("clone", PLAYER);

        assertEquals("[VTT] Clone spawned.", lastMessage());
        assertEquals(1, tokens().getByOwner(PLAYER_ID).size());
    }

    @Test
    @DisplayName("a messageless failure reports the throwable class, not \"null\"")
    void failedSpawnWithMessagelessThrowable() {
        clones = new FakeCloneService() {
            @Override
            public CompletableFuture<UUID> spawnClone(UUID sourcePlayerUuid) {
                spawnCalls.add(sourcePlayerUuid);
                return CompletableFuture.failedFuture(new NullPointerException());
            }
        };
        boot(clones);

        run("clone", PLAYER);

        assertEquals("[VTT] Clone failed: java.lang.NullPointerException", lastMessage());
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
        boot(clones);

        run("clone", PLAYER);

        Token token = tokens().getByOwner(PLAYER_ID).stream().findFirst().orElseThrow();
        assertEquals(PLAYER_ID + " (clone)", token.getName());
    }

    @Test
    @DisplayName("other commands are ignored")
    void ignoresOtherCommands() {
        run("roll", PLAYER, "2d6");
        assertEquals(0, out.size(), "no reply for a command this module does not own");
        assertTrue(clones.spawnCalls.isEmpty(), "no spawn for a command this module does not own");
    }

    @Test
    @DisplayName("/unclone removes the clone token")
    void uncloneRemovesToken() {
        run("clone", PLAYER);
        out.clear();

        run("unclone", PLAYER, "Bob");

        assertEquals("[VTT] Removed Bob's clone.", lastMessage());
        assertEquals(0, tokens().getByOwner(PLAYER_ID).size(),
                "the clone token must be gone, not just untagged");
        assertTrue(tokens().getByOwner(PLAYER_ID).stream()
                .noneMatch(t -> t.getTags().contains(PlayerCloneModule.CLONE_TAG)));
    }

    @Test
    @DisplayName("/unclone without an existing clone says so")
    void uncloneWithoutClone() {
        run("unclone", PLAYER);

        assertEquals("[VTT] Bob has no clone.", lastMessage());
    }

    @Test
    @DisplayName("/unclone removes the figurine of an offline source via the exact token name")
    void uncloneOfflineSourceByExactTokenName() {
        // Simulate Bob being offline: resolvePlayer returns null on this fake.
        // A fresh kernel is needed because the module captured the previous
        // service at enable time; it has no memory of any spawn, so the clone
        // token is created directly through the real registry.
        boot(new FakeCloneService());

        TokenRegistry tokens = tokens();
        Token token = tokens.create("Bob (clone)", CoreTokenType.PLAYER_CHARACTER, PLAYER_ID);
        token.addTag(PlayerCloneModule.CLONE_TAG);
        tokens.bindToEntity(token.getId(), ENTITY);
        assertEquals(1, tokens.getByOwner(PLAYER_ID).size());

        run("unclone", PLAYER, "Bob");

        assertEquals("[VTT] Removed Bob's clone.", lastMessage());
        assertEquals(0, tokens().getByOwner(PLAYER_ID).size(),
                "the offline player's clone token must be gone");
    }
}
