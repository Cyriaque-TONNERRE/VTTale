package org.vttale.vttale.module.token.behavior;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleBehaviorContextTest {

    private SimpleBehaviorContext context;

    @BeforeEach
    void setUp() {
        context = new SimpleBehaviorContext();
    }

    @Test
    @DisplayName("values round-trip and can be removed")
    void roundTrip() {
        context.set("charges", 3);
        assertTrue(context.has("charges"));
        assertEquals(3, context.get("charges").orElseThrow());
        assertTrue(context.remove("charges"));
        assertFalse(context.remove("charges"));
        assertTrue(context.get("charges").isEmpty());
    }

    @Test
    @DisplayName("setting a null value removes the key")
    void nullValueRemoves() {
        context.set("charges", 3);
        context.set("charges", null);
        assertFalse(context.has("charges"));
    }

    @Test
    @DisplayName("KNOWN INCONSISTENCY: set() tolerates a null key but the readers do not")
    void nullKeyHandlingIsAsymmetric() {
        // set() guards against a null key and silently does nothing...
        context.set(null, "value");

        // ...but get/has/remove go straight to a ConcurrentHashMap, which rejects null keys.
        // A behaviour that computes its key can therefore crash on read but not on write.
        // The fix is to guard all four consistently (or none of them).
        assertThrows(NullPointerException.class, () -> context.has(null));
        assertThrows(NullPointerException.class, () -> context.get(null));
        assertThrows(NullPointerException.class, () -> context.remove(null));
    }

    @Test
    @DisplayName("typed getters fall back to the default when absent or wrongly typed")
    void typedGetters() {
        context.set("count", 7);
        context.set("ratio", 1.5);
        context.set("flag", true);
        context.set("label", "orc");

        assertEquals(7, context.getInt("count", -1));
        assertEquals(7L, context.getLong("count", -1L));
        assertEquals(7.0, context.getDouble("count", -1.0), 1e-9);
        assertEquals(1.5, context.getDouble("ratio", -1.0), 1e-9);
        assertEquals(1, context.getInt("ratio", -1), "a double is narrowed, not rejected");
        assertTrue(context.getBoolean("flag", false));
        assertEquals("orc", context.getString("label", "none"));

        assertEquals(-1, context.getInt("missing", -1));
        assertEquals(-1, context.getInt("label", -1), "a String is not a Number");
        assertFalse(context.getBoolean("label", false));
        assertEquals("7", context.getString("count", "none"), "getString uses toString()");
    }

    @Test
    @DisplayName("increment and decrement start from zero")
    void incrementAndDecrement() {
        assertEquals(1, context.increment("uses"));
        assertEquals(2, context.increment("uses"));
        assertEquals(1, context.decrement("uses"));
        assertEquals(-1, context.decrement("fresh"));
    }

    @Test
    @DisplayName("clear empties the context")
    void clear() {
        context.set("a", 1);
        context.set("b", 2);
        context.clear();
        assertFalse(context.has("a"));
        assertFalse(context.has("b"));
    }
}
