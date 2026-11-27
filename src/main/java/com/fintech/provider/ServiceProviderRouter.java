package com.fintech.provider;

import com.fintech.emerald.EmeraldProperties;
import com.fintech.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-provider channel router.
 * UPI cash-out and payout use the retailer's own priority (1, then 2, then 3).
 * AePS still uses the platform strategy. {@code ROUND_ROBIN} advances only after
 * {@link #noteSuccessfulSelection(String)}. A failed provider call does not try the
 * next channel, because money or a QR may already exist.
 */
@Service
public class ServiceProviderRouter {

    public static final String UPI_CASHOUT = "UPI_CASHOUT";
    public static final String AEPS = "AEPS";
    public static final String PAYOUT = "PAYOUT";
    public static final String DMT = "DMT";

    private static final Set<String> VALID_STRATEGIES = Set.of("ROUND_ROBIN", "PRIMARY_FALLBACK");
    private static final Set<String> VALID_SERVICES = Set.of(UPI_CASHOUT, AEPS, PAYOUT, DMT);

    public record RoutedChannel(
            UUID channelId,
            String channelKey,
            String providerCode,
            String pipeCode,
            String outletCode,
            String pipeOutletCode,
            String kycStatus,
            String bankSyncStatus,
            String configJson,
            String payeeVpa,
            UUID syncedBankAccountId
    ) {
        public RoutedChannel(
                UUID channelId,
                String channelKey,
                String providerCode,
                String pipeCode,
                String outletCode,
                String pipeOutletCode,
                String kycStatus,
                String bankSyncStatus,
                String configJson,
                String payeeVpa) {
            this(channelId, channelKey, providerCode, pipeCode, outletCode, pipeOutletCode,
                    kycStatus, bankSyncStatus, configJson, payeeVpa, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final EmeraldProperties emeraldProps;

    public ServiceProviderRouter(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public ServiceProviderRouter(JdbcTemplate jdbc, EmeraldProperties emeraldProps) {
        this.jdbc = jdbc;
        this.emeraldProps = emeraldProps;
    }

    private boolean isEmeraldEnabled() {
        return emeraldProps == null || emeraldProps.enabled();
    }

    /**
     * Selects the retailer's enabled channel for the service. UPI and payout use
     * {@code retailer_priority}. Emerald still needs full KYC. A channel that does not
     * require biometrics is eligible at {@code NOT_REQUIRED}.
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
            case DMT -> "c.supports_dmt";
            default -> throw new IllegalArgumentException("Unsupported service: " + svc);
        };

        String emeraldFilter = isEmeraldEnabled() ? "" : " AND c.provider_code <> 'EMERALD' ";
        String kycPredicate = DMT.equals(svc)
                ? " a.kyc_status = 'FULL_KYC' "
                : """
                    (
                         a.kyc_status = 'FULL_KYC'
                         OR (c.biometric_required = FALSE AND a.kyc_status = 'NOT_REQUIRED')
                    )
                  """;

        List<Map<String, Object>> candidates = jdbc.queryForList("""
                SELECT c.id AS channel_id,
                       c.channel_key,
                       c.provider_code,
                       c.pipe_code,
                       a.retailer_priority,
                       m.outlet_code,
                       a.pipe_outlet_code,
                       a.kyc_status,
                       a.bank_sync_status,
                       a.synced_bank_account_id,
                       a.payee_vpa,
                       c.config_json::text AS config_json
                  FROM provider_pipe_activations a
                  JOIN service_provider_channels c ON c.id = a.channel_id
                  JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                 WHERE a.user_id = ?
                   AND a.enabled_for_retailer = TRUE
                   AND a.onboarding_status IN ('ONBOARDED', 'ACTIVE')
                   AND a.retailer_priority BETWEEN 1 AND 3
                   AND """ + kycPredicate + """
                   AND c.active = TRUE
                   AND m.status = 'ACTIVE'
                   """ + emeraldFilter + """
                   AND """ + " " + serviceColumn + " = TRUE " + """
                 ORDER BY a.retailer_priority ASC, c.created_at ASC
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
                       AND (c.biometric_required = TRUE OR ? = 'DMT')
                       AND a.kyc_status <> 'FULL_KYC'
                    """, Integer.class, userId, svc);
            if (pendingBiometric != null && pendingBiometric > 0) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "BIOMETRIC_KYC_REQUIRED",
                        DMT.equals(svc)
                                ? "Complete your merchant Full KYC biometric verification to unlock Domestic Money Transfer."
                                : "Complete your merchant biometric verification to activate cash and settlement services.");
            }
            throw ApiException.of(HttpStatus.FORBIDDEN, "SERVICE_NOT_ACTIVATED",
                    DMT.equals(svc)
                            ? "Your Domestic Money Transfer terminal is pending activation. Please contact support."
                            : "Your merchant cash terminal is pending activation. Please contact support.");
        }

        String strategy = String.valueOf(policy.getOrDefault("strategy", "ROUND_ROBIN")).toUpperCase(Locale.ROOT);
        long counter = policy.get("rr_counter") instanceof Number n ? n.longValue() : 0L;
        return toRoutedChannel(choose(svc, candidates, strategy, counter));
    }

    /** UPI, payout, and DMT always take the first row, which is the lowest retailer priority. */
    static Map<String, Object> choose(String service, List<Map<String, Object>> orderedByPriority, String strategy, long counter) {
        boolean retailerOrder = UPI_CASHOUT.equals(service) || PAYOUT.equals(service) || DMT.equals(service);
        if (!retailerOrder && "ROUND_ROBIN".equals(strategy) && orderedByPriority.size() > 1) {
            return orderedByPriority.get((int) Math.floorMod(counter, orderedByPriority.size()));
        }
        return orderedByPriority.get(0);
    }

    static boolean kycEligible(boolean biometricRequired, String kycStatus) {
        if ("FULL_KYC".equals(kycStatus)) {
            return true;
        }
        return !biometricRequired && "NOT_REQUIRED".equals(kycStatus);
    }

    /** Advances round-robin only after the provider call itself succeeded. */
    public void noteSuccessfulSelection(String serviceCode) {
        String svc = normalizeService(serviceCode);
        jdbc.update("""
                UPDATE service_routing_policies
                   SET rr_counter = rr_counter + 1,
                       updated_at = now()
                 WHERE service_code = ?
                   AND strategy = 'ROUND_ROBIN'
                """, svc);
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
                       a.bank_sync_status,
                       a.synced_bank_account_id,
                       a.payee_vpa,
                       c.config_json::text AS config_json
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
        String whereClause = isEmeraldEnabled() ? "" : " WHERE c.provider_code <> 'EMERALD' ";
        List<Map<String, Object>> channels = jdbc.queryForList("""
                SELECT c.id, c.channel_key, c.provider_code, c.pipe_code, c.display_label,
                       c.supports_upi_cashout, c.supports_aeps, c.supports_payout, c.supports_dmt,
                       c.biometric_required, c.active, c.priority_order, c.updated_at,
                       (SELECT COUNT(*)::int FROM provider_pipe_activations a WHERE a.channel_id = c.id) AS onboarded_count,
                       (SELECT COUNT(*)::int FROM provider_pipe_activations a WHERE a.channel_id = c.id AND a.kyc_status = 'FULL_KYC') AS full_kyc_count
                  FROM service_provider_channels c
                """ + whereClause + """
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
                str(row.get("bank_sync_status")),
                jsonText(row.get("config_json")),
                str(row.get("payee_vpa")),
                (UUID) row.get("synced_bank_account_id"));
    }

    private static String jsonText(Object value) {
        if (value == null) {
            return "{}";
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equals(text) ? "{}" : text;
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

