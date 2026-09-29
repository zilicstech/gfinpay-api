package com.fintech.reports;

import com.fintech.platform.web.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AdminReportFactory {

    private final Map<String, AdminReportHandler> handlers;

    public AdminReportFactory(List<AdminReportHandler> handlers) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
                h -> h.reportType().toUpperCase(Locale.ROOT),
                Function.identity()));
    }

    public List<Map<String, String>> types() {
        return handlers.values().stream()
                .map(h -> Map.of("type", h.reportType(), "label", h.label()))
                .toList();
    }

    public AdminReportHandler forType(String reportType) {
        if (reportType == null || reportType.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REPORT_TYPE",
                    "Select a report type");
        }
        String code = reportType.trim().toUpperCase(Locale.ROOT);
        AdminReportHandler handler = handlers.get(code);
        if (handler == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REPORT_TYPE",
                    "No report handler registered for " + code);
        }
        return handler;
    }
}
