package org.vttale.vttale.module.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.token.CoreTokenType;
import org.vttale.vttale.api.token.Token;
import org.vttale.vttale.api.token.TokenComponent;
import org.vttale.vttale.api.token.TokenPosition;
import org.vttale.vttale.api.token.TokenType;
import org.vttale.vttale.api.token.behavior.Behavior;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleTokenQueryTest {

    private record CustomType(String id, String displayName, String shortName) implements TokenType {
        @Override public String getId() { return id; }
        @Override public String getDisplayName() { return displayName; }
        @Override public String getShortName() { return shortName; }
    }

    private static final class Armour implements TokenComponent {
        @Override public String getComponentId() { return "vtt:armour"; }
    }

    private static final class Spellbook implements TokenComponent {
        @Override public String getComponentId() { return "mymod:spellbook"; }
    }

    private static final class Named implements Behavior {
        private final String id;
        Named(String id) { this.id = id; }
        @Override public String getId() { return id; }
    }

    private final UUID world = UUID.randomUUID();
    private final UUID otherWorld = UUID.randomUUID();
    private final UUID gm = UUID.randomUUID();

    private SimpleToken alice;   // PC, owned by gm, in world, at (0,0,0), tagged party
    private SimpleToken goblin;  // monster, unowned, in world, at (10,0,0), tagged hostile+boss
    private SimpleToken ghost;   // custom type, no position, no world
    private List<Token> all;

    /** Busy-waits until the wall clock ticks, so creation timestamps are guaranteed distinct. */
    private static void waitForClockTick() {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() == start) {
            Thread.onSpinWait();
        }
    }

    @BeforeEach
    void setUp() {
        alice = new SimpleToken("Alice", CoreTokenType.PLAYER_CHARACTER, gm);
        alice.setWorldId(world);
        alice.setPosition(new TokenPosition(0, 0, 0));
        alice.addTag("party");
        alice.setComponent(new Armour());
        alice.attachBehavior(new Named("vtt:concentration"));

        waitForClockTick();
        goblin = new SimpleToken("Goblin Boss", CoreTokenType.MONSTER);
        goblin.setWorldId(world);
        goblin.setPosition(new TokenPosition(10, 0, 0));
        goblin.addTag("hostile");
        goblin.addTag("boss");
        goblin.setComponent(new Armour());
        goblin.setComponent(new Spellbook());

        waitForClockTick();
        ghost = new SimpleToken("Casper", new CustomType("mymod:ghost", "Ghost", "GHO"));
        ghost.setBoundEntityId(UUID.randomUUID());

        all = List.of(alice, goblin, ghost);
    }

    private SimpleTokenQuery query() {
        return new SimpleTokenQuery(all);
    }

    private static List<Token> list(Iterable<Token> tokens) {
        List<Token> out = new ArrayList<>();
        tokens.forEach(out::add);
        return out;
    }

    @Test
    @DisplayName("an empty query returns everything")
    void emptyQuery() {
        assertEquals(all, list(query().execute()));
        assertEquals(3, query().count());
        assertTrue(query().exists());
    }

    @Test
    @DisplayName("type filters accept enums, ids and namespaces")
    void typeFilters() {
        assertEquals(List.of(alice), list(query().withType(CoreTokenType.PLAYER_CHARACTER).execute()));
        assertEquals(List.of(goblin), list(query().withTypeId("core:monster").execute()));
        assertEquals(List.of(alice, goblin), list(query().withTypeNamespace("core").execute()));
        assertEquals(List.of(ghost), list(query().withTypeNamespace("mymod").execute()));
    }

    @Test
    @DisplayName("filters compose as AND")
    void filtersCompose() {
        assertEquals(List.of(goblin),
                list(query().inWorld(world).withTag("boss").execute()));
        assertEquals(0, query().inWorld(world).withTag("nope").count());
    }

    @Test
    @DisplayName("tag filters cover all, any and single")
    void tagFilters() {
        assertEquals(List.of(goblin), list(query().withAllTags("hostile", "boss").execute()));
        assertEquals(List.of(alice, goblin), list(query().withAnyTag("party", "boss").execute()));
        assertEquals(0, query().withAllTags("party", "boss").count());
    }

    @Test
    @DisplayName("ownership filters split owned from unowned")
    void ownershipFilters() {
        assertEquals(List.of(alice), list(query().ownedBy(gm).execute()));
        assertEquals(List.of(goblin, ghost), list(query().unowned().execute()));
    }

    @Test
    @DisplayName("name filters are case insensitive")
    void nameFilters() {
        assertEquals(List.of(alice), list(query().withName("alice").execute()));
        assertEquals(List.of(goblin), list(query().nameContains("BOSS").execute()));
        assertEquals(0, query().withName("Ali").count(), "withName is exact, not a prefix");
    }

    @Test
    @DisplayName("location filters ignore tokens that have no position")
    void locationFilters() {
        assertEquals(List.of(alice, goblin), list(query().inWorld(world).execute()));
        assertEquals(0, query().inWorld(otherWorld).count());
        assertEquals(List.of(alice, goblin), list(query().withPosition().execute()));
        assertEquals(List.of(ghost), list(query().withoutPosition().execute()));

        TokenPosition origin = TokenPosition.origin();
        assertEquals(List.of(alice), list(query().withinRadius(origin, 5).execute()));
        assertEquals(List.of(alice, goblin), list(query().withinRadius(origin, 10).execute()),
                "the radius is inclusive");
        assertFalse(list(query().withinRadius(origin, 1000).execute()).contains(ghost),
                "a token without a position is never within any radius");
    }

    @Test
    @DisplayName("component filters work by class and by namespace")
    void componentFilters() {
        assertEquals(List.of(alice, goblin), list(query().withComponent(Armour.class).execute()));
        assertEquals(List.of(goblin), list(query().withComponentNamespace("mymod").execute()));
        assertEquals(List.of(goblin),
                list(query().withAllComponents(Armour.class, Spellbook.class).execute()));
        assertEquals(List.of(alice, goblin),
                list(query().withAnyComponent(Armour.class, Spellbook.class).execute()));
    }

    @Test
    @DisplayName("behaviour filters work by id and by class")
    void behaviourFilters() {
        assertEquals(List.of(alice), list(query().withBehavior("vtt:concentration").execute()));
        assertEquals(List.of(alice), list(query().withBehavior(Behavior.class).execute()));
        assertEquals(List.of(alice), list(query().withAnyBehavior("vtt:concentration", "nope").execute()));
        assertEquals(0, query().withAllBehaviors("vtt:concentration", "nope").count());
    }

    @Test
    @DisplayName("entity-binding filters split bound from unbound")
    void bindingFilters() {
        assertEquals(List.of(ghost), list(query().boundToEntity().execute()));
        assertEquals(List.of(alice, goblin), list(query().notBoundToEntity().execute()));
    }

    @Test
    @DisplayName("where() accepts an arbitrary predicate")
    void customPredicate() {
        assertEquals(List.of(ghost),
                list(query().where(t -> t.getName().startsWith("Cas")).execute()));
    }

    @Test
    @DisplayName("empty varargs are a no-op, not an empty result")
    void emptyVarargsDoNotFilter() {
        // Easy to trip over: query().withType() returns everything rather than nothing.
        assertEquals(3, query().withType().count());
        assertEquals(3, query().withTypeId().count());
        assertEquals(3, query().withAnyTag().count());
        assertEquals(3, query().withAllTags().count());
        assertEquals(3, query().withAnyBehavior().count());
    }

    @Test
    @DisplayName("sortByName is case insensitive, sortByCreatedAt is oldest first")
    void ascendingSorts() {
        assertEquals(List.of(alice, ghost, goblin), list(query().sortByName().execute()));
        assertEquals(List.of(alice, goblin, ghost), list(query().sortByCreatedAt().execute()));
    }

    @Test
    @DisplayName("sortByLastModified is NEWEST first, unlike every other sort")
    void lastModifiedSortIsDescending() {
        // Deliberately inconsistent with sortByCreatedAt; pinned so the surprise is documented.
        assertEquals(List.of(ghost, goblin, alice), list(query().sortByLastModified().execute()));
    }

    @Test
    @DisplayName("sortByDistanceFrom puts positionless tokens last")
    void distanceSort() {
        assertEquals(List.of(alice, goblin, ghost),
                list(query().sortByDistanceFrom(TokenPosition.origin()).execute()));
    }

    @Test
    @DisplayName("skip and limit page through the sorted result")
    void skipAndLimit() {
        assertEquals(List.of(alice, ghost), list(query().sortByName().limit(2).execute()));
        assertEquals(List.of(ghost, goblin), list(query().sortByName().skip(1).execute()));
        assertEquals(List.of(ghost), list(query().sortByName().skip(1).limit(1).execute()));
    }

    @Test
    @DisplayName("KNOWN LIMITATION: count() and exists() ignore skip and limit")
    void countIgnoresPaging() {
        // count() runs the filter chain only, so it reports the total match count, not the size
        // of what execute() would return. Callers doing "limit(10).count()" get 3, not 2.
        assertEquals(3, query().limit(2).count());
        assertEquals(3, query().skip(2).count());
        assertTrue(query().skip(99).exists(), "exists() also ignores paging");
        assertEquals(2, list(query().limit(2).execute()).size());
    }

    @Test
    @DisplayName("KNOWN LIMITATION: limit(0) means no limit")
    void limitZeroMeansUnlimited() {
        // The guard is `if (limitValue > 0)`, so 0 is indistinguishable from "unset".
        assertEquals(3, list(query().limit(0).execute()).size());
    }

    @Test
    @DisplayName("a query is repeatable: execute() twice gives the same answer")
    void queryIsRepeatable() {
        SimpleTokenQuery q = query();
        q.withTypeNamespace("core").sortByName();

        assertEquals(list(q.execute()), list(q.execute()));
        assertEquals(2, q.count());
    }
}
