package org.vttale.vttale.module.diceroll;

/**
 * Dice rolling as a callable service — the command layer is only a notation
 * parser on top of this. Game systems that use dice call it directly:
 * <pre>{@code
 * DiceService dice = kernel.getService(DiceService.class);
 * DiceRolledEvent attack = dice.roll(1, 20, 5);
 * }</pre>
 * Every roll is published as a {@link DiceRolledEvent} on the bus, whatever
 * the caller, so modules react through the bus and never need this package.
 */
public interface DiceService {

    /**
     * Rolls {@code count} dice of {@code sides} faces each and adds
     * {@code modifier} (may be negative).
     *
     * @param count    number of dice, at least 1
     * @param sides    faces per die, at least 1
     * @param modifier flat bonus applied after summing the dice
     * @return the published roll event
     * @throws IllegalArgumentException if count or sides is below 1
     */
    DiceRolledEvent roll(int count, int sides, int modifier);
}
