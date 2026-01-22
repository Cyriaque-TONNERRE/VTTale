package org.vttale.vttale.kernel.events;

import org.vttale.vttale.api.events.Event;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.events.EventContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public class SimpleEventBus implements EventBus {

    private final Map<Class<?>, List<BiConsumer<?, EventContext>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public <T extends Event> void publish(T event, EventContext context) {
        Class<?> eventType = event.getClass();

        if (subscribers.containsKey(eventType)) {
            for (var listener : subscribers.get(eventType)) {
                @SuppressWarnings("unchecked")
                BiConsumer<T, EventContext> typedListener = (BiConsumer<T, EventContext>) listener;

                try {
                    typedListener.accept(event, context);
                } catch (Exception e) {
                    System.err.println("Errore nel listener per " + eventType.getName());
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public <T extends Event> void subscribe(Class<T> eventType, BiConsumer<T, EventContext> listener) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

}
