package com.fintech.recon;

import com.fintech.platform.web.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class FdReconReportFactory {

    private final Map<String, FdReconReportHandler> handlers;

    public FdReconReportFactory(List<FdReconReportHandler> handlers) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                h -> h.reportType().toUpperCase(Locale.ROOT),
                Function.identity()));
    }

    public FdReconReportHandler forType(String reportType) {
        if (reportType == null || reportType.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REPORT_TYPE",
                    "Select a report type");
        }
        String code = reportType.trim().toUpperCase(Locale.ROOT);
        FdReconReportHandler handler = handlers.get(code);
        if (handler == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REPORT_TYPE",
                    "No recon handler registered for " + code);
        }
        return handler;
    }
}
