package org.vttale.vttale.api.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.token.behavior.Behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TokenComponent and Behavior both derive a display name from their id, but with two different
 * capitalisation rules. These tests pin the current behaviour so the inconsistency is visible
 * and cannot drift further.
 */
class DefaultNamingTest {

    private record Comp(String id) implements TokenComponent {
        @Override public String getComponentId() { return id; }
    }

    private record Behav(String id) implements Behavior {
        @Override public String getId() { return id; }
    }

    @Test
    @DisplayName("TokenComponent title-cases every word")
    void componentDisplayName() {
        assertEquals("Hit Points", new Comp("vtt:hit_points").getDisplayName());
        assertEquals("Armour", new Comp("vtt:armour").getDisplayName());
        assertEquals("vtt", new Comp("vtt:hit_points").getNamespace());
    }

    @Test
    @DisplayName("Behavior only capitalises the first word (differs from TokenComponent)")
    void behaviourDisplayName() {
        // Not a typo: Behavior#getDisplayName capitalises the first letter only.
        assertEquals("Melee attack", new Behav("vtt:melee_attack").getDisplayName());
        assertEquals("vtt", new Behav("vtt:melee_attack").getNamespace());
    }

    @Test
    @DisplayName("an id without a namespace falls back to \"unknown\"")
    void unqualifiedIds() {
        assertEquals("unknown", new Comp("armour").getNamespace());
        assertEquals("armour", new Comp("armour").getDisplayName());
        assertEquals("unknown", new Behav("attack").getNamespace());
        assertEquals("attack", new Behav("attack").getDisplayName());
    }

    @Test
    @DisplayName("defaults on TokenComponent are permissive")
    void componentDefaults() {
        Comp c = new Comp("vtt:armour");
        assertTrue(c.isValid());
        assertEquals("vtt:armour", c.getSummary());
        // copy() returns `this` by default: components are assumed immutable. Anything mutable
        // MUST override copy(), otherwise two tokens silently share one instance.
        assertEquals(c, c.copy());
    }
}
