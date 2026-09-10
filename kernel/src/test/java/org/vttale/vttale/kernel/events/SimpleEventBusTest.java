package org.vttale.vttale.kernel.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.events.Event;
import org.vttale.vttale.api.events.EventContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SimpleEventBusTest {

    private static class ParentEvent implements Event { }

    private static final class ChildEvent extends ParentEvent { }

    private static final class OtherEvent implements Event { }

    private static final EventContext CTX = new EventContext("player-1");

    private SimpleEventBus bus;
    private List<String> calls;

    @BeforeEach
    void setUp() {
        bus = new SimpleEventBus();
        calls = new ArrayList<>();
    }

    @Test
    @DisplayName("a subscriber receives its event with the publishing context")
    void deliversEventAndContext() {
        List<ParentEvent> received = new ArrayList<>();
        List<EventContext> contexts = new ArrayList<>();
        bus.subscribe(ParentEvent.class, (e, ctx) -> {
            received.add(e);
            contexts.add(ctx);
        });

        ParentEvent event = new ParentEvent();
        bus.publish(event, CTX);

        assertEquals(1, received.size());
        assertSame(event, received.getFirst());
        assertSame(CTX, contexts.getFirst(), "the context must be forwarded untouched");
    }

    @Test
    @DisplayName("publishing with no subscriber is a no-op")
    void publishWithoutSubscribers() {
        assertDoesNotThrow(() -> bus.publish(new ParentEvent(), CTX));
    }

    @Test
    @DisplayName("subscribers of other event types are not called")
    void doesNotLeakAcrossTypes() {
        bus.subscribe(OtherEvent.class, (e, ctx) -> calls.add("other"));
        bus.publish(new ParentEvent(), CTX);
        assertEquals(0, calls.size());
    }

    @Test
    @DisplayName("handlers run in ascending priority order, insertion order breaking ties")
    void priorityOrdering() {
        bus.subscribe(ParentEvent.class, 10, (e, ctx) -> calls.add("late"));
        bus.subscribe(ParentEvent.class, -10, (e, ctx) -> calls.add("early"));
        bus.subscribe(ParentEvent.class, 0, (e, ctx) -> calls.add("default-a"));
        bus.subscribe(ParentEvent.class, 0, (e, ctx) -> calls.add("default-b"));

        bus.publish(new ParentEvent(), CTX);

        // Lower number == runs first. This is the opposite of the Bukkit/Sponge convention,
        // so it is pinned here on purpose.
        assertIterableEquals(List.of("early", "default-a", "default-b", "late"), calls);
    }

    @Test
    @DisplayName("a throwing handler is isolated: the others still run and publish() does not throw")
    void handlerFailureIsContained() {
        bus.subscribe(ParentEvent.class, 0, (e, ctx) -> {
            calls.add("first");
            throw new IllegalStateException("boom");
        });
        bus.subscribe(ParentEvent.class, 1, (e, ctx) -> calls.add("second"));

        assertDoesNotThrow(() -> bus.publish(new ParentEvent(), CTX));
        assertIterableEquals(List.of("first", "second"), calls);
    }

    @Test
    @DisplayName("an Error thrown by a handler is also contained")
    void handlerErrorIsContained() {
        bus.subscribe(ParentEvent.class, 0, (e, ctx) -> {
            throw new StackOverflowError("boom");
        });
        bus.subscribe(ParentEvent.class, 1, (e, ctx) -> calls.add("second"));

        assertDoesNotThrow(() -> bus.publish(new ParentEvent(), CTX));
        assertIterableEquals(List.of("second"), calls);
    }

    @Test
    @DisplayName("KNOWN LIMITATION: dispatch is by exact class, supertypes never fire")
    void dispatchIsExactClassOnly() {
        bus.subscribe(ParentEvent.class, (e, ctx) -> calls.add("parent"));
        bus.subscribe(Event.class, (e, ctx) -> calls.add("any"));

        bus.publish(new ChildEvent(), CTX);

        // SimpleEventBus looks up subscribers with subscribers.get(event.getClass()), so neither
        // the superclass nor the Event interface is consulted.
        //
        // THIS TEST DOCUMENTS A BUG, NOT A DESIRED BEHAVIOUR. When hierarchy-aware dispatch is
        // implemented, this test is expected to fail: replace it with the assertion that both
        // "parent" and "any" were called.
        assertEquals(0, calls.size(),
                "if this fails, hierarchy dispatch was implemented - update this test");
    }

    @Test
    @DisplayName("the same listener subscribed twice is called twice")
    void duplicateSubscriptionsAreNotDeduplicated() {
        java.util.function.BiConsumer<ParentEvent, EventContext> listener =
                (e, ctx) -> calls.add("hit");
        bus.subscribe(ParentEvent.class, listener);
        bus.subscribe(ParentEvent.class, listener);

        bus.publish(new ParentEvent(), CTX);

        // There is no unsubscribe(), so a module that re-registers on reload permanently
        // duplicates its handlers.
        assertEquals(2, calls.size());
    }
}
