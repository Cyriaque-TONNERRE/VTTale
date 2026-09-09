package org.vttale.vttale.module.diceroll;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Parses and rolls dice notation: {@code NdM[+/-K]}, e.g. "2d6", "1d20+3", "4d8-1".
 *
 * @throws IllegalArgumentException if the notation is invalid
 *         (bad numbers, zero/negative die count or sides)
 */
public final class Dice {

    private final RandomGenerator random;

    public Dice(RandomGenerator random) {
        this.random = random;
    }

    public DiceRolledEvent roll(String notation) {
        String expr = notation.strip().toLowerCase();
        int modifier = 0;
        int plus = expr.indexOf('+');
        int minus = expr.indexOf('-');
        if (plus >= 0) {
            modifier = Integer.parseInt(expr.substring(plus + 1).strip());
            expr = expr.substring(0, plus).strip();
        } else if (minus >= 0) {
            modifier = -Integer.parseInt(expr.substring(minus + 1).strip());
            expr = expr.substring(0, minus).strip();
        }

        int count = 1;
        int d = expr.indexOf('d');
        if (d >= 0) {
            String before = expr.substring(0, d).strip();
            if (!before.isEmpty()) {
                count = Integer.parseInt(before);
            }
            expr = expr.substring(d + 1).strip();
        }
        int sides = Integer.parseInt(expr);
        if (count < 1 || sides < 1) {
            throw new IllegalArgumentException("Need at least 1 die with at least 1 side: " + notation);
        }

        List<Integer> rolls = new ArrayList<>(count);
        int total = modifier;
        for (int i = 0; i < count; i++) {
            int value = random.nextInt(1, sides + 1);
            rolls.add(value);
            total += value;
        }
        return new DiceRolledEvent(notation, List.copyOf(rolls), modifier, total);
    }
}
