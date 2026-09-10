package org.vttale.vttale.module.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vttale.vttale.api.token.TokenRegistry;
import org.vttale.vttale.api.token.behavior.BehaviorDispatcher;
import org.vttale.vttale.kernel.VTTaleKernel;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class TokenModuleTest {

    @Test
    @DisplayName("enabling the module publishes the token services on the kernel")
    void registersServices() {
        VTTaleKernel kernel = new VTTaleKernel();
        kernel.getModuleRegistry().registerModule(new TokenModule());

        // VTTaleHytalePlugin reads both of these straight after registering the module.
        assertNotNull(kernel.getService(TokenRegistry.class));
        assertNotNull(kernel.getService(BehaviorDispatcher.class));
    }
}
