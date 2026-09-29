package com.fintech.fdcards;

import com.fintech.platform.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class FdProviderGate {

    public static final String ZET = "ZET";
    public static final String PAYSPRINT = "PAYSPRINT";
    public static final String GROWMORE = "GROWMORE";

    private final JdbcTemplate jdbc;

    public FdProviderGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isModuleEnabled() {
        var rows = jdbc.queryForList("SELECT 1 FROM fd_providers WHERE enabled = TRUE LIMIT 1");
        return !rows.isEmpty();
    }

    public void requireModule() {
        if (!isModuleEnabled()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FD_MODULE_DISABLED",
                    "FD card sales are switched off by the platform");
        }
    }

    public boolean isProviderEnabled(String code) {
        var rows = jdbc.queryForList("SELECT enabled FROM fd_providers WHERE code = ?", code);
        if (rows.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(rows.get(0).get("enabled"));
    }

    public void requireProvider(String code) {
        if (!isProviderEnabled(code)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FD_PROVIDER_DISABLED",
                    "This FD card provider is switched off");
        }
    }

    public List<Map<String, Object>> listEnabledForDesk() {
        return jdbc.queryForList("""
                SELECT code, name, fallback_rank
                  FROM fd_providers
                 WHERE enabled = TRUE
                 ORDER BY fallback_rank, code
                """);
    }

    public boolean isNovuProvider(String code) {
        var rows = jdbc.queryForList("SELECT novu FROM fd_providers WHERE code = ?", code);
        if (rows.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(rows.get(0).get("novu"));
    }
}
