package dev.giopalma.vttale.api;

import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Global entry point for the VTTale API.
 */
public final class VTTale {
    private static Kernel kernel;

    private VTTale() {
    }

    /**
     * Returns the global Kernel instance.
     *
     * @return the current Kernel instance
     * @throws IllegalStateException if the kernel has not been initialized
     */
    public static Kernel getKernel() {
        if (kernel == null) {
            throw new IllegalStateException("VTTale has not been initialized yet!");
        }
        return kernel;
    }

    /**
     * Initializes the global Kernel using the first available KernelProvider.
     *
     * @throws IllegalStateException if already initialized or no provider is found
     */
    public static void init() {
        if (VTTale.kernel != null) {
            throw new IllegalStateException("VTTale is already initialized!");
        }

        ServiceLoader<KernelProvider> loader = ServiceLoader.load(KernelProvider.class);
        KernelProvider provider = loader.findFirst()
                .orElseThrow(() -> new IllegalStateException("No KernelProvider found in classpath!"));

        VTTale.kernel = Objects.requireNonNull(provider.createKernel(), "Provider returned a null kernel");
    }
}