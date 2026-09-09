package org.vttale.vttale.kernel.events;

import org.vttale.vttale.api.events.Event;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.events.EventContext;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public class SimpleEventBus implements EventBus {

    private static final System.Logger LOGGER = System.getLogger(SimpleEventBus.class.getName());

    private record Handler(int priority, BiConsumer<?, EventContext> action) {}

    private final Map<Class<?>, List<Handler>> subscribers = new ConcurrentHashMap<>();

    @Override
    public <T extends Event> void subscribe(Class<T> eventType, int priority, BiConsumer<T, EventContext> listener) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new Handler(priority, listener));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void publish(T event, EventContext context) {
        List<Handler> list = subscribers.get(event.getClass());
        if (list == null) {
            return;
        }
        // ponytail: sort per publish; pre-sort on subscribe if profiling ever says so
        List<Handler> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt(Handler::priority));
        for (Handler handler : sorted) {
            try {
                ((BiConsumer<T, EventContext>) handler.action()).accept(event, context);
            } catch (Exception e) {
                // One broken handler must not break the others or the caller.
                LOGGER.log(Level.ERROR, "Event handler failed for " + event.getClass().getSimpleName(), e);
            }
        }
    }
}
