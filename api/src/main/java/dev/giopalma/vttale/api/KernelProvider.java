package dev.giopalma.vttale.api;

/**
 * Service provider interface for creating Kernel instances.
 */
public interface KernelProvider {
    /**
     * Creates a new instance of the Kernel.
     *
     * @return a new Kernel instance
     */
    Kernel createKernel();
}
