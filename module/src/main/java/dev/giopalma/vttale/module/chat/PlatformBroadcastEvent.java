package dev.giopalma.vttale.module.chat;

import dev.giopalma.vttale.api.events.Event;

/**
 * Event published by the ChatModule when a message is ready to be displayed by
 * the platform.
 * <p>
 * Platform adapters should subscribe to this event to deliver messages to
 * players.
 * </p>
 */
public class PlatformBroadcastEvent implements Event {
    private final String formattedMessage;
    private final String targetId;

    /**
     * Creates a new platform broadcast event.
     *
     * @param formattedMessage the formatted message ready for display
     * @param targetId         the target recipient ID
     */
    public PlatformBroadcastEvent(String formattedMessage, String targetId) {
        this.formattedMessage = formattedMessage;
        this.targetId = targetId;
    }

    /**
     * Returns the formatted message.
     *
     * @return the formatted message
     */
    public String getFormattedMessage() {
        return formattedMessage;
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
