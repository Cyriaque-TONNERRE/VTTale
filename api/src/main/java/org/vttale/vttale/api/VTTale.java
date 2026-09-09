package org.vttale.vttale.api;

import java.util.Objects;

/**
 * Global entry point for the VTTale API.
 * <p>
 * The platform (Hytale plugin) creates the kernel and injects it at startup:
 * <pre>{@code
 * VTTale.init(new VTTaleKernel());
 * }</pre>
 * Modules then read shared state via {@code VTTale.getKernel()}.
 */
public final class VTTale {

    private static volatile Kernel kernel;

    private VTTale() {
    }

    /**
     * Returns the global Kernel instance.
     *
     * @return the current Kernel instance
     * @throws IllegalStateException if the kernel has not been initialized
     */
    public static Kernel getKernel() {
        Kernel k = kernel;
        if (k == null) {
            throw new IllegalStateException("VTTale has not been initialized yet!");
        }
        return k;
    }

    /**
     * Initializes the global Kernel. Called once by the platform at startup.
     *
     * @param kernel the kernel instance to expose
     * @throws IllegalStateException if already initialized
     */
    public static void init(Kernel kernel) {
        if (VTTale.kernel != null) {
            throw new IllegalStateException("VTTale is already initialized!");
        }
        VTTale.kernel = Objects.requireNonNull(kernel, "kernel");
    }
}
