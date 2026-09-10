package org.vttale.vttale.module.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.events.Event;
import org.vttale.vttale.api.token.CoreTokenType;
import org.vttale.vttale.api.token.Token;
import org.vttale.vttale.api.token.TokenComponent;
import org.vttale.vttale.api.token.TokenPosition;
import org.vttale.vttale.api.token.events.TokenBoundEvent;
import org.vttale.vttale.api.token.events.TokenCreatedEvent;
import org.vttale.vttale.api.token.events.TokenRemovedEvent;
import org.vttale.vttale.api.token.events.TokenUpdatedEvent;
import org.vttale.vttale.kernel.VTTaleKernel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleTokenRegistryTest {

    private static final class Armour implements TokenComponent {
        @Override public String getComponentId() { return "vtt:armour"; }
    }

    private VTTaleKernel kernel;
    private SimpleTokenRegistry registry;
    private List<Event> events;

    @BeforeEach
    void setUp() {
        kernel = new VTTaleKernel();
        registry = new SimpleTokenRegistry(kernel);
        events = new ArrayList<>();
        kernel.getEventBus().subscribe(TokenCreatedEvent.class, (e, ctx) -> events.add(e));
        kernel.getEventBus().subscribe(TokenRemovedEvent.class, (e, ctx) -> events.add(e));
        kernel.getEventBus().subscribe(TokenBoundEvent.class, (e, ctx) -> events.add(e));
        kernel.getEventBus().subscribe(TokenUpdatedEvent.class, (e, ctx) -> events.add(e));
    }

    private <T> T lastEvent(Class<T> type) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (type.isInstance(events.get(i))) {
                return type.cast(events.get(i));
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " was published");
    }

    @Test
    @DisplayName("create registers the token and announces it")
    void create() {
        Token token = registry.create("Goblin", CoreTokenType.MONSTER);

        assertEquals(1, registry.count());
        assertTrue(registry.exists(token.getId()));
        assertSame(token, registry.get(token.getId()).orElseThrow());
        assertSame(token, lastEvent(TokenCreatedEvent.class).getToken());
    }

    @Test
    @DisplayName("a token created by the registry emits update events when mutated")
    void createdTokensAreWiredToTheBus() {
        Token token = registry.create("Goblin", CoreTokenType.MONSTER);
        events.clear();

        token.setPosition(new TokenPosition(1, 2, 3));

        TokenUpdatedEvent update = lastEvent(TokenUpdatedEvent.class);
        assertEquals(TokenUpdatedEvent.UpdateType.POSITION_CHANGED, update.getUpdateType());
        assertSame(token, update.getToken());
    }

    @Test
    @DisplayName("remove is idempotent and announces the token before dropping it")
    void remove() {
        Token token = registry.create("Goblin", CoreTokenType.MONSTER);

        assertTrue(registry.remove(token.getId()));
        assertFalse(registry.remove(token.getId()));
        assertEquals(0, registry.count());
        assertEquals(token.getId(), lastEvent(TokenRemovedEvent.class).getTokenId());
    }

    @Test
    @DisplayName("removing a bound token releases the entity binding")
    void removeReleasesBinding() {
        Token token = registry.create("Goblin", CoreTokenType.MONSTER);
        UUID entity = UUID.randomUUID();
        registry.bindToEntity(token.getId(), entity);

        registry.remove(token.getId());

        assertTrue(registry.getByEntityId(entity).isEmpty());
        assertTrue(lastEvent(TokenRemovedEvent.class).wasBoundToEntity());
    }

    @Test
    @DisplayName("binding is bidirectional and reports a first binding")
    void bindToEntity() {
        Token token = registry.create("Goblin", CoreTokenType.MONSTER);
        UUID entity = UUID.randomUUID();

        assertTrue(registry.bindToEntity(token.getId(), entity));
        assertEquals(entity, token.getBoundEntityId().orElseThrow());
        assertSame(token, registry.getByEntityId(entity).orElseThrow());

        TokenBoundEvent event = lastEvent(TokenBoundEvent.class);
        assertTrue(event.isBound());
        assertTrue(event.isFirstBinding());
        assertFalse(event.isRebound());
    }

    @Test
    @DisplayName("binding an unknown token fails without side effects")
    void bindUnknownToken() {
        UUID entity = UUID.randomUUID();
        assertFalse(registry.bindToEntity(UUID.randomUUID(), entity));
        assertTrue(registry.getByEntityId(entity).isEmpty());
    }

    @Test
    @DisplayName("rebinding a token releases its previous entity")
    void rebind() {
        Token token = registry.create("Goblin", CoreTokenType.MONSTER);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        registry.bindToEntity(token.getId(), first);

        registry.bindToEntity(token.getId(), second);

        assertTrue(registry.getByEntityId(first).isEmpty());
        assertSame(token, registry.getByEntityId(second).orElseThrow());
        assertTrue(lastEvent(TokenBoundEvent.class).isRebound());
    }

    @Test
    @DisplayName("an entity can only carry one token: the previous holder is unbound")
    void entityIsStolenFromPreviousToken() {
        Token first = registry.create("Goblin A", CoreTokenType.MONSTER);
        Token second = registry.create("Goblin B", CoreTokenType.MONSTER);
        UUID entity = UUID.randomUUID();
        registry.bindToEntity(first.getId(), entity);

        registry.bindToEntity(second.getId(), entity);

        assertTrue(first.getBoundEntityId().isEmpty());
        assertSame(second, registry.getByEntityId(entity).orElseThrow());
    }

    @Test
    @DisplayName("unbinding is reported honestly")
    void unbind() {
        Token token = registry.create("Goblin", CoreTokenType.MONSTER);
        assertFalse(registry.unbindFromEntity(token.getId()), "already unbound");
        assertFalse(registry.unbindFromEntity(UUID.randomUUID()), "unknown token");

        registry.bindToEntity(token.getId(), UUID.randomUUID());
        assertTrue(registry.unbindFromEntity(token.getId()));
        assertTrue(token.getBoundEntityId().isEmpty());
    }

    @Test
    @DisplayName("bound and unbound tokens can be listed separately")
    void boundAndUnboundViews() {
        Token bound = registry.create("Bound", CoreTokenType.NPC);
        Token free = registry.create("Free", CoreTokenType.NPC);
        registry.bindToEntity(bound.getId(), UUID.randomUUID());

        assertEquals(List.of(bound), new ArrayList<>(registry.getBoundTokens()));
        assertEquals(List.of(free), new ArrayList<>(registry.getUnboundTokens()));
    }

    @Test
    @DisplayName("the indexed lookups all agree with the underlying data")
    void lookups() {
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        Token npc = registry.create("Innkeeper", CoreTokenType.NPC, owner);
        npc.setWorldId(world);
        npc.addTag("friendly");
        npc.setComponent(new Armour());
        registry.create("Goblin", CoreTokenType.MONSTER);

        assertEquals(List.of(npc), new ArrayList<>(registry.getByType(CoreTokenType.NPC)));
        assertEquals(List.of(npc), new ArrayList<>(registry.getByTypeId("core:npc")));
        assertEquals(List.of(npc), new ArrayList<>(registry.getByWorld(world)));
        assertEquals(List.of(npc), new ArrayList<>(registry.getByOwner(owner)));
        assertEquals(List.of(npc), new ArrayList<>(registry.getByTag("friendly")));
        assertEquals(List.of(npc), new ArrayList<>(registry.getByComponent(Armour.class)));
        assertEquals(2, registry.getAll().size());
        assertEquals(1, registry.filter(t -> t.getName().startsWith("Gob")).size());
    }

    @Test
    @DisplayName("getAll returns an unmodifiable snapshot")
    void getAllIsAnUnmodifiableSnapshot() {
        registry.create("Goblin", CoreTokenType.MONSTER);
        var snapshot = registry.getAll();

        assertThrows(UnsupportedOperationException.class, snapshot::clear);

        registry.create("Orc", CoreTokenType.MONSTER);
        assertEquals(1, snapshot.size(), "the snapshot must not see later writes");
        assertEquals(2, registry.count());
    }

    @Test
    @DisplayName("removeIf and removeByWorld report how many tokens went away")
    void bulkRemoval() {
        UUID world = UUID.randomUUID();
        Token a = registry.create("A", CoreTokenType.MONSTER);
        Token b = registry.create("B", CoreTokenType.MONSTER);
        registry.create("C", CoreTokenType.NPC);
        a.setWorldId(world);
        b.setWorldId(world);

        assertEquals(2, registry.removeByWorld(world));
        assertEquals(1, registry.count());
        assertEquals(1, registry.removeIf(t -> true));
        assertEquals(0, registry.count());
    }

    @Test
    @DisplayName("clear empties the registry, the bindings and announces every removal")
    void clear() {
        Token a = registry.create("A", CoreTokenType.MONSTER);
        UUID entity = UUID.randomUUID();
        registry.bindToEntity(a.getId(), entity);
        registry.create("B", CoreTokenType.MONSTER);
        events.clear();

        registry.clear();

        assertEquals(0, registry.count());
        assertTrue(registry.getByEntityId(entity).isEmpty());
        assertEquals(2, events.size(), "one TokenRemovedEvent per token");
    }

    @Test
    @DisplayName("the registry refuses to be built without a kernel")
    void kernelIsRequired() {
        assertThrows(NullPointerException.class, () -> new SimpleTokenRegistry(null));
    }
}
