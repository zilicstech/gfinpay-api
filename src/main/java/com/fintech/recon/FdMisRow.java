package com.fintech.recon;

import java.time.Instant;
import java.util.Map;

public record FdMisRow(
        String provider,
        String productKey,
        String sheetName,
        String phone,
        String fullName,
        String partnerUserId,
        String matchRef,
        String partnerStatus,
        String sourceColumn,
        Instant partnerStatusAt,
        FdPartnerLifecycle lifecycle,
        Map<String, String> raw) {}
