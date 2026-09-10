package org.vttale.vttale.module.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.events.EventContext;
import org.vttale.vttale.kernel.VTTaleKernel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChatModuleTest {

    private VTTaleKernel kernel;
    private List<PlatformBroadcastEvent> broadcasts;
    private List<EventContext> contexts;

    @BeforeEach
    void setUp() {
        kernel = new VTTaleKernel();
        broadcasts = new ArrayList<>();
        contexts = new ArrayList<>();
        kernel.getEventBus().subscribe(PlatformBroadcastEvent.class, (e, ctx) -> {
            broadcasts.add(e);
            contexts.add(ctx);
        });
        kernel.getModuleRegistry().registerModule(new ChatModule());
    }

    @Test
    @DisplayName("a SendMessageEvent becomes a prefixed PlatformBroadcastEvent")
    void formatsAndForwards() {
        EventContext ctx = new EventContext("player-1");
        kernel.getEventBus().publish(new SendMessageEvent("2d6 -> [4, 5] = 9", "player-1"), ctx);

        assertEquals(1, broadcasts.size());
        assertEquals("[VTT] 2d6 -> [4, 5] = 9", broadcasts.getFirst().getFormattedMessage());
        assertEquals("player-1", broadcasts.getFirst().getTargetId(),
                "the recipient must survive the formatting step");
        assertSame(ctx, contexts.getFirst(), "the originating context must be forwarded untouched");
    }

    @Test
    @DisplayName("the CONSOLE target is passed through like any other")
    void consoleTargetIsPreserved() {
        kernel.getEventBus().publish(
                new SendMessageEvent("hello", "CONSOLE"), new EventContext("CONSOLE"));

        assertEquals("CONSOLE", broadcasts.getFirst().getTargetId());
    }
}
