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
