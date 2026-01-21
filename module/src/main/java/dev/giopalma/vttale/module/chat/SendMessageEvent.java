package dev.giopalma.vttale.module.chat;

import dev.giopalma.vttale.api.events.Event;

/**
 * Event published when a module wants to send a message to the chat system.
 * <p>
 * This is processed by the ChatModule, which may format it before sending to
 * the platform.
 * </p>
 */
public class SendMessageEvent implements Event {
    private final String message;
    private final String targetId;

    /**
     * Creates a new send message event.
     *
     * @param message  the message to send
     * @param targetId the target recipient ID (player UUID, "CONSOLE", or "GLOBAL")
     */
    public SendMessageEvent(String message, String targetId) {
        this.message = message;
        this.targetId = targetId;
    }

    /**
     * Returns the message content.
     *
     * @return the message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the target recipient ID.
     *
     * @return the target ID
     */
    public String getTargetId() {
        return targetId;
    }
}
