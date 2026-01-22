package org.vttale.vttale.kernel;

import org.vttale.vttale.api.Kernel;
import org.vttale.vttale.api.KernelProvider;

/**
 * Implementation of KernelProvider that creates VTTaleKernel instances.
 */
public class VTTaleKernelProvider implements KernelProvider {
    @Override
    public Kernel createKernel() {
        return new VTTaleKernel();
    }
}
