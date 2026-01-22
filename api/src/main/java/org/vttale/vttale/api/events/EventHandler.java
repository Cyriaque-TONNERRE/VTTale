package org.vttale.vttale.api.events;

/**
 * Functional interface for handling events without context.
 *
 * @param <T> the type of event to handle
 * @deprecated Use {@link java.util.function.BiConsumer} with
 *             {@link EventBus#subscribe} instead.
 */
@FunctionalInterface
public interface EventHandler<T extends Event> {

    /**
     * Handles the given event.
     *
     * @param event the event to handle
     */
    void handle(T event);
}