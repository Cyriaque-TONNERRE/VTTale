package dev.giopalma.vttale.module.chat;

import dev.giopalma.vttale.api.Kernel;
import dev.giopalma.vttale.api.events.EventBus;
import dev.giopalma.vttale.api.events.EventContext;
import dev.giopalma.vttale.api.module.Module;

public class ChatModule implements Module {
    private EventBus eventBus;

    @Override
    public void onEnable(Kernel kernel) {
        this.eventBus = kernel.getEventBus();
        eventBus.subscribe(SendMessageEvent.class, this::onSendMessage);
    }

    private void onSendMessage(SendMessageEvent event, EventContext context) {
        String formatted = String.format("[VTT] %s", event.getMessage());
        eventBus.publish(new PlatformBroadcastEvent(formatted, event.getTargetId()), context);
    }
}
