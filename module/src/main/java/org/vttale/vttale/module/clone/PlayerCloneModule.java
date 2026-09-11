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
