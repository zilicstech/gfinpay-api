package com.fintech.recon;

import org.springframework.stereotype.Component;

@Component
public class GrowmoreFdReconHandler extends AbstractFdReconHandler {

    public GrowmoreFdReconHandler(FdProviderReconActivator activator) {
        super(activator);
    }

    @Override
    public String reportType() {
        return "GROWMORE";
    }
}
