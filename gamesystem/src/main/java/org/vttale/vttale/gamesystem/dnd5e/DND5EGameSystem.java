package org.vttale.vttale.gamesystem.dnd5e;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.gamesystem.GameSystem;
import org.vttale.vttale.api.token.TokenComponent;

import java.util.Set;

/**
 * Example game system: the D&D 5e ruleset skeleton. Gameplay (StatBlock,
 * character sheet components, behaviors) is added on top of this contract.
 */
public class DND5EGameSystem implements GameSystem {

    @Override
    public String id() {
        return "dnd5e";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public Set<Class<? extends TokenComponent>> components() {
        return Set.of();
    }

    @Override
    public void onEnable(Kernel kernel) {
        // DiceService pattern: the capability is a service registered by its module.
        kernel.registerService(GameSystem.class, this);
    }
}
