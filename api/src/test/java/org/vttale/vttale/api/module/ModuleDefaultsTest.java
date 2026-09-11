package org.vttale.vttale.api.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModuleDefaultsTest {

    /** Minimal module: no override, so it exercises the defaults. */
    private static class BareModule implements Module {
        @Override
        public void onEnable(org.vttale.vttale.api.Kernel kernel) {
        }
    }

    @Test
    @DisplayName("default id is the fully qualified class name")
    void defaultIdIsFullyQualifiedName() {
        assertEquals(BareModule.class.getName(), new BareModule().id());
    }

    @Test
    @DisplayName("default requires no service")
    void defaultRequiresNothing() {
        assertEquals(Set.of(), new BareModule().requires());
    }
}
