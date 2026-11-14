package com.fintech.provider;

import com.fintech.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-provider channel router supporting {@code ROUND_ROBIN} and {@code PRIMARY_FALLBACK}
 * selection across active, {@code FULL_KYC}-activated provider channels for a retailer.
 */
@Service
public class ServiceProviderRouter {

    public static final String UPI_CASHOUT = "UPI_CASHOUT";
    public static final String AEPS = "AEPS";
    public static final String PAYOUT = "PAYOUT";

    private static final Set<String> VALID_STRATEGIES = Set.of("ROUND_ROBIN", "PRIMARY_FALLBACK");
    private static final Set<String> VALID_SERVICES = Set.of(UPI_CASHOUT, AEPS, PAYOUT);

    public record RoutedChannel(
            UUID channelId,
            String channelKey,
            String providerCode,
            String pipeCode,
            String outletCode,
            String pipeOutletCode,
            String kycStatus,
            String bankSyncStatus
    ) {}

    private final JdbcTemplate jdbc;

    public ServiceProviderRouter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Selects the next eligible {@code FULL_KYC} channel for the retailer and service.
     */
    @Transactional
    public RoutedChannel selectChannel(UUID userId, String serviceCode) {
        String svc = normalizeService(serviceCode);
        Map<String, Object> policy = loadPolicy(svc);
        if (!Boolean.TRUE.equals(policy.get("active"))) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_ROUTING_DISABLED",
                    "This service is temporarily disabled for maintenance.");
        }

        String serviceColumn = switch (svc) {
            case UPI_CASHOUT -> "c.supports_upi_cashout";
            case AEPS -> "c.supports_aeps";
            case PAYOUT -> "c.supports_payout";
            default -> throw new IllegalArgumentException("Unsupported service: " + svc);
        };

        List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT c.id AS channel_id,
                       c.channel_key,
                       c.provider_code,
                       c.pipe_code,
                       c.priority_order,
                       m.outlet_code,
                       a.pipe_outlet_code,
                       a.kyc_status,
                       a.bank_sync_status
                  FROM provider_pipe_activations a
                  JOIN service_provider_channels c ON c.id = a.channel_id
                  JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                 WHERE a.user_id = ?
                   AND a.enabled_for_retailer = TRUE
                   AND a.onboarding_status IN ('ONBOARDED', 'ACTIVE')
                   AND a.kyc_status = 'FULL_KYC'
                   AND c.active = TRUE
                   AND m.status = 'ACTIVE'
                   AND """ + " " + serviceColumn + " = TRUE " + """
                 ORDER BY c.priority_order ASC, c.created_at ASC
                """, userId);

        if (candidates.isEmpty()) {
            // Determine why no channel is active so we give a clear provider-opaque actionable message
            Integer pendingBiometric = jdbc.queryForObject("""
                    SELECT COUNT(*)::int
                      FROM provider_pipe_activations a
                      JOIN service_provider_channels c ON c.id = a.channel_id
                     WHERE a.user_id = ?
                       AND a.enabled_for_retailer = TRUE
                       AND c.active = TRUE
                       AND a.kyc_status <> 'FULL_KYC'
                    """, Integer.class, userId);
            if (pendingBiometric != null && pendingBiometric > 0) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "BIOMETRIC_KYC_REQUIRED",
                        "Complete your merchant biometric verification to activate cash and settlement services.");
            }
            throw ApiException.of(HttpStatus.FORBIDDEN, "SERVICE_NOT_ACTIVATED",
                    "Your merchant cash terminal is pending activation. Please contact support.");
        }

        String strategy = String.valueOf(policy.getOrDefault("strategy", "ROUND_ROBIN")).toUpperCase(Locale.ROOT);
        Map<String, Object> chosen;
        if ("ROUND_ROBIN".equals(strategy) && candidates.size() > 1) {
            Long nextCounter = jdbc.queryForObject("""
                    UPDATE service_routing_policies
                       SET rr_counter = rr_counter + 1,
                           updated_at = now()
                     WHERE service_code = ?
                 RETURNING rr_counter
                    """, Long.class, svc);
            long idx = Math.floorMod((nextCounter == null ? 1L : nextCounter) - 1L, candidates.size());
            chosen = candidates.get((int) idx);
        } else {
            chosen = candidates.get(0);
        }

        return toRoutedChannel(chosen);
    }

    public RoutedChannel resolveSpecificChannelForUser(UUID userId, UUID channelId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.id AS channel_id,
                       c.channel_key,
                       c.provider_code,
                       c.pipe_code,
                       m.outlet_code,
                       a.pipe_outlet_code,
                       a.kyc_status,
                       a.bank_sync_status
                  FROM provider_pipe_activations a
                  JOIN service_provider_channels c ON c.id = a.channel_id
                  JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                 WHERE a.user_id = ? AND c.id = ?
                """, userId, channelId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CHANNEL_NOT_FOUND",
                    "Merchant terminal channel was not found.");
        }
        return toRoutedChannel(rows.get(0));
    }

    public Map<String, Object> listPlatformConfiguration() {
        List<Map<String, Object>> channels = jdbc.queryForList("""
                SELECT c.id, c.channel_key, c.provider_code, c.pipe_code, c.display_label,
                       c.supports_upi_cashout, c.supports_aeps, c.supports_payout,
                       c.biometric_required, c.active, c.priority_order, c.updated_at,
                       (SELECT COUNT(*)::int FROM provider_pipe_activations a WHERE a.channel_id = c.id) AS onboarded_count,
                       (SELECT COUNT(*)::int FROM provider_pipe_activations a WHERE a.channel_id = c.id AND a.kyc_status = 'FULL_KYC') AS full_kyc_count
                  FROM service_provider_channels c
                 ORDER BY c.priority_order ASC, c.created_at ASC
                """);
        List<Map<String, Object>> policies = jdbc.queryForList("""
                SELECT service_code, strategy, rr_counter, active, updated_at
                  FROM service_routing_policies
                 ORDER BY service_code ASC
                """);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channels", channels);
        out.put("policies", policies);
        return out;
    }

    @Transactional
    public Map<String, Object> updateChannel(UUID channelId, Boolean active, Integer priorityOrder, String displayLabel) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, active, priority_order, display_label FROM service_provider_channels WHERE id = ?", channelId);
        if (existing.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CHANNEL_NOT_FOUND", "Provider channel not found");
        }
        Map<String, Object> cur = existing.get(0);
        boolean nextActive = active != null ? active : Boolean.TRUE.equals(cur.get("active"));
        int nextPriority = priorityOrder != null ? Math.max(1, priorityOrder) : ((Number) cur.get("priority_order")).intValue();
        String nextLabel = (displayLabel != null && !displayLabel.isBlank())
                ? displayLabel.trim()
                : String.valueOf(cur.get("display_label"));
        jdbc.update("""
                UPDATE service_provider_channels
                   SET active = ?, priority_order = ?, display_label = ?, updated_at = now()
                 WHERE id = ?
                """, nextActive, nextPriority, nextLabel, channelId);
        return listPlatformConfiguration();
    }

    @Transactional
    public Map<String, Object> updatePolicy(String serviceCodeRaw, String strategyRaw, Boolean active) {
        String svc = normalizeService(serviceCodeRaw);
        Map<String, Object> cur = loadPolicy(svc);
        String nextStrategy = strategyRaw == null || strategyRaw.isBlank()
                ? String.valueOf(cur.get("strategy"))
                : strategyRaw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_STRATEGIES.contains(nextStrategy)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STRATEGY",
                    "Routing strategy must be ROUND_ROBIN or PRIMARY_FALLBACK");
        }
        boolean nextActive = active != null ? active : Boolean.TRUE.equals(cur.get("active"));
        jdbc.update("""
                UPDATE service_routing_policies
                   SET strategy = ?, active = ?, updated_at = now()
                 WHERE service_code = ?
                """, nextStrategy, nextActive, svc);
        return listPlatformConfiguration();
    }

    private Map<String, Object> loadPolicy(String svc) {
        try {
            return jdbc.queryForMap(
                    "SELECT service_code, strategy, rr_counter, active FROM service_routing_policies WHERE service_code = ?",
                    svc);
        } catch (EmptyResultDataAccessException e) {
            return Map.of("service_code", svc, "strategy", "ROUND_ROBIN", "rr_counter", 0L, "active", true);
        }
    }

    private static RoutedChannel toRoutedChannel(Map<String, Object> row) {
        return new RoutedChannel(
                (UUID) row.get("channel_id"),
                str(row.get("channel_key")),
                str(row.get("provider_code")),
                str(row.get("pipe_code")),
                str(row.get("outlet_code")),
                str(row.get("pipe_outlet_code")),
                str(row.get("kyc_status")),
                str(row.get("bank_sync_status")));
    }

    private static String normalizeService(String raw) {
        String svc = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_SERVICES.contains(svc)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SERVICE_CODE",
                    "Unsupported service code: " + raw);
        }
        return svc;
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}

