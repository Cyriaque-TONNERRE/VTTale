package org.vttale.vttale.module.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.token.CoreTokenType;
import org.vttale.vttale.api.token.TokenComponent;
import org.vttale.vttale.api.token.TokenPosition;
import org.vttale.vttale.api.token.behavior.Behavior;
import org.vttale.vttale.api.token.behavior.BehaviorContext;
import org.vttale.vttale.api.token.Token;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses the registry-less constructor on purpose: this exercises SimpleToken in isolation,
 * without any event being emitted.
 */
class SimpleTokenTest {

    private interface HealthComponent extends TokenComponent { }

    private static final class HitPoints implements HealthComponent {
        final int value;
        HitPoints(int value) { this.value = value; }
        @Override public String getComponentId() { return "vtt:hit_points"; }
    }

    private static final class Armour implements TokenComponent {
        @Override public String getComponentId() { return "vtt:armour"; }
    }

    private static final class RecordingBehavior implements Behavior {
        private final String id;
        private final List<String> log;
        RecordingBehavior(String id, List<String> log) { this.id = id; this.log = log; }
        @Override public String getId() { return id; }
        @Override public void onAttach(Token token, BehaviorContext context) { log.add("attach:" + id); }
        @Override public void onDetach(Token token, BehaviorContext context) { log.add("detach:" + id); }
    }

    private SimpleToken token;
    private List<String> log;

    @BeforeEach
    void setUp() {
        token = new SimpleToken("Goblin", CoreTokenType.MONSTER);
        log = new ArrayList<>();
    }

    @Test
    @DisplayName("a fresh token has an id, timestamps and no optional state")
    void freshToken() {
        assertEquals("Goblin", token.getName());
        assertSame(CoreTokenType.MONSTER, token.getType());
        assertNotNull(token.getId());
        assertEquals(token.getCreatedAt(), token.getLastModifiedAt());
        assertTrue(token.getPosition().isEmpty());
        assertTrue(token.getWorldId().isEmpty());
        assertTrue(token.getOwnerId().isEmpty());
        assertFalse(token.isBoundToEntity());
        assertTrue(token.getTags().isEmpty());
    }

    @Test
    @DisplayName("identity fields reject null")
    void identityRejectsNull() {
        assertThrows(NullPointerException.class, () -> new SimpleToken(null, CoreTokenType.NPC));
        assertThrows(NullPointerException.class, () -> new SimpleToken("x", null));
        assertThrows(NullPointerException.class, () -> token.setName(null));
        assertThrows(NullPointerException.class, () -> token.setType(null));
    }

    @Test
    @DisplayName("any mutation refreshes lastModifiedAt")
    void mutationsTouchLastModified() {
        long created = token.getCreatedAt();
        token.setName("Hobgoblin");
        // System.currentTimeMillis() has coarse granularity on some platforms, so this can only
        // be asserted as monotonic. A injected Clock would make this exact.
        assertTrue(token.getLastModifiedAt() >= created);
    }

    @Test
    @DisplayName("components are stored and retrieved by their concrete class")
    void componentRoundTrip() {
        HitPoints hp = new HitPoints(12);
        token.setComponent(hp);

        assertTrue(token.hasComponent(HitPoints.class));
        assertSame(hp, token.getComponent(HitPoints.class).orElseThrow());
        assertIterableEquals(List.of(hp), token.getAllComponents());
    }

    @Test
    @DisplayName("setComponent replaces the previous instance of the same class")
    void componentReplacement() {
        token.setComponent(new HitPoints(12));
        HitPoints healed = new HitPoints(20);
        token.setComponent(healed);

        assertEquals(1, token.getAllComponents().size());
        assertSame(healed, token.getComponent(HitPoints.class).orElseThrow());
    }

    @Test
    @DisplayName("KNOWN LIMITATION: components cannot be looked up by interface or supertype")
    void componentLookupIsExactClassOnly() {
        HitPoints hp = new HitPoints(12);
        token.setComponent(hp);

        // setComponent keys the map on component.getClass(), while getComponent does an exact
        // map lookup. So a component stored through its concrete type is invisible under the
        // interface an add-on would naturally query.
        //
        // Note the inconsistency with behaviours: getBehavior(Class) uses isInstance and DOES
        // resolve supertypes (see behaviourLookupResolvesSupertypes below).
        assertTrue(token.getComponent(HealthComponent.class).isEmpty(),
                "if this fails, component lookup was fixed - update this test");
        assertFalse(token.hasComponent(HealthComponent.class));
    }

    @Test
    @DisplayName("removeComponent reports whether anything was removed")
    void removeComponent() {
        token.setComponent(new Armour());
        assertTrue(token.removeComponent(Armour.class));
        assertFalse(token.removeComponent(Armour.class));
        assertFalse(token.removeComponent(HitPoints.class));
    }

    @Test
    @DisplayName("component views are unmodifiable")
    void componentViewsAreUnmodifiable() {
        token.setComponent(new Armour());
        assertThrows(UnsupportedOperationException.class,
                () -> token.getAllComponents().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> token.getComponentTypes().clear());
    }

    @Test
    @DisplayName("tags are normalised to lower case and trimmed")
    void tagNormalisation() {
        token.addTag("  Fire  ");
        assertTrue(token.hasTag("fire"));
        assertTrue(token.hasTag("FIRE"), "lookup is normalised too");
        assertTrue(token.hasTag("  fire "));
        assertIterableEquals(List.of("fire"), token.getTags());
    }

    @Test
    @DisplayName("blank and null tags are silently ignored")
    void blankTagsIgnored() {
        token.addTag(null);
        token.addTag("");
        token.addTag("   ");
        assertTrue(token.getTags().isEmpty());
        assertFalse(token.removeTag(null));
        assertFalse(token.hasTag(null));
    }

    @Test
    @DisplayName("removeTag reports whether the tag was present")
    void removeTag() {
        token.addTag("boss");
        assertTrue(token.removeTag("BOSS"));
        assertFalse(token.removeTag("boss"));
        assertThrows(UnsupportedOperationException.class, () -> token.getTags().clear());
    }

    @Test
    @DisplayName("attaching a behaviour calls onAttach and creates its private context")
    void attachBehaviour() {
        RecordingBehavior behavior = new RecordingBehavior("vtt:aggressive", log);
        token.attachBehavior(behavior);

        assertIterableEquals(List.of("attach:vtt:aggressive"), log);
        assertTrue(token.hasBehavior("vtt:aggressive"));
        assertSame(behavior, token.getBehavior("vtt:aggressive").orElseThrow());
        assertTrue(token.getBehaviorContext("vtt:aggressive").isPresent());
        assertIterableEquals(List.of("vtt:aggressive"), token.getBehaviorIds());
    }

    @Test
    @DisplayName("re-attaching the same id detaches the previous behaviour first")
    void reattachDetachesPrevious() {
        token.attachBehavior(new RecordingBehavior("vtt:aggressive", log));
        token.attachBehavior(new RecordingBehavior("vtt:aggressive", log));

        assertIterableEquals(
                List.of("attach:vtt:aggressive", "detach:vtt:aggressive", "attach:vtt:aggressive"),
                log);
        assertEquals(1, token.getAllBehaviors().size());
    }

    @Test
    @DisplayName("each behaviour gets its own context, and it is dropped on detach")
    void behaviourContextIsPerBehaviour() {
        token.attachBehavior(new RecordingBehavior("a", log));
        token.attachBehavior(new RecordingBehavior("b", log));

        BehaviorContext a = token.getBehaviorContext("a").orElseThrow();
        BehaviorContext b = token.getBehaviorContext("b").orElseThrow();
        assertNotEquals(a, b);

        a.set("charges", 3);
        assertEquals(3, a.getInt("charges", 0));
        assertEquals(0, b.getInt("charges", 0), "contexts must not be shared");

        assertTrue(token.detachBehavior("a"));
        assertTrue(token.getBehaviorContext("a").isEmpty(), "the context dies with the behaviour");
    }

    @Test
    @DisplayName("detaching an unknown behaviour returns false")
    void detachUnknown() {
        assertFalse(token.detachBehavior("nope"));
    }

    @Test
    @DisplayName("behaviour lookup by class resolves supertypes (unlike components)")
    void behaviourLookupResolvesSupertypes() {
        RecordingBehavior behavior = new RecordingBehavior("vtt:aggressive", log);
        token.attachBehavior(behavior);

        assertTrue(token.hasBehavior(Behavior.class));
        assertSame(behavior, token.getBehavior(Behavior.class).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> token.getAllBehaviors().clear());
    }

    @Test
    @DisplayName("position and world are optional and settable back to empty")
    void positionAndWorld() {
        token.setPosition(new TokenPosition(1, 2, 3));
        assertEquals(new TokenPosition(1, 2, 3), token.getPosition().orElseThrow());
        token.setPosition(null);
        assertTrue(token.getPosition().isEmpty());
    }

    @Test
    @DisplayName("equality is by id only")
    void equalityById() {
        SimpleToken sameName = new SimpleToken("Goblin", CoreTokenType.MONSTER);
        assertNotEquals(token, sameName);
        assertEquals(token, token);
        assertEquals(token.getId().hashCode(), token.hashCode());
        assertTrue(token.toString().contains("Goblin"));
    }
}
