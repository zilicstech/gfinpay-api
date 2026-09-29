package com.fintech.reports;

import java.util.Map;

/**
 * One implementation per admin report type. Date range and parsing stay inside the handler
 * so new reports do not change a shared service.
 */
public interface AdminReportHandler {

    String reportType();

    String label();

    Map<String, Object> filters();

    Map<String, Object> run(AdminReportQuery query);
}
