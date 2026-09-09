package org.vttale.vttale.module.diceroll;

import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.events.EventContext;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Default {@link DiceService}: rolls real dice and publishes every roll as a
 * {@link DiceRolledEvent} on the bus so any module can react.
 */
public class SimpleDiceService implements DiceService {

    private final RandomGenerator random;
    private final EventBus eventBus;

    public SimpleDiceService(EventBus eventBus) {
        this(eventBus, RandomGenerator.getDefault());
    }

    public SimpleDiceService(EventBus eventBus, RandomGenerator random) {
        this.eventBus = eventBus;
        this.random = random;
    }

    @Override
    public DiceRolledEvent roll(int count, int sides, int modifier) {
        if (count < 1 || sides < 1) {
            throw new IllegalArgumentException("Need at least 1 die with at least 1 side");
        }

        List<Integer> rolls = new ArrayList<>(count);
        int total = modifier;
        for (int i = 0; i < count; i++) {
            int value = random.nextInt(1, sides + 1);
            rolls.add(value);
            total += value;
        }

        DiceRolledEvent event =
                new DiceRolledEvent(notation(count, sides, modifier), List.copyOf(rolls), modifier, total);
        eventBus.publish(event, new EventContext("KERNEL"));
        return event;
    }

    /** Canonical notation of a roll, e.g. "2d6+3" — same for every caller. */
    private static String notation(int count, int sides, int modifier) {
        StringBuilder sb = new StringBuilder().append(count).append('d').append(sides);
        if (modifier > 0) {
            sb.append('+').append(modifier);
        } else if (modifier < 0) {
            sb.append('-').append(-modifier);
        }
        return sb.toString();
    }
}
