package com.fintech.fdcards;

import com.fintech.platform.web.ApiException;
import java.util.LinkedHashMap;
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

    /** One read of every provider, for request paths that would otherwise look each code up. */
    public Directory directory() {
        Map<String, Row> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT code, enabled, novu, fallback_rank
                  FROM fd_providers
                 ORDER BY fallback_rank, code
                """)) {
            String code = String.valueOf(row.get("code"));
            int rank = row.get("fallback_rank") == null ? 100 : ((Number) row.get("fallback_rank")).intValue();
            byCode.put(code, new Row(Boolean.TRUE.equals(row.get("enabled")), Boolean.TRUE.equals(row.get("novu")), rank));
        }
        return new Directory(byCode);
    }

    public record Directory(Map<String, Row> byCode) {
        public boolean enabled(String code) {
            Row row = byCode.get(code);
            return row != null && row.enabled();
        }

        public boolean novu(String code) {
            Row row = byCode.get(code);
            return row != null && row.novu();
        }

        public boolean moduleEnabled() {
            return byCode.values().stream().anyMatch(Row::enabled);
        }

        public int fallbackRank(String code) {
            Row row = byCode.get(code);
            return row == null ? 1000 : row.fallbackRank();
        }

        public List<String> codes() {
            return List.copyOf(byCode.keySet());
        }
    }

    public record Row(boolean enabled, boolean novu, int fallbackRank) {}
}
