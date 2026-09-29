package com.fintech.reports;

import java.time.LocalDate;
import java.util.UUID;

public record AdminReportQuery(
        LocalDate from,
        LocalDate to,
        String saleType,
        String saleProvider,
        String status,
        UUID retailerId,
        UUID distributorId,
        UUID hubId) {}
