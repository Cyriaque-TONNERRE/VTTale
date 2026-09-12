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
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(PlayerCloneModule.class.getName());

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
        // Atomic claim: the completion callback runs on the Hytale world thread
        // (World.execute always queues), so check-then-act against the registry
        // would race a concurrent /clone on the command thread.
        if (!pending.add(source)) {
            reply(context, sourceName(source) + " already has a clone.");
            return;
        }
        Token existing = findClone(source);
        if (existing != null) {
            pending.remove(source);
            reply(context, sourceName(source) + " already has a clone.");
            return;
        }
        // The completion callback intentionally runs on the world thread
        // (World.execute queues): the registry and the bus are thread-safe.
        clones.spawnClone(source).whenComplete((entityId, error) -> {
            try {
                if (error != null) {
                    reply(context, "Clone failed: "
                            + (error.getMessage() != null ? error.getMessage() : error.toString()));
                    return;
                }
                Token token = tokens.create(sourceName(source) + NAME_SUFFIX,
                        CoreTokenType.PLAYER_CHARACTER, source);
                token.addTag(CLONE_TAG);
                tokens.bindToEntity(token.getId(), entityId);
                reply(context, "Clone spawned.");
            } catch (Throwable t) {
                // Logged, never silent: a failure here would otherwise leave a
                // spawned entity untracked (no token, no reply).
                LOGGER.log(Level.WARNING, "Clone completion failed", t);
                reply(context, "Clone failed: "
                        + (t.getMessage() != null ? t.getMessage() : t.toString()));
            } finally {
                pending.remove(source);
            }
        });
    }

    private void onUnclone(CommandExecutedEvent event, EventContext context) {
        String[] args = event.getArgs();
        if (args.length > 1) {
            reply(context, "Usage: /unclone [player]");
            return;
        }
        if (args.length == 1) {
            UUID source = clones.resolvePlayer(args[0]);
            if (source == null) {
                // Offline source: the figurine must stay removable, so fall back
                // to the exact clone-token name instead of giving up.
                Token byName = findCloneByName(args[0]);
                if (byName == null) {
                    reply(context, args[0] + " has no clone.");
                    return;
                }
                tokens.remove(byName);
                reply(context, "Removed " + args[0] + "'s clone.");
                return;
            }
            removeClone(context, source);
            return;
        }
        UUID source = resolveTarget(args, context);
        if (source == null) {
            return; // console without argument: reply already sent
        }
        removeClone(context, source);
    }

    private void removeClone(EventContext context, UUID source) {
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

    // ponytail: exact-name match; enough for /unclone
    private Token findCloneByName(String playerName) {
        for (Token token : tokens.getAll()) {
            if (token.getTags().contains(CLONE_TAG)
                    && token.getName().equals(playerName + NAME_SUFFIX)) {
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
