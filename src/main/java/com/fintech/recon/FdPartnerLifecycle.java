package com.fintech.recon;

/** Lifecycle transition suggested by a partner MIS row (maps to sales_leads.state). */
public enum FdPartnerLifecycle {
    NONE,
    OPENED,
    IN_PROGRESS,
    ACTIVATED
}
