package com.fintech.recon;

import org.springframework.stereotype.Component;

@Component
public class ZetFdReconHandler extends AbstractFdReconHandler {

    public ZetFdReconHandler(FdProviderReconActivator activator) {
        super(activator);
    }

    @Override
    public String reportType() {
        return "ZET";
    }
}
