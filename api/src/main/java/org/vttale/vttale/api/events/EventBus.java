package org.vttale.vttale.api.events;

import java.util.function.BiConsumer;

/**
 * Central hub for publishing and subscribing to events.
 * <p>
 * Publishing is synchronous: {@code publish} returns only once every handler
 * has returned, so a table action (roll -> apply -> notify) always completes
 * before the caller moves on.
 */
public interface EventBus {

    /**
     * Publishes an event to all registered listeners, ordered by priority.
     *
     * @param event   the event to publish
     * @param context the context containing metadata about the event source
     * @param <T>     the event type
     */
    <T extends Event> void publish(T event, EventContext context);

    /**
     * Subscribes a listener with default priority (0).
     *
     * @param eventType the class of the event to listen for
     * @param listener  the callback to invoke when the event is published
     * @param <T>       the event type
     */
    default <T extends Event> void subscribe(Class<T> eventType, BiConsumer<T, EventContext> listener) {
        subscribe(eventType, 0, listener);
    }

    /**
     * Subscribes a listener with an explicit priority. Lower values run
     * first; ties keep subscription order.
     *
     * @param eventType the class of the event to listen for
     * @param priority  the priority (lower runs first)
     * @param listener  the callback to invoke when the event is published
     * @param <T>       the event type
     */
    <T extends Event> void subscribe(Class<T> eventType, int priority, BiConsumer<T, EventContext> listener);
}
