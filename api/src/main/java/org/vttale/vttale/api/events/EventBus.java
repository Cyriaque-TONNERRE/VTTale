package org.vttale.vttale.api.events;

import java.util.function.BiConsumer;

/**
 * Central hub for publishing and subscribing to events.
 * <p>
 * The EventBus enables decoupled communication between modules, adapters,
 * and the kernel using a publish-subscribe pattern.
 * </p>
 */
public interface EventBus {

    /**
     * Publishes an event to all registered listeners.
     *
     * @param event   the event to publish
     * @param context the context containing metadata about the event source
     * @param <T>     the event type
     */
    <T extends Event> void publish(T event, EventContext context);

    /**
     * Subscribes a listener to a specific event type.
     *
     * @param eventType the class of the event to listen for
     * @param listener  the callback to invoke when the event is published
     * @param <T>       the event type
     */
    <T extends Event> void subscribe(Class<T> eventType, BiConsumer<T, EventContext> listener);
}