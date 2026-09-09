package org.vttale.vttale.module.diceroll;

import org.vttale.vttale.api.events.Event;

import java.util.List;

/**
 * Published on the bus after a successful roll, whatever the caller
 * (command or programmatic {@link DiceService#roll}).
 *
 * @param notation the canonical notation of the roll, e.g. "2d6+3"
 * @param rolls    one value per die
 * @param modifier flat bonus applied after summing the dice
 * @param total    sum of rolls + modifier
 */
public record DiceRolledEvent(String notation, List<Integer> rolls, int modifier, int total) implements Event {
}
