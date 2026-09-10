package org.vttale.vttale.module.diceroll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.kernel.events.SimpleEventBus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dice are the one place where a silent bug is invisible to players, so the invariants are
 * asserted structurally (bounds, sum, size) and the randomness is seeded for reproducibility.
 */
class SimpleDiceServiceTest {

    private SimpleEventBus bus;
    private List<DiceRolledEvent> published;

    @BeforeEach
    void setUp() {
        bus = new SimpleEventBus();
        published = new ArrayList<>();
        bus.subscribe(DiceRolledEvent.class, (e, ctx) -> published.add(e));
    }

    private SimpleDiceService seeded(long seed) {
        return new SimpleDiceService(bus, new Random(seed));
    }

    @Test
    @DisplayName("every die lands within [1, sides] and the total is the sum plus the modifier")
    void rollInvariants() {
        DiceRolledEvent result = seeded(42).roll(5, 20, 3);

        assertEquals(5, result.rolls().size());
        int sum = 0;
        for (int value : result.rolls()) {
            assertTrue(value >= 1 && value <= 20, "die out of range: " + value);
            sum += value;
        }
        assertEquals(sum + 3, result.total());
        assertEquals(3, result.modifier());
    }

    @Test
    @DisplayName("notation is rebuilt canonically, including the modifier sign")
    void notationFormatting() {
        SimpleDiceService dice = seeded(1);
        assertEquals("1d20", dice.roll(1, 20, 0).notation());
        assertEquals("2d6+3", dice.roll(2, 6, 3).notation());
        assertEquals("2d6-1", dice.roll(2, 6, -1).notation());
    }

    @Test
    @DisplayName("a d1 is deterministic")
    void singleSidedDie() {
        DiceRolledEvent result = seeded(7).roll(3, 1, 0);
        assertEquals(List.of(1, 1, 1), result.rolls());
        assertEquals(3, result.total());
    }

    @Test
    @DisplayName("the same seed produces the same roll")
    void deterministicForAGivenSeed() {
        assertEquals(seeded(99).roll(10, 6, 0).rolls(), seeded(99).roll(10, 6, 0).rolls());
    }

    @Test
    @DisplayName("every face of a d6 is reachable")
    void allFacesAreReachable() {
        SimpleDiceService dice = seeded(2024);
        Set<Integer> seen = new HashSet<>(dice.roll(100, 6, 0).rolls());
        assertEquals(Set.of(1, 2, 3, 4, 5, 6), seen);
    }

    @Test
    @DisplayName("the roll is published on the bus, and it is the very object returned")
    void publishesOnTheBus() {
        DiceRolledEvent result = seeded(5).roll(2, 6, 0);

        assertEquals(1, published.size());
        assertSame(result, published.getFirst());
    }

    @Test
    @DisplayName("degenerate dice are rejected")
    void rejectsDegenerateDice() {
        SimpleDiceService dice = seeded(1);
        assertThrows(IllegalArgumentException.class, () -> dice.roll(0, 6, 0));
        assertThrows(IllegalArgumentException.class, () -> dice.roll(-1, 6, 0));
        assertThrows(IllegalArgumentException.class, () -> dice.roll(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> dice.roll(1, -6, 0));
        assertEquals(0, published.size(), "a rejected roll must not reach the bus");
    }

    @Test
    @DisplayName("the returned roll list cannot be tampered with")
    void rollsAreImmutable() {
        DiceRolledEvent result = seeded(1).roll(2, 6, 0);
        assertThrows(UnsupportedOperationException.class, () -> result.rolls().add(99));
    }
}
