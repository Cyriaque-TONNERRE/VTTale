package org.vttale.vttale.kernel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class VTTaleKernelTest {

    private interface DiceLike { }

    private static final class DiceA implements DiceLike { }

    private static final class DiceB implements DiceLike { }

    private VTTaleKernel kernel;

    @BeforeEach
    void setUp() {
        kernel = new VTTaleKernel();
    }

    @Test
    @DisplayName("the three core registries exist and are stable")
    void coreRegistries() {
        assertNotNull(kernel.getEventBus());
        assertNotNull(kernel.getCommandRegistry());
        assertNotNull(kernel.getModuleRegistry());
        assertSame(kernel.getEventBus(), kernel.getEventBus());
        assertSame(kernel.getCommandRegistry(), kernel.getCommandRegistry());
    }

    @Test
    @DisplayName("a registered service is returned as itself")
    void serviceRoundTrip() {
        DiceA service = new DiceA();
        kernel.registerService(DiceLike.class, service);
        assertSame(service, kernel.getService(DiceLike.class));
    }

    @Test
    @DisplayName("an unregistered service returns null rather than an empty Optional")
    void missingServiceReturnsNull() {
        // Documents the current contract: every caller must null-check, and
        // VTTaleHytalePlugin already does. Optional<T> would be the safer signature.
        assertNull(kernel.getService(DiceLike.class));
    }

    @Test
    @DisplayName("registering the same key twice silently replaces the service")
    void registrationOverwritesSilently() {
        DiceA first = new DiceA();
        DiceB second = new DiceB();
        kernel.registerService(DiceLike.class, first);
        kernel.registerService(DiceLike.class, second);

        // No exception, no warning: a module can steal another module's service by accident.
        assertSame(second, kernel.getService(DiceLike.class));
    }

    @Test
    @DisplayName("services are keyed by the exact class passed at registration")
    void servicesAreKeyedByDeclaredClass() {
        DiceA service = new DiceA();
        kernel.registerService(DiceLike.class, service);

        assertNull(kernel.getService(DiceA.class),
                "registering under an interface does not expose the implementation type");
    }
}
