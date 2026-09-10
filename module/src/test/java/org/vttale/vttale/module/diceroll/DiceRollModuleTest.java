package org.vttale.vttale.module.diceroll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.events.CommandExecutedEvent;
import org.vttale.vttale.api.events.EventContext;
import org.vttale.vttale.kernel.VTTaleKernel;
import org.vttale.vttale.module.chat.ChatModule;
import org.vttale.vttale.module.chat.PlatformBroadcastEvent;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the real chain a player triggers:
 * CommandExecutedEvent -> DiceRollModule -> SendMessageEvent -> ChatModule -> PlatformBroadcastEvent.
 * <p>
 * Everything except the Hytale adapter is exercised, on a real kernel.
 */
class DiceRollModuleTest {

    private static final String PLAYER = "3f7c1f5e-0000-0000-0000-000000000001";

    private VTTaleKernel kernel;
    private List<PlatformBroadcastEvent> out;

    @BeforeEach
    void setUp() {
        kernel = new VTTaleKernel();
        out = new ArrayList<>();
        kernel.getEventBus().subscribe(PlatformBroadcastEvent.class, (e, ctx) -> out.add(e));
        kernel.getModuleRegistry().registerModule(new ChatModule());
        kernel.getModuleRegistry().registerModule(new DiceRollModule());
    }

    private void run(String... args) {
        kernel.getEventBus().publish(
                new CommandExecutedEvent("roll", args), new EventContext(PLAYER));
    }

    private String lastMessage() {
        assertEquals(1, out.size(), "exactly one reply expected");
        return out.getFirst().getFormattedMessage();
    }

    @Test
    @DisplayName("the module declares /roll and publishes the DiceService")
    void wiring() {
        assertTrue(kernel.getCommandRegistry().getRegisteredCommands().containsKey("roll"));
        assertNotNull(kernel.getService(DiceService.class),
                "other modules must be able to roll dice without going through chat");
    }

    @Test
    @DisplayName("a valid roll is answered to the player who typed it")
    void validRoll() {
        run("2d6");

        String message = lastMessage();
        assertTrue(message.startsWith("[VTT] 2d6 -> ["), "unexpected reply: " + message);
        assertTrue(message.contains("] = "), "unexpected reply: " + message);
        assertEquals(PLAYER, out.getFirst().getTargetId(),
                "the reply must go back to the sender, not broadcast");
    }

    @Test
    @DisplayName("arguments split by the platform are re-joined, so \"1d10 + 10\" works")
    void argumentsAreRejoined() {
        run("1d10", "+", "10");
        assertTrue(lastMessage().startsWith("[VTT] 1d10+10 -> ["), lastMessage());
    }

    @Test
    @DisplayName("an omitted count defaults to one die")
    void impliedSingleDie() {
        run("d20");
        assertTrue(lastMessage().startsWith("[VTT] 1d20 -> ["), lastMessage());
    }

    @Test
    @DisplayName("no argument prints usage instead of failing")
    void usage() {
        run();
        assertEquals("[VTT] Usage: /roll <dice_notation>. Example: /roll 2d6+3", lastMessage());
    }

    @Test
    @DisplayName("the dice caps are enforced before rolling")
    void capsAreEnforced() {
        run("1000d6");
        assertEquals("[VTT] Dice count or sides too high!", lastMessage());

        out.clear();
        run("1d5000");
        assertEquals("[VTT] Dice count or sides too high!", lastMessage());
    }

    @Test
    @DisplayName("garbage notation is reported, never silently mis-rolled")
    void invalidNotation() {
        run("abc");
        assertEquals("[VTT] Invalid dice notation: abc", lastMessage());
    }

    @Test
    @DisplayName("zero dice is rejected by the service and surfaces as invalid notation")
    void zeroDice() {
        run("0d6");
        assertEquals("[VTT] Invalid dice notation: 0d6", lastMessage());
    }

    @Test
    @DisplayName("KNOWN LIMITATION: only one modifier is supported, \"2d6+3-1\" is refused")
    void chainedModifiersAreNotSupported() {
        run("2d6+3-1");

        // The parser splits on the first '+' and then parses "3-1" as an integer, which fails.
        // Refusing is the safe outcome, but a real VTT is expected to accept this expression.
        // When a proper dice grammar lands, this test should be replaced by a success case.
        assertEquals("[VTT] Invalid dice notation: 2d6+3-1", lastMessage());
    }

    @Test
    @DisplayName("other commands are ignored")
    void ignoresOtherCommands() {
        kernel.getEventBus().publish(
                new CommandExecutedEvent("whisper", new String[]{"2d6"}), new EventContext(PLAYER));
        assertEquals(0, out.size());
    }
}
