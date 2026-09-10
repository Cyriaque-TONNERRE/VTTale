package org.vttale.vttale.api.gamesystem;

import org.vttale.vttale.api.module.Module;
import org.vttale.vttale.api.token.TokenComponent;

import java.util.Set;

/**
 * A ruleset (D&D 5e, Pathfinder 2e, ...) as a {@link Module} carrying its
 * own identity. A game system is a module with lifecycle, commands and
 * event subscriptions, plus metadata that lets the kernel answer
 * "which ruleset is active?".
 * <p>
 * Exactly one game system may be active per server: the module registry
 * refuses a second one before its {@code onEnable} runs. Consumers read the
 * active system with {@code kernel.getService(GameSystem.class)}.
 * <p>
 * <b>An implementation must publish itself</b> in {@link Module#onEnable}:
 * <pre>{@code kernel.registerService(GameSystem.class, this);}</pre>
 * The exclusivity guard is a lookup of that service. A system that skips this step
 * still runs, but the registry cannot see it - and a second system would then be
 * accepted, silently breaking the "exactly one" rule above. The registry logs an
 * error when it detects the omission.
 */
public interface GameSystem extends Module {

    /** Stable ruleset identifier, e.g. {@code "dnd5e"}. Namespace it if collisions ever matter. */
    String id();

    /** Ruleset version. */
    String version();

    /**
     * Token component types this system contributes.
     * <p>
     * Informational only: no kernel behaviour reads this today. It becomes load-bearing
     * when several systems can coexist, or when persistence needs to route codecs.
     */
    Set<Class<? extends TokenComponent>> components();
}
