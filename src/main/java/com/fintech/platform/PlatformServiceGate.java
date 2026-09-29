package com.fintech.platform;

import com.fintech.fdcards.FdProviderGate;
import com.fintech.platform.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PlatformServiceGate {

    public static final String DMT = "DMT";
    public static final String AEPS = "AEPS";
    public static final String UPI_CASHOUT = "UPI_CASHOUT";
    public static final String BBPS = "BBPS";
    public static final String LEAD_GEN = "LEAD_GEN";

    private final JdbcTemplate jdbc;
    private final FdProviderGate fdProviders;

    public PlatformServiceGate(JdbcTemplate jdbc, FdProviderGate fdProviders) {
        this.jdbc = jdbc;
        this.fdProviders = fdProviders;
    }

    public boolean isEnabled(String code) {
        var rows = jdbc.queryForList("SELECT enabled FROM platform_services WHERE code = ?", code);
        if (rows.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(rows.get(0).get("enabled"));
    }

    public void requireEnabled(String code) {
        if (!isEnabled(code)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SERVICE_DISABLED",
                    "This service is switched off by the platform");
        }
    }

    public void requireLeadSalesEnabled() {
        if (!fdProviders.isModuleEnabled() && !isEnabled(LEAD_GEN)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SERVICE_DISABLED",
                    "Customer sales are switched off by the platform");
        }
    }

    public void requireCatalogCategory(String categoryCode) {
        if ("FD_CARD".equalsIgnoreCase(categoryCode)) {
            fdProviders.requireModule();
        } else {
            requireEnabled(LEAD_GEN);
        }
    }

    public boolean isCatalogCategoryAvailable(String categoryCode) {
        if ("FD_CARD".equalsIgnoreCase(categoryCode)) {
            return fdProviders.isModuleEnabled();
        }
        return isEnabled(LEAD_GEN);
    }

    public List<Map<String, Object>> listEnabled() {
        return jdbc.queryForList("""
                SELECT code, name, description, sort_order
                  FROM platform_services
                 WHERE enabled = TRUE
                 ORDER BY sort_order, code
                """);
    }
}
