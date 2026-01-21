package dev.giopalma.vttale.api.events;

/**
 * Carries metadata about the source and context of an event.
 * <p>
 * Every published event is accompanied by an EventContext that allows
 * handlers to identify who triggered the event and route responses accordingly.
 * </p>
 */
public class EventContext {
    private final String senderId;

    /**
     * Creates a new event context.
     *
     * @param senderId the unique identifier of the event sender (player UUID,
     *                 "CONSOLE", or "KERNEL")
     */
    public EventContext(String senderId) {
        this.senderId = senderId;
    }

    /**
     * Returns the unique identifier of the event sender.
     *
     * @return the sender ID
     */
    public String getSenderId() {
        return senderId;
    }
}
