package org.vttale.vttale.kernel.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.command.CommandOptions;
import org.vttale.vttale.api.events.RegisterCommandRequest;
import org.vttale.vttale.kernel.events.SimpleEventBus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleCommandRegistryTest {

    private SimpleEventBus bus;
    private SimpleCommandRegistry registry;
    private List<RegisterCommandRequest> published;

    @BeforeEach
    void setUp() {
        bus = new SimpleEventBus();
        registry = new SimpleCommandRegistry(bus);
        published = new ArrayList<>();
        bus.subscribe(RegisterCommandRequest.class, (e, ctx) -> published.add(e));
    }

    @Test
    @DisplayName("registering stores the command and announces it on the bus")
    void registerStoresAndPublishes() {
        registry.registerCommand("roll", "Roll dice");

        RegisterCommandRequest stored = registry.getRegisteredCommands().get("roll");
        assertEquals("roll", stored.getCommandName());
        assertEquals("Roll dice", stored.getCommandDescription());

        assertEquals(1, published.size());
        assertSame(stored, published.getFirst(),
                "platform adapters rely on receiving the very request that was stored");
    }

    @Test
    @DisplayName("the two-argument overload applies CommandOptions.defaults()")
    void defaultOptions() {
        registry.registerCommand("roll", "Roll dice");

        CommandOptions options = registry.getRegisteredCommands().get("roll").getOptions();
        assertFalse(options.isPlayerOnly());
        assertFalse(options.isHidden());
        assertNull(options.getPermission(), "no permission means no gate at all today");
    }

    @Test
    @DisplayName("explicit options are carried through to the adapter")
    void explicitOptionsArePreserved() {
        CommandOptions gmOnly = CommandOptions.builder()
                .playerOnly(true)
                .permission("vttale.gm")
                .hidden(true)
                .build();

        registry.registerCommand("gm", "Game master tools", gmOnly);

        CommandOptions stored = registry.getRegisteredCommands().get("gm").getOptions();
        assertTrue(stored.isPlayerOnly());
        assertTrue(stored.isHidden());
        assertEquals("vttale.gm", stored.getPermission());
    }

    @Test
    @DisplayName("re-registering a name silently replaces it and re-announces it")
    void reRegisteringOverwrites() {
        registry.registerCommand("roll", "First");
        registry.registerCommand("roll", "Second");

        assertEquals(1, registry.getRegisteredCommands().size());
        assertEquals("Second", registry.getRegisteredCommands().get("roll").getCommandDescription());
        assertEquals(2, published.size(),
                "the adapter is told twice, which means the platform command is registered twice");
    }

    @Test
    @DisplayName("the exposed map is unmodifiable")
    void registeredCommandsAreUnmodifiable() {
        registry.registerCommand("roll", "Roll dice");
        assertThrows(UnsupportedOperationException.class,
                () -> registry.getRegisteredCommands().clear());
    }
}
