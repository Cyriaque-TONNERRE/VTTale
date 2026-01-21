package dev.giopalma.vttale.kernel;

import dev.giopalma.vttale.api.Kernel;
import dev.giopalma.vttale.api.KernelProvider;

/**
 * Implementation of KernelProvider that creates VTTaleKernel instances.
 */
public class VTTaleKernelProvider implements KernelProvider {
    @Override
    public Kernel createKernel() {
        return new VTTaleKernel();
    }
}
