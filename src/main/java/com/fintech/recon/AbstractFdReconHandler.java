package com.fintech.recon;

import org.springframework.web.multipart.MultipartFile;

/**
 * Accepts the weekly workbook, then activates that provider's open FD sales
 * until partner-specific Excel matching is implemented in {@link #parseWorkbook}.
 */
public abstract class AbstractFdReconHandler implements FdReconReportHandler {

    private final FdProviderReconActivator activator;

    protected AbstractFdReconHandler(FdProviderReconActivator activator) {
        this.activator = activator;
    }

    @Override
    public final FdReconOutcome reconcile(MultipartFile file) {
        parseWorkbook(file);
        return activator.activateOpenLeads(reportType());
    }

    /** Replace with partner-specific Excel parsing and lead matching. */
    protected void parseWorkbook(MultipartFile file) {
        // no-op until this report type's columns are specified
    }
}
