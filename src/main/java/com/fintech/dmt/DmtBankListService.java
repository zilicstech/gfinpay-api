package com.fintech.dmt;

import com.fintech.emerald.EmeraldTraceContext;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PageWindow;
import com.fintech.provider.ProviderDispatch;
import com.fintech.provider.ProviderRegistry;
import com.fintech.provider.ServiceProviderRouter;
import com.fintech.provider.canonical.ProviderModels;
import com.fintech.provider.port.DmtPort;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DmtBankListService {

    private static final Logger log = LoggerFactory.getLogger(DmtBankListService.class);
    private static final Duration SYNC_STALE_THRESHOLD = Duration.ofMinutes(60);

    private final JdbcTemplate jdbc;
    private final ProviderDispatch dispatch;
    private final ProviderRegistry registry;

    public DmtBankListService(JdbcTemplate jdbc) {
        this(jdbc, null, null);
    }

    @Autowired
    public DmtBankListService(JdbcTemplate jdbc, ProviderDispatch dispatch, ProviderRegistry registry) {
        this.jdbc = jdbc;
        this.dispatch = dispatch;
        this.registry = registry;
    }

    public Map<String, Object> list(String query, Boolean active, Integer limit, Integer offset) {
        String q = query == null ? "" : query.trim();
        String like = "%" + q + "%";
        String flag = active == null ? "" : (active ? "true" : "false");
        int size = PageWindow.limit(limit);
        int skip = PageWindow.offset(offset);
        Integer total = jdbc.queryForObject("""
                SELECT count(*)::int FROM dmt_bank_list
                 WHERE (? = '' OR bank_id ILIKE ? OR bank_name ILIKE ? OR COALESCE(ifsc_global, '') ILIKE ?)
                   AND (? = '' OR active = (? = 'true'))
                """, Integer.class, q, like, like, like, flag, flag);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT id, bank_id, bank_name, ifsc_alias, ifsc_global,
                       imps_enabled, neft_enabled, imps_failure_rate, neft_failure_rate,
                       provider_code, active, last_synced_at, created_at, updated_at
                  FROM dmt_bank_list
                 WHERE (? = '' OR bank_id ILIKE ? OR bank_name ILIKE ? OR COALESCE(ifsc_global, '') ILIKE ?)
                   AND (? = '' OR active = (? = 'true'))
                 ORDER BY bank_name
                 LIMIT ? OFFSET ?
                """, q, like, like, like, flag, flag, size, skip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total == null ? 0 : total);
        return out;
    }

    /** Active banks for the retailer desk, auto-syncing from the active DMT switch if stale (>60m). */
    public List<Map<String, Object>> active(String query, Integer limit) {
        return activeForRetailer(null, query, limit);
    }

    public List<Map<String, Object>> activeForRetailer(UUID retailerUserId, String query, Integer limit) {
        if (isDirectoryStale()) {
            try {
                syncFromProvider(retailerUserId, false);
            } catch (Exception ex) {
                log.debug("DMT_BANK passive sync skipped: {}", ex.getMessage());
            }
        }
        String q = query == null ? "" : query.trim();
        String like = "%" + q + "%";
        int maxRows = limit == null ? 40 : Math.max(1, Math.min(limit, 100));
        return jdbc.queryForList("""
                SELECT bank_id, bank_name, ifsc_alias, ifsc_global,
                       imps_enabled, neft_enabled, imps_failure_rate, neft_failure_rate
                  FROM dmt_bank_list
                 WHERE active
                   AND (? = '' OR bank_id ILIKE ? OR bank_name ILIKE ? OR COALESCE(ifsc_global, '') ILIKE ?)
                 ORDER BY bank_name
                 LIMIT ?
                """, q, like, like, like, maxRows);
    }

    public Map<String, Object> syncFromAdmin(UUID adminUserId) {
        return syncFromProvider(adminUserId, true);
    }

    public Map<String, Object> syncFromProvider(UUID actorUserId, boolean force) {
        if (registry == null) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                    "DMT provider registry is not available.");
        }
        ServiceProviderRouter.RoutedChannel channel = null;
        DmtPort port = null;

        if (actorUserId != null && dispatch != null) {
            try {
                ProviderDispatch.RoutedDmt routed = dispatch.dmt(actorUserId);
                channel = routed.channel();
                port = routed.port();
            } catch (Exception ignored) {
                // Admin user or unprovisioned user — fall back to platform DMT channel + any active outlet
            }
        }

        if (port == null) {
            List<Map<String, Object>> chRows = jdbc.queryForList("""
                    SELECT c.id AS channel_id, c.channel_key, c.provider_code, c.pipe_code, c.config_json::text AS config_json,
                           (SELECT m.outlet_code FROM provider_merchant_accounts m
                             WHERE m.provider_code = c.provider_code AND m.status = 'ACTIVE'
                               AND m.outlet_code IS NOT NULL AND m.outlet_code <> ''
                             ORDER BY m.updated_at DESC LIMIT 1) AS outlet_code
                      FROM service_provider_channels c
                     WHERE c.supports_dmt = TRUE AND c.active = TRUE
                     ORDER BY c.priority_order ASC
                     LIMIT 1
                    """);
            if (chRows.isEmpty()) {
                if (force) {
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_CHANNEL_NOT_CONFIGURED",
                            "No active DMT provider channel is configured.");
                }
                return Map.of("synced", 0, "inserted", 0, "updated", 0);
            }
            Map<String, Object> row = chRows.get(0);
            String providerCode = String.valueOf(row.get("provider_code")).trim();
            String outletCode = row.get("outlet_code") == null ? "" : String.valueOf(row.get("outlet_code")).trim();
            channel = new ServiceProviderRouter.RoutedChannel(
                    (UUID) row.get("channel_id"),
                    String.valueOf(row.get("channel_key")),
                    providerCode,
                    String.valueOf(row.get("pipe_code")),
                    outletCode,
                    "",
                    "FULL_KYC",
                    "SYNCED",
                    String.valueOf(row.get("config_json")),
                    "");
            port = registry.dmt(providerCode);
        }

        List<ProviderModels.DmtBankItem> liveBanks;
        try (var ignored = EmeraldTraceContext.open(actorUserId, "DMT_BANK_SYNC", null)) {
            liveBanks = port.listBanks(channel, force);
        }
        if (liveBanks == null || liveBanks.isEmpty()) {
            if (force) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_BANK_SYNC_EMPTY",
                        "Provider returned an empty DMT bank list. Ensure at least one active merchant outlet is onboarded.");
            }
            return Map.of("synced", 0, "inserted", 0, "updated", 0);
        }
        return upsertSyncedBanks(channel.providerCode(), liveBanks);
    }

    @Transactional
    protected Map<String, Object> upsertSyncedBanks(String providerCode, List<ProviderModels.DmtBankItem> liveBanks) {
        Set<String> known = new HashSet<>(jdbc.queryForList("SELECT bank_id FROM dmt_bank_list", String.class));
        int inserted = 0;
        int updated = 0;
        String prov = providerCode == null ? "INSTANTPAY" : providerCode.trim().toUpperCase(Locale.ROOT);
        for (ProviderModels.DmtBankItem b : liveBanks) {
            if (b.bankId() == null || b.bankId().isBlank() || b.name() == null || b.name().isBlank()) {
                continue;
            }
            String id = b.bankId().trim();
            String name = b.name().trim().replaceAll("\\s+", " ");
            String alias = b.ifscAlias() == null || b.ifscAlias().isBlank() ? null : b.ifscAlias().trim().toUpperCase(Locale.ROOT);
            String global = b.ifscGlobal() == null || b.ifscGlobal().isBlank() ? null : b.ifscGlobal().trim().toUpperCase(Locale.ROOT);
            jdbc.update("""
                    INSERT INTO dmt_bank_list
                        (bank_id, bank_name, ifsc_alias, ifsc_global, imps_enabled, neft_enabled,
                         imps_failure_rate, neft_failure_rate, provider_code, active, last_synced_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, now(), now())
                    ON CONFLICT (bank_id) DO UPDATE
                       SET bank_name = EXCLUDED.bank_name,
                           ifsc_alias = EXCLUDED.ifsc_alias,
                           ifsc_global = EXCLUDED.ifsc_global,
                           imps_enabled = EXCLUDED.imps_enabled,
                           neft_enabled = EXCLUDED.neft_enabled,
                           imps_failure_rate = EXCLUDED.imps_failure_rate,
                           neft_failure_rate = EXCLUDED.neft_failure_rate,
                           provider_code = EXCLUDED.provider_code,
                           last_synced_at = now(),
                           updated_at = now()
                    """,
                    id, name, alias, global, b.impsEnabled(), b.neftEnabled(),
                    b.impsFailureRate(), b.neftFailureRate(), prov);
            if (known.add(id)) {
                inserted++;
            } else {
                updated++;
            }
        }
        Integer total = jdbc.queryForObject("SELECT count(*)::int FROM dmt_bank_list", Integer.class);
        log.info("DMT_BANK sync provider={} inserted={} updated={} total={}", prov, inserted, updated, total);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("synced", inserted + updated);
        out.put("inserted", inserted);
        out.put("updated", updated);
        out.put("total", total == null ? 0 : total);
        return out;
    }

    private boolean isDirectoryStale() {
        Timestamp last = jdbc.queryForObject("SELECT MAX(last_synced_at) FROM dmt_bank_list", Timestamp.class);
        if (last == null) {
            return true;
        }
        return Duration.between(last.toInstant(), Instant.now()).compareTo(SYNC_STALE_THRESHOLD) > 0;
    }

    @Transactional
    public Map<String, Object> create(String bankId, String bankName, String ifscGlobal) {
        String id = cleanId(bankId);
        String name = cleanName(bankName);
        String cleanIfsc = (ifscGlobal == null || ifscGlobal.isBlank()) ? null : DmtCasaTexts.ifsc(ifscGlobal);
        try {
            UUID rowId = jdbc.queryForObject("""
                    INSERT INTO dmt_bank_list (bank_id, bank_name, ifsc_global, active)
                    VALUES (?, ?, ?, TRUE)
                    RETURNING id
                    """, UUID.class, id, name, cleanIfsc);
            log.info("DMT_BANK created bankId={}", id);
            return one(rowId);
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.CONFLICT, "BANK_EXISTS", "This bank id is already in the list.");
        }
    }

    @Transactional
    public Map<String, Object> create(String bankId, String bankName) {
        return create(bankId, bankName, null);
    }

    @Transactional
    public Map<String, Object> setActive(UUID id, boolean active) {
        int n = jdbc.update("""
                UPDATE dmt_bank_list SET active = ?, updated_at = now() WHERE id = ?
                """, active, id);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BANK_NOT_FOUND", "That bank is not in the list.");
        }
        log.info("DMT_BANK active id={} active={}", id, active);
        return one(id);
    }

    @Transactional
    public Map<String, Object> upload(MultipartFile file) {
        List<DmtBankWorkbook.RowData> rows = DmtBankWorkbook.read(file);
        Set<String> known = new HashSet<>(jdbc.queryForList("SELECT bank_id FROM dmt_bank_list", String.class));
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        for (DmtBankWorkbook.RowData row : rows) {
            String id;
            String name;
            try {
                id = cleanId(row.bankId());
                name = cleanName(row.bankName());
            } catch (ApiException e) {
                skipped++;
                continue;
            }
            jdbc.update("""
                    INSERT INTO dmt_bank_list (bank_id, bank_name, active)
                    VALUES (?, ?, TRUE)
                    ON CONFLICT (bank_id) DO UPDATE
                       SET bank_name = EXCLUDED.bank_name,
                           updated_at = now()
                    """, id, name);
            if (known.add(id)) {
                inserted++;
            } else {
                updated++;
            }
        }
        log.info("DMT_BANK upload inserted={} updated={} skipped={}", inserted, updated, skipped);
        return Map.of("inserted", inserted, "updated", updated, "skipped", skipped);
    }

    public Map<String, Object> findActiveByBankId(String bankId) {
        if (bankId == null || bankId.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT bank_id, bank_name, ifsc_alias, ifsc_global, imps_enabled, neft_enabled
                  FROM dmt_bank_list
                 WHERE bank_id = ? AND active = TRUE
                """, bankId.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> one(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, bank_id, bank_name, ifsc_alias, ifsc_global, imps_enabled, neft_enabled,
                       imps_failure_rate, neft_failure_rate, active, last_synced_at, created_at, updated_at
                  FROM dmt_bank_list WHERE id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BANK_NOT_FOUND", "That bank is not in the list.");
        }
        return rows.get(0);
    }

    private static String cleanId(String bankId) {
        String id = bankId == null ? "" : bankId.trim();
        if (id.isBlank() || id.length() > 40) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BANK_ID", "Enter a bank id.");
        }
        return id;
    }

    private static String cleanName(String bankName) {
        String name = bankName == null ? "" : bankName.trim().replaceAll("\\s+", " ");
        if (name.isBlank() || name.length() > 200) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BANK_NAME", "Enter a bank name.");
        }
        return name;
    }
}
