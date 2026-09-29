package com.fintech.recon;

import org.springframework.stereotype.Component;

@Component
public class PaysprintFdReconHandler extends AbstractFdReconHandler {

    public PaysprintFdReconHandler(FdProviderReconActivator activator) {
        super(activator);
    }

    @Override
    public String reportType() {
        return "PAYSPRINT";
    }
}
