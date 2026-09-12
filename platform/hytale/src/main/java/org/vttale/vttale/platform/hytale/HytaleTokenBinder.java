package org.vttale.vttale.platform.hytale;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.events.EventContext;
import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.token.Token;
import org.vttale.vttale.api.token.TokenPosition;
import org.vttale.vttale.api.token.TokenRegistry;
import org.vttale.vttale.api.token.events.TokenBoundEvent;
import org.vttale.vttale.api.token.events.TokenRemovedEvent;
import org.vttale.vttale.api.token.events.TokenUpdatedEvent;
import org.vttale.vttale.module.clone.PlayerCloneService;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Handles synchronization between VTTale tokens and Hytale entities.
 * <p>
 * This class bridges the gap between the platform-agnostic Token system
 * and the Hytale-specific entity system.
 * <p>
 * Responsibilities:<br />
 * - Creating Hytale entities when tokens are spawned<br />
 * - Synchronizing token data to entities (position, name, etc.)<br />
 * - Managing the token-entity binding lifecycle<br />
 * - Despawning a token's bound non-player entity when the token is removed
 * (TokenRemovedEvent)<br />
 * - Spawning skinned player clones ({@link PlayerCloneService}, consumed by
 * PlayerCloneModule)
 * <p>
 * Thread Safety:
 * All Hytale entity access is performed via world.execute() to ensure
 * thread safety with Hytale's ECS system.
 */
public class HytaleTokenBinder implements Module, PlayerCloneService {

    private static final Logger LOGGER = Logger.getLogger(HytaleTokenBinder.class.getName());

    // Static role: no AI, the figurine stays put; the model (and thus the
    // appearance) is overridden by the cloned player's skin at spawn time.
    // Validated in game against the role list logged at boot; if /clone ever
    // fails again, the error message lists the valid names.
    private static final String NPC_ROLE_NAME = "Static";

    private final JavaPlugin plugin;
    private TokenRegistry tokenRegistry;
    // Set in onDisable before anything else: the kill switch for the three
    // token-event handlers and spawnClone. Kernel subscriptions cannot be
    // removed at all.
    // Volatile: events arrive from world threads, onDisable runs on another.
    private volatile boolean disabled;

    /**
     * Creates a new HytaleTokenBinder.
     *
     * @param plugin the Hytale plugin instance
     */
    public HytaleTokenBinder(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Set<Class<?>> requires() {
        return Set.of(TokenRegistry.class);
    }

    @Override
    public void onEnable(Kernel kernel) {
        LOGGER.info("Enabling HytaleTokenBinder...");
        tokenRegistry = kernel.getService(TokenRegistry.class);

        EventBus eventBus = kernel.getEventBus();

        // Listen for VTTale token events
        eventBus.subscribe(TokenBoundEvent.class, this::onTokenBound);
        eventBus.subscribe(TokenUpdatedEvent.class, this::onTokenUpdated);
        eventBus.subscribe(TokenRemovedEvent.class, this::onTokenRemoved);

        // Other modules (PlayerCloneModule) spawn skinned figurines through us.
        kernel.registerService(PlayerCloneService.class, this);

        // Discovery aid until NPC_ROLE_NAME is validated in game.
        try {
            LOGGER.info("Spawnable NPC roles: " + String.join(", ",
                    NPCPlugin.get().getRoleTemplateNames(true)));
        } catch (RuntimeException e) {
            LOGGER.warning("Could not list NPC roles (NPC plugin not loaded yet?): " + e.getMessage());
        }

        LOGGER.info("HytaleTokenBinder enabled");
    }

    @Override
    public void onDisable() {
        disabled = true;
        LOGGER.info("HytaleTokenBinder disabled");
    }

    // ==================== VTTale Token Events ====================

    /**
     * Handles token binding/unbinding events.
     */
    private void onTokenBound(TokenBoundEvent event, EventContext context) {
        if (disabled) {
            return;
        }
        Token token = event.getToken();

        // Handles token binding/unbinding; logs and syncs as needed
        if (event.isBound()) {
            UUID entityId = event.getEntityId().orElse(null);
            if (entityId != null) {
                LOGGER.fine("Token " + token.getName() + " bound to entity " + entityId);
                syncTokenToEntity(token, entityId);
            }
        } else if (event.isUnbound()) {
            UUID previousEntityId = event.getPreviousEntityId().orElse(null);
            if (previousEntityId != null) {
                LOGGER.fine("Token " + token.getName() + " unbound from entity " + previousEntityId);
            }
        }
    }

    /**
     * Handles token update events.
     */
    private void onTokenUpdated(TokenUpdatedEvent event, EventContext context) {
        if (disabled) {
            return;
        }
        Token token = event.getToken();

        // Only sync if token is bound to an entity
        token.getBoundEntityId().ifPresent(entityId -> {
            switch (event.getUpdateType()) {
                case POSITION_CHANGED -> syncPositionToEntity(token, entityId);
                case NAME_CHANGED -> syncNameToEntity(token, entityId);
                case COMPONENT_UPDATED, COMPONENT_ADDED -> {
                    // Could sync specific component data if needed
                }
                default -> { /* No sync needed */ }
            }
        });
    }

    /**
     * Handles token removal events.
     */
    private void onTokenRemoved(TokenRemovedEvent event, EventContext context) {
        if (disabled) {
            return;
        }
        // A removed token means a removed figurine: despawn its bound entity.
        // Player entities are never despawned this way.
        event.getBoundEntityId().ifPresent(entityId -> {
            // Unknown world id falls back to the default world; getEntity returns null there and nothing is despawned.
            World world = event.getToken()
                    .filter(t -> t.getWorldId().isPresent())
                    .map(this::getWorldForToken)
                    .orElseGet(Universe.get()::getDefaultWorld);
            if (world == null) {
                LOGGER.warning("Cannot despawn entity " + entityId
                        + " for removed token " + event.getTokenName() + ": world not found");
                return;
            }
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

    // ==================== Synchronization ====================

    /**
     * Synchronizes all token data to a Hytale entity.
     */
    private void syncTokenToEntity(Token token, UUID entityId) {
        World world = getWorldForToken(token);
        if (world == null) return;

        world.execute(() -> {
            Entity entity = world.getEntity(entityId);
            if (entity == null) {
                LOGGER.warning("Cannot sync token to entity: entity " + entityId + " not found");
                return;
            }

            // Sync position
            token.getPosition().ifPresent(pos -> {
                entity.moveTo(
                        entity.getReference(),
                        pos.getX(),
                        pos.getY(),
                        pos.getZ(),
                        world.getEntityStore().getStore()
                );
            });

            LOGGER.fine("Synced token " + token.getName() + " to entity " + entityId);
        });
    }

    /**
     * Synchronizes token position to entity.
     */
    private void syncPositionToEntity(Token token, UUID entityId) {
        World world = getWorldForToken(token);
        if (world == null) return;

        token.getPosition().ifPresent(pos -> {
            world.execute(() -> {
                Entity entity = world.getEntity(entityId);
                if (entity != null) {
                    entity.moveTo(
                            entity.getReference(),
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            world.getEntityStore().getStore()
                    );
                }
            });
        });
    }

    /**
     * Synchronizes token name to entity (for entities that support display names).
     */
    private void syncNameToEntity(Token token, UUID entityId) {
        // Most entities don't have settable display names, but we can
        // update this for custom NPCs when the API supports it
        LOGGER.fine("Name sync requested for " + token.getName() + " (not implemented yet)");
    }

    // ==================== Public API ====================

    /**
     * Spawns a Hytale entity for a token.
     *
     * @param token      the token to spawn
     * @param entityType the Hytale entity type ID
     * @return a future that completes with the entity UUID
     */
    public CompletableFuture<UUID> spawnEntityForToken(Token token, String entityType) {
        CompletableFuture<UUID> future = new CompletableFuture<>();

        World world = getWorldForToken(token);
        if (world == null) {
            future.completeExceptionally(new IllegalStateException("No world available"));
            return future;
        }

        TokenPosition pos = token.getPosition().orElse(TokenPosition.origin());

        world.execute(() -> {
            try {
                Vector3d position = new Vector3d(pos.getX(), pos.getY(), pos.getZ());
                Vector3f rotation = new Vector3f(pos.getPitch(), pos.getYaw(), 0);

                // TODO: Use Hytale's entity registry to spawn the correct entity type
                // Entity entity = world.spawnEntity(entityType, position, rotation);
                // tokenRegistry.bindToEntity(token.getId(), entity.getUuid());
                // future.complete(entity.getUuid());

                LOGGER.info("Would spawn entity type " + entityType + " at " + position);
                future.completeExceptionally(new UnsupportedOperationException(
                        "Entity spawning not yet fully implemented - waiting for Hytale API"));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Spawns an entity for a token at a specific position (simplified version).
     *
     * @param token    the token to spawn
     * @param position the position to spawn at
     * @throws UnsupportedOperationException always — entity spawning is not yet implemented
     */
    public UUID spawnEntityForToken(Token token, TokenPosition position) {
        throw new UnsupportedOperationException("Entity spawning not yet implemented - waiting for Hytale API");
    }

    /**
     * Despawns the entity bound to a token.
     *
     * @param token the token to despawn
     * @return true if the entity was despawned
     */
    public boolean despawnEntityForToken(Token token) {
        Objects.requireNonNull(tokenRegistry, "binder not enabled");
        UUID entityId = token.getBoundEntityId().orElse(null);
        if (entityId == null) {
            return false;
        }

        World world = getWorldForToken(token);
        if (world != null) {
            world.execute(() -> {
                Entity entity = world.getEntity(entityId);
                if (entity != null && !(entity instanceof Player)) {
                    // Don't despawn players
                    entity.remove();
                    LOGGER.fine("Despawned entity " + entityId + " for token " + token.getName());
                }
            });
        }

        // Unbind the token
        tokenRegistry.unbindFromEntity(token.getId());
        LOGGER.info("Despawned entity for token: " + token.getName());

        return true;
    }

    // ==================== PlayerCloneService ====================

    @Override
    public CompletableFuture<UUID> spawnClone(UUID sourcePlayerUuid) {
        CompletableFuture<UUID> future = new CompletableFuture<>();

        if (disabled) {
            future.completeExceptionally(new IllegalStateException("VTTale is shutting down"));
            return future;
        }

        try {
            PlayerRef player = Universe.get().getPlayer(sourcePlayerUuid);
            if (player == null) {
                future.completeExceptionally(
                        new IllegalArgumentException("No online player with uuid " + sourcePlayerUuid));
                return future;
            }
            World world = Universe.get().getDefaultWorld();
            if (world == null) {
                // Guarded before world.execute so the future can never be left pending.
                future.completeExceptionally(new IllegalStateException("No world available"));
                return future;
            }
            world.execute(() -> {
                try {
                    Store<EntityStore> store = world.getEntityStore().getStore();
                    Entity source = world.getEntity(sourcePlayerUuid);
                    if (source == null) {
                        future.completeExceptionally(new IllegalStateException(
                                "Source entity not found in the default world"
                                        + " (multi-world is not supported yet)"));
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
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }

        // World.execute only queues: a task queued right before world shutdown would otherwise never complete and leak the caller's in-flight guard.
        return future.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
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

    // ==================== Utility Methods ====================

    /**
     * Gets the Hytale World for a token.
     *
     * @param token the token
     * @return the World, or default world if token has no world set
     */
    private World getWorldForToken(Token token) {
        UUID worldId = token.getWorldId().orElse(null);
        if (worldId == null) {
            return Universe.get().getDefaultWorld();
        }
        return Universe.get().getWorld(worldId);
    }
}