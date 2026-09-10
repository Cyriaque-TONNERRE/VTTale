package org.vttale.vttale.gamesystem.dnd5e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.gamesystem.GameSystem;
import org.vttale.vttale.kernel.VTTaleKernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DND5EGameSystemTest {

    private Kernel kernel;
    private DND5EGameSystem system;

    @BeforeEach
    void setUp() {
        kernel = new VTTaleKernel();
        system = new DND5EGameSystem();
    }

    @Test
    @DisplayName("onEnable registers the system under GameSystem.class")
    void registersItselfAsService() {
        system.onEnable(kernel);

        assertSame(system, kernel.getService(GameSystem.class));
        // Services are keyed by the exact class passed at registration.
        assertNull(kernel.getService(DND5EGameSystem.class));
    }

    @Test
    @DisplayName("carries its identity")
    void identity() {
        assertEquals("dnd5e", system.id());
        assertEquals("1.0", system.version());
    }

    @Test
    @DisplayName("owns no component type yet")
    void ownsNoComponents() {
        assertTrue(system.components().isEmpty());
    }
}
