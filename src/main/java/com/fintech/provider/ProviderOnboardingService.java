package com.fintech.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.emerald.EmeraldClient;
import com.fintech.emerald.EmeraldProperties;
import com.fintech.emerald.EmeraldTraceContext;
import com.fintech.finance.RetailerBankService;
import com.fintech.identity.AdminAccess;
import com.fintech.platform.crypto.FieldCipher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.verification.AadhaarValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-facing multi-provider onboarding orchestrator (Step 1: Onboard to Emerald with default pipe {@code INSTANTPAY},
 * bank sync, live outlet refresh, per-retailer channel toggles, and 24-column multi-vendor CSV export).
 */
@Service
public class ProviderOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(ProviderOnboardingService.class);
    public static final String PROVIDER_EMERALD = "EMERALD";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AdminAccess access;
    private final FieldCipher fieldCipher;
    private final EmeraldClient emeraldClient;
    private final EmeraldProperties emeraldProps;
    private final RetailerOnboardingProfileAssembler assembler;
    private final RetailerBankService retailerBanks;

    @Autowired
    public ProviderOnboardingService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AdminAccess access,
            FieldCipher fieldCipher,
            EmeraldClient emeraldClient,
            EmeraldProperties emeraldProps,
            RetailerOnboardingProfileAssembler assembler,
            RetailerBankService retailerBanks) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.access = access;
        this.fieldCipher = fieldCipher;
        this.emeraldClient = emeraldClient;
        this.emeraldProps = emeraldProps;
        this.assembler = assembler;
        this.retailerBanks = retailerBanks;
    }

    public ProviderOnboardingService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AdminAccess access,
            FieldCipher fieldCipher,
            EmeraldClient emeraldClient,
            EmeraldProperties emeraldProps,
            RetailerOnboardingProfileAssembler assembler) {
        this(jdbc, mapper, access, fieldCipher, emeraldClient, emeraldProps, assembler, null);
    }

    public Map<String, Object> getRetailerProviders(UUID userId) {
        access.assertNetworkUser(userId);
        return buildAdminView(userId);
    }

    @Transactional
    public Map<String, Object> onboardToEmerald(
            AuthPrincipal admin,
            UUID userId,
            String aadhaarOverride,
            String emailOverride,
            String requestedPipe) {
        access.assertNetworkUser(userId);
        ensureProfileRow(userId);

        // Allow Admin to backfill missing 12-digit Aadhaar or email for legacy retailers prior to onboarding
        if (aadhaarOverride != null && !aadhaarOverride.isBlank()) {
            String last4 = jdbc.queryForObject(
                    "SELECT COALESCE(aadhaar_last4, '') FROM agent_kyc_profiles WHERE user_id = ?",
                    String.class, userId);
            String validAadhaar = AadhaarValidator.validateAndNormalize(aadhaarOverride, last4);
            jdbc.update("""
                    UPDATE agent_kyc_profiles
                       SET aadhaar_enc = ?,
                           aadhaar_last4 = CASE WHEN aadhaar_last4 IS NULL OR aadhaar_last4 = '' THEN ? ELSE aadhaar_last4 END,
                           updated_at = now()
                     WHERE user_id = ?
                    """, fieldCipher.encrypt(validAadhaar), validAadhaar.substring(8), userId);
        }
        if (emailOverride != null && !emailOverride.isBlank()) {
            String cleanEmail = emailOverride.trim().toLowerCase(Locale.ROOT);
            if (!cleanEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMAIL",
                        "Enter a valid email address for the retailer");
            }
            jdbc.update("UPDATE users SET email = ?, updated_at = now() WHERE id = ?", cleanEmail, userId);
        }

        RetailerOnboardingProfileAssembler.RetailerProviderDossier d = assembler.assemble(userId);
        if (!d.readyForEmerald()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ONBOARDING_FIELDS_MISSING",
                    "Missing required retailer fields for Emerald onboarding: " + String.join(", ", d.missingFields()));
        }

        String pipe = (requestedPipe == null || requestedPipe.isBlank())
                ? emeraldProps.defaultPipe()
                : requestedPipe.trim().toUpperCase(Locale.ROOT);
        UUID channelId = ensureEmeraldChannel(pipe);

        EmeraldClient.OutletResponse createRes;
        String outletCode;
        List<EmeraldClient.OutletPipeSummary> pipes;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP1_ONBOARD", d.bcAgentId())) {
            createRes = emeraldClient.createOutlet(new EmeraldClient.CreateOutletRequest(
                    d.mobile(),
                    d.firstName(),
                    d.lastName(),
                    d.gender(),
                    d.pan(),
                    d.email(),
                    d.shopName(),
                    d.companyName(),
                    d.aadhaar(),
                    d.dateOfBirth(),
                    d.latitude(),
                    d.longitude(),
                    pipe,
                    d.gstin(),
                    d.shopAddress(),
                    d.shopArea(),
                    d.shopCity(),
                    d.shopDistrict(),
                    d.shopState(),
                    d.shopPincode()));

            outletCode = createRes.outletCode();
            pipes = createRes.pipes();

            // Idempotent recovery: if merchant mobile/PAN already exists on Emerald under this entity, locate it via /outlets/all
            if ((outletCode == null || outletCode.isBlank())
                    && createRes.message() != null
                    && createRes.message().toLowerCase(Locale.ROOT).contains("already")) {
                for (EmeraldClient.OutletResponse existing : emeraldClient.listAllOutlets()) {
                    Map<String, Object> rawData = asMap(existing.raw().get("data"));
                    if (d.mobile().equals(normalizeMobile(str(rawData.get("mobile"))))) {
                        outletCode = existing.outletCode();
                        pipes = existing.pipes();
                        break;
                    }
                }
            }
        }

        if (outletCode == null || outletCode.isBlank()) {
            String reason = createRes.message() == null || createRes.message().isBlank()
                    ? "Emerald rejected outlet creation (code " + createRes.statusCode() + ")"
                    : createRes.message();
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMERALD_ONBOARD_FAILED", reason);
        }

        UUID merchantAccountId = upsertMerchantAccount(
                userId, PROVIDER_EMERALD, emeraldProps.entityCode(), outletCode, "ACTIVE", admin.userId(), createRes.raw());

        EmeraldClient.OutletPipeSummary matchedPipe = pipes.stream()
                .filter(p -> pipe.equalsIgnoreCase(p.pipeCode()))
                .findFirst()
                .orElse(pipes.isEmpty() ? null : pipes.get(0));

        String pipeOutletCode = matchedPipe != null ? matchedPipe.pipeOutletCode() : "";
        boolean alreadyFullKyc = matchedPipe != null && "FULL_KYC".equalsIgnoreCase(matchedPipe.kycStatus());

        upsertPipeActivation(
                userId,
                channelId,
                merchantAccountId,
                pipeOutletCode,
                "ONBOARDED",
                alreadyFullKyc ? "FULL_KYC" : "PENDING_BIOMETRIC");

        log.info("EMERALD onboarded user={} outlet={} pipe={} pipeOutlet={} byAdmin={}",
                userId, outletCode, pipe, pipeOutletCode, admin.userId());

        // Step 1b: Immediately link and sync the retailer's primary active bank account on Emerald
        if (d.bankAccountId() != null) {
            try {
                syncEmeraldBankInternal(userId, d, outletCode, pipe, channelId);
            } catch (Exception ex) {
                log.warn("EMERALD initial bank sync failed for user={} outlet={}: {}", userId, outletCode, ex.getMessage());
            }
        }

        return buildAdminView(userId);
    }

    @Transactional
    public Map<String, Object> onboardToProvider(
            AuthPrincipal admin,
            UUID userId,
            String providerCodeRaw,
            String aadhaarOverride,
            String emailOverride,
            String requestedPipe) {
        String providerCode = normalizeProviderCode(providerCodeRaw);
        if (PROVIDER_EMERALD.equals(providerCode)) {
            return onboardToEmerald(admin, userId, aadhaarOverride, emailOverride, requestedPipe);
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_PROVIDER",
                "Automated API onboarding is not yet configured for provider: " + providerCode);
    }

    @Transactional
    public Map<String, Object> syncEmeraldBank(AuthPrincipal admin, UUID userId) {
        return syncEmeraldBank(admin, userId, null);
    }

    @Transactional
    public Map<String, Object> syncEmeraldBank(AuthPrincipal admin, UUID userId, String requestedPipe) {
        access.assertNetworkUser(userId);
        RetailerOnboardingProfileAssembler.RetailerProviderDossier d = assembler.assemble(userId);
        if (d.bankAccountId() == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_ACCOUNT_MISSING",
                    "Retailer does not have an active settlement bank account on file.");
        }
        String outletCode = requireEmeraldOutletCode(userId);
        String pipe = (requestedPipe == null || requestedPipe.isBlank())
                ? emeraldProps.defaultPipe()
                : requestedPipe.trim().toUpperCase(Locale.ROOT);
        UUID channelId = ensureEmeraldChannel(pipe);
        EmeraldClient.BankSyncResponse syncRes = syncEmeraldBankInternal(userId, d, outletCode, pipe, channelId);
        Map<String, Object> view = buildAdminView(userId);
        view.put("bankSyncStatus", syncRes.synced() ? "SYNCED" : "FAILED");
        view.put("bankSyncRemarks", syncRes.pipeSyncRemarks());
        view.put("lastBankSyncResult", Map.of(
                "synced", syncRes.synced(),
                "statusCode", syncRes.statusCode(),
                "pipeSyncStatus", syncRes.pipeSyncStatus(),
                "message", syncRes.message(),
                "remarks", syncRes.pipeSyncRemarks()));
        return view;
    }

    @Transactional
    public Map<String, Object> syncProviderBank(AuthPrincipal admin, UUID userId, String providerCodeRaw, String pipeCode) {
        String providerCode = normalizeProviderCode(providerCodeRaw);
        if (PROVIDER_EMERALD.equals(providerCode)) {
            return syncEmeraldBank(admin, userId, pipeCode);
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_PROVIDER",
                "Bank sync is not yet configured for provider: " + providerCode);
    }

    @Transactional
    public Map<String, Object> refreshEmeraldStatus(UUID userId) {
        access.assertNetworkUser(userId);
        syncFromEmeraldLive(userId);
        return buildAdminView(userId);
    }

    @Transactional
    public Map<String, Object> refreshProviderStatus(UUID userId, String providerCodeRaw) {
        String providerCode = normalizeProviderCode(providerCodeRaw);
        if (PROVIDER_EMERALD.equals(providerCode)) {
            return refreshEmeraldStatus(userId);
        }
        access.assertNetworkUser(userId);
        return buildAdminView(userId);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verifyRetailerBank(UUID userId, UUID bankId) {
        access.assertNetworkUser(userId);
        if (retailerBanks == null) {
            throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "BANK_SERVICE_UNAVAILABLE",
                    "Bank verification service is unavailable.");
        }
        retailerBanks.verifyForUser(userId, bankId);
        return buildAdminView(userId);
    }

    @Transactional
    public Map<String, Object> setRetailerBankActive(UUID userId, UUID bankId, boolean active) {
        access.assertNetworkUser(userId);
        if (retailerBanks == null) {
            throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "BANK_SERVICE_UNAVAILABLE",
                    "Bank verification service is unavailable.");
        }
        retailerBanks.setActiveForUser(userId, bankId, active);
        return buildAdminView(userId);
    }

    /**
     * Live status sync used by both Admin refresh and Retailer Login / Cash page checks (Step 3).
     * Never throws if upstream gateway is unreachable during passive retailer checks when {@code swallowNetworkErrors=true}.
     */
    @Transactional
    public boolean syncFromEmeraldLive(UUID userId) {
        List<Map<String, Object>> accounts = jdbc.queryForList("""
                SELECT id, outlet_code
                  FROM provider_merchant_accounts
                 WHERE user_id = ? AND provider_code = ?
                """, userId, PROVIDER_EMERALD);
        if (accounts.isEmpty()) {
            return false;
        }
        UUID merchantAccountId = (UUID) accounts.get(0).get("id");
        String outletCode = str(accounts.get(0).get("outlet_code"));
        if (outletCode.isBlank()) {
            return false;
        }

        EmeraldClient.OutletResponse details;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP3_STATUS_SYNC")) {
            details = emeraldClient.getOutletDetails(outletCode);
        }
        if (!details.success()) {
            return false;
        }

        jdbc.update("""
                UPDATE provider_merchant_accounts
                   SET raw_outlet = ?::jsonb,
                       last_synced_at = now(),
                       updated_at = now()
                 WHERE id = ?
                """, toJson(details.raw()), merchantAccountId);

        for (EmeraldClient.OutletPipeSummary p : details.pipes()) {
            UUID channelId = ensureEmeraldChannel(p.pipeCode());
            boolean upstreamFullKyc = "FULL_KYC".equalsIgnoreCase(p.kycStatus());
            jdbc.update("""
                    UPDATE provider_pipe_activations
                       SET pipe_outlet_code = CASE WHEN ? <> '' THEN ? ELSE pipe_outlet_code END,
                           onboarding_status = CASE WHEN ? THEN 'ONBOARDED' ELSE onboarding_status END,
                           kyc_status = CASE
                               WHEN kyc_status = 'FULL_KYC' THEN 'FULL_KYC'
                               WHEN ? THEN 'FULL_KYC'
                               ELSE kyc_status
                           END,
                           biometric_verified_at = CASE
                               WHEN ? AND biometric_verified_at IS NULL THEN now()
                               ELSE biometric_verified_at
                           END,
                           last_synced_at = now(),
                           updated_at = now()
                     WHERE user_id = ? AND channel_id = ?
                    """,
                    p.pipeOutletCode(), p.pipeOutletCode(),
                    p.active(),
                    upstreamFullKyc,
                    upstreamFullKyc,
                    userId, channelId);
        }

        // Only trust bankAccounts[].pipeSyncStatus when Emerald explicitly returns it (see Emerald Issue #2)
        for (EmeraldClient.OutletBankSummary b : details.bankAccounts()) {
            if ("SYNCED".equalsIgnoreCase(b.pipeSyncStatus())) {
                jdbc.update("""
                        UPDATE provider_pipe_activations
                           SET bank_sync_status = 'SYNCED',
                               bank_sync_remarks = CASE WHEN ? <> '' THEN ? ELSE bank_sync_remarks END,
                               updated_at = now()
                         WHERE merchant_account_id = ?
                        """, b.pipeSyncRemarks(), b.pipeSyncRemarks(), merchantAccountId);
                break;
            } else if ("FAILED".equalsIgnoreCase(b.pipeSyncStatus())) {
                jdbc.update("""
                        UPDATE provider_pipe_activations
                           SET bank_sync_status = CASE WHEN bank_sync_status = 'SYNCED' THEN 'SYNCED' ELSE 'FAILED' END,
                               bank_sync_remarks = CASE WHEN ? <> '' THEN ? ELSE bank_sync_remarks END,
                               updated_at = now()
                         WHERE merchant_account_id = ?
                        """, b.pipeSyncRemarks(), b.pipeSyncRemarks(), merchantAccountId);
            }
        }
        return true;
    }

    @Transactional
    public Map<String, Object> updateRetailerChannel(
            AuthPrincipal admin,
            UUID userId,
            UUID channelId,
            Boolean enabledForRetailer,
            Boolean reopenBiometric) {
        access.assertNetworkUser(userId);
        int count = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM provider_pipe_activations WHERE user_id = ? AND channel_id = ?",
                Integer.class, userId, channelId);
        if (count == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "ACTIVATION_NOT_FOUND",
                    "Retailer is not onboarded on this provider channel yet.");
        }
        if (enabledForRetailer != null) {
            jdbc.update("""
                    UPDATE provider_pipe_activations
                       SET enabled_for_retailer = ?, updated_at = now()
                     WHERE user_id = ? AND channel_id = ?
                    """, enabledForRetailer, userId, channelId);
        }
        if (Boolean.TRUE.equals(reopenBiometric)) {
            jdbc.update("""
                    UPDATE provider_pipe_activations
                       SET kyc_status = 'PENDING_BIOMETRIC', updated_at = now()
                     WHERE user_id = ? AND channel_id = ?
                    """, userId, channelId);
        }
        log.info("ADMIN updated retailer channel user={} channel={} enabled={} reopenBio={} by={}",
                userId, channelId, enabledForRetailer, reopenBiometric, admin.userId());
        return buildAdminView(userId);
    }

    public String exportVendor2Csv(UUID userId) {
        access.assertNetworkUser(userId);
        RetailerOnboardingProfileAssembler.RetailerProviderDossier d = assembler.assemble(userId);
        return assembler.toVendor2Csv(d, "GFINPAY");
    }

    private EmeraldClient.BankSyncResponse syncEmeraldBankInternal(
            UUID userId,
            RetailerOnboardingProfileAssembler.RetailerProviderDossier d,
            String outletCode,
            String pipe,
            UUID channelId) {
        String lat = d.latitude().isBlank() ? "19.0760" : d.latitude();
        String lng = d.longitude().isBlank() ? "72.8777" : d.longitude();
        String holderName = d.accountHolderName().isBlank() ? d.fullName() : d.accountHolderName();

        EmeraldClient.BankSyncResponse res;
        try (var ignored = EmeraldTraceContext.open(
                userId,
                "STEP1_BANK_SYNC",
                d.bankAccountId() != null ? d.bankAccountId().toString() : null)) {
            res = emeraldClient.addBankAccount(
                    outletCode,
                    new EmeraldClient.AddBankAccountRequest(
                            holderName,
                            d.accountNumber(),
                            d.ifsc(),
                            lat,
                            lng,
                            pipe));
        }

        String nextSyncStatus = res.synced() ? "SYNCED" : "FAILED";
        jdbc.update("""
                UPDATE provider_pipe_activations
                   SET bank_sync_status = ?,
                       synced_bank_account_id = ?,
                       bank_sync_remarks = ?,
                       last_synced_at = now(),
                       updated_at = now()
                 WHERE user_id = ? AND channel_id = ?
                """, nextSyncStatus, d.bankAccountId(), res.pipeSyncRemarks(), userId, channelId);
        return res;
    }

    private Map<String, Object> buildAdminView(UUID userId) {
        RetailerOnboardingProfileAssembler.RetailerProviderDossier d = assembler.assemble(userId);

        Map<String, Object> dossierSummary = new LinkedHashMap<>();
        dossierSummary.put("bcAgentId", d.bcAgentId());
        dossierSummary.put("fullName", d.fullName());
        dossierSummary.put("companyName", d.companyName());
        dossierSummary.put("shopName", d.shopName());
        dossierSummary.put("mobile", d.mobile());
        dossierSummary.put("email", d.email());
        dossierSummary.put("gender", d.gender());
        dossierSummary.put("dateOfBirth", d.dateOfBirth());
        dossierSummary.put("hasPan", !d.pan().isBlank());
        dossierSummary.put("panMasked", d.panLast4().isBlank() ? "" : "XXXXXX" + d.panLast4());
        dossierSummary.put("hasFullAadhaar", d.aadhaar().matches("[0-9]{12}"));
        dossierSummary.put("aadhaarMasked", d.aadhaarLast4().isBlank() ? "" : "XXXX-XXXX-" + d.aadhaarLast4());
        dossierSummary.put("gstin", d.gstin());
        dossierSummary.put("shopAddress", d.shopAddress());
        dossierSummary.put("shopArea", d.shopArea());
        dossierSummary.put("shopCity", d.shopCity());
        dossierSummary.put("shopDistrict", d.shopDistrict());
        dossierSummary.put("shopState", d.shopState());
        dossierSummary.put("shopPincode", d.shopPincode());
        dossierSummary.put("latitude", d.latitude());
        dossierSummary.put("longitude", d.longitude());
        dossierSummary.put("bankAccountId", d.bankAccountId());
        dossierSummary.put("bankName", d.bankName());
        dossierSummary.put("ifsc", d.ifsc());
        dossierSummary.put("accountType", d.accountType());
        dossierSummary.put("accountNumberMasked", maskAccount(d.accountNumber()));
        dossierSummary.put("bankVerificationStatus", d.bankVerificationStatus());
        dossierSummary.put("personalVerified", d.personalVerified());
        dossierSummary.put("businessVerified", d.businessVerified());
        dossierSummary.put("agreementSigned", d.agreementSigned());
        dossierSummary.put("readyForEmerald", d.readyForEmerald());
        dossierSummary.put("missingFields", d.missingFields());

        List<Map<String, Object>> merchantAccounts = jdbc.queryForList("""
                SELECT id, provider_code, entity_code, outlet_code, status, onboarded_at, last_synced_at
                  FROM provider_merchant_accounts
                 WHERE user_id = ?
                 ORDER BY created_at ASC
                """, userId);

        List<Map<String, Object>> activations = jdbc.queryForList("""
                SELECT a.id, a.channel_id, c.channel_key, c.provider_code, c.pipe_code, c.display_label,
                       c.active AS platform_channel_active,
                       m.outlet_code, a.pipe_outlet_code, a.onboarding_status, a.kyc_status,
                       a.biometric_verified_at, a.bank_sync_status, a.synced_bank_account_id,
                       a.bank_sync_remarks, a.enabled_for_retailer, a.last_error, a.last_synced_at, a.updated_at
                  FROM provider_pipe_activations a
                  JOIN service_provider_channels c ON c.id = a.channel_id
                  JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                 WHERE a.user_id = ?
                 ORDER BY c.priority_order ASC
                """, userId);

        List<Map<String, Object>> providerChannels = jdbc.queryForList("""
                SELECT c.id AS channel_id,
                       c.channel_key,
                       c.provider_code,
                       c.pipe_code,
                       c.display_label,
                       c.active AS platform_channel_active,
                       c.supports_upi_cashout,
                       c.supports_aeps,
                       c.supports_payout,
                       c.biometric_required,
                       c.priority_order,
                       (a.id IS NOT NULL) AS onboarded,
                       a.id AS activation_id,
                       m.outlet_code,
                       m.status AS merchant_status,
                       a.pipe_outlet_code,
                       COALESCE(a.onboarding_status, 'NOT_ONBOARDED') AS onboarding_status,
                       COALESCE(a.kyc_status, 'NOT_STARTED') AS kyc_status,
                       a.biometric_verified_at,
                       COALESCE(a.bank_sync_status, 'NOT_SYNCED') AS bank_sync_status,
                       a.synced_bank_account_id,
                       a.bank_sync_remarks,
                       COALESCE(a.enabled_for_retailer, FALSE) AS enabled_for_retailer,
                       a.last_error,
                       a.last_synced_at
                  FROM service_provider_channels c
                  LEFT JOIN provider_pipe_activations a ON a.channel_id = c.id AND a.user_id = ?
                  LEFT JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                 ORDER BY c.priority_order ASC, c.created_at ASC
                """, userId);

        List<Map<String, Object>> biometricSessions = jdbc.queryForList("""
                SELECT s.id, c.provider_code, c.pipe_code, s.external_ref, s.status,
                       s.rd_rds_id, s.rd_mi, s.rd_srno, s.q_score, s.nm_points,
                       s.provider_status_code, s.provider_message, s.created_at, s.submitted_at
                  FROM biometric_kyc_sessions s
                  JOIN service_provider_channels c ON c.id = s.channel_id
                 WHERE s.user_id = ?
                 ORDER BY s.created_at DESC
                 LIMIT 10
                """, userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emeraldConfigured", emeraldProps.isConfigured());
        out.put("defaultPipe", emeraldProps.defaultPipe());
        out.put("entityCode", emeraldProps.entityCode());
        out.put("bankVerificationStatus", d.bankVerificationStatus());
        out.put("banks", retailerBanks != null ? retailerBanks.list(userId) : List.of());
        out.put("dossier", dossierSummary);
        out.put("merchantAccounts", merchantAccounts);
        out.put("providerChannels", providerChannels);
        out.put("activations", activations);
        out.put("biometricSessions", biometricSessions);
        return out;
    }

    private static String normalizeProviderCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return PROVIDER_EMERALD;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private UUID ensureEmeraldChannel(String pipeCode) {
        String pipe = (pipeCode == null || pipeCode.isBlank()) ? "INSTANTPAY" : pipeCode.trim().toUpperCase(Locale.ROOT);
        List<UUID> existing = jdbc.queryForList("""
                SELECT id FROM service_provider_channels WHERE provider_code = ? AND pipe_code = ?
                """, UUID.class, PROVIDER_EMERALD, pipe);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String nextKey = "ch_cash_" + (jdbc.queryForObject("SELECT COUNT(*)::int + 1 FROM service_provider_channels", Integer.class));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO service_provider_channels
                    (id, channel_key, provider_code, pipe_code, display_label, priority_order)
                VALUES (?, ?, ?, ?, ?, 10)
                ON CONFLICT (provider_code, pipe_code) DO NOTHING
                """, id, nextKey, PROVIDER_EMERALD, pipe, "Terminal Channel (" + pipe + ")");
        return jdbc.queryForObject(
                "SELECT id FROM service_provider_channels WHERE provider_code = ? AND pipe_code = ?",
                UUID.class, PROVIDER_EMERALD, pipe);
    }

    private UUID upsertMerchantAccount(
            UUID userId,
            String providerCode,
            String entityCode,
            String outletCode,
            String status,
            UUID adminId,
            Map<String, Object> raw) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_merchant_accounts
                    (id, user_id, provider_code, entity_code, outlet_code, status, onboarded_by, onboarded_at, last_synced_at, raw_outlet)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), now(), ?::jsonb)
                ON CONFLICT (user_id, provider_code) DO UPDATE
                   SET entity_code = EXCLUDED.entity_code,
                       outlet_code = EXCLUDED.outlet_code,
                       status = EXCLUDED.status,
                       last_synced_at = now(),
                       raw_outlet = EXCLUDED.raw_outlet,
                       updated_at = now()
                """, id, userId, providerCode, entityCode, outletCode, status, adminId, toJson(raw));
        return jdbc.queryForObject(
                "SELECT id FROM provider_merchant_accounts WHERE user_id = ? AND provider_code = ?",
                UUID.class, userId, providerCode);
    }

    private void upsertPipeActivation(
            UUID userId,
            UUID channelId,
            UUID merchantAccountId,
            String pipeOutletCode,
            String onboardingStatus,
            String initialKycStatus) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_pipe_activations
                    (id, user_id, channel_id, merchant_account_id, pipe_outlet_code, onboarding_status, kyc_status, last_synced_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (user_id, channel_id) DO UPDATE
                   SET merchant_account_id = EXCLUDED.merchant_account_id,
                       pipe_outlet_code = CASE WHEN EXCLUDED.pipe_outlet_code <> '' THEN EXCLUDED.pipe_outlet_code ELSE provider_pipe_activations.pipe_outlet_code END,
                       onboarding_status = EXCLUDED.onboarding_status,
                       kyc_status = CASE
                           WHEN provider_pipe_activations.kyc_status = 'FULL_KYC' THEN 'FULL_KYC'
                           ELSE EXCLUDED.kyc_status
                       END,
                       last_synced_at = now(),
                       updated_at = now()
                """, id, userId, channelId, merchantAccountId, pipeOutletCode == null ? "" : pipeOutletCode, onboardingStatus, initialKycStatus);
    }

    private String requireEmeraldOutletCode(UUID userId) {
        List<String> codes = jdbc.queryForList("""
                SELECT outlet_code FROM provider_merchant_accounts
                 WHERE user_id = ? AND provider_code = ?
                """, String.class, userId, PROVIDER_EMERALD);
        if (codes.isEmpty() || codes.get(0) == null || codes.get(0).isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_ONBOARDED_ON_EMERALD",
                    "Onboard this retailer to Emerald first before syncing bank details.");
        }
        return codes.get(0).trim();
    }

    private void ensureProfileRow(UUID userId) {
        jdbc.update("""
                INSERT INTO agent_kyc_profiles (user_id, kyc_status)
                VALUES (?, 'PENDING')
                ON CONFLICT (user_id) DO NOTHING
                """, userId);
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj == null ? Map.of() : obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static String normalizeMobile(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }

    private static String maskAccount(String acc) {
        if (acc == null || acc.isBlank()) {
            return "";
        }
        if (acc.length() <= 4) {
            return acc;
        }
        return "••••" + acc.substring(acc.length() - 4);
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}

