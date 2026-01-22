package org.vttale.vttale.module.chat;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.events.EventBus;
import org.vttale.vttale.api.events.EventContext;
import org.vttale.vttale.api.module.Module;

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
