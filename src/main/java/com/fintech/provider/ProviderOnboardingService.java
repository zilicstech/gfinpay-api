package com.fintech.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.iserveu.IserveuOnboardingLine;
import com.fintech.emerald.EmeraldClient;
import com.fintech.emerald.EmeraldProperties;
import com.fintech.emerald.EmeraldTraceContext;
import com.fintech.finance.RetailerBankService;
import com.fintech.identity.AdminAccess;
import com.fintech.instantpay.InstantPayClient;
import com.fintech.instantpay.InstantPayProperties;
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
    public static final String PROVIDER_ISERVEU = "ISERVEU";
    public static final String PROVIDER_INSTANTPAY = "INSTANTPAY";

    public record ProviderPriority(String providerCode, Integer priority) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AdminAccess access;
    private final FieldCipher fieldCipher;
    private final EmeraldClient emeraldClient;
    private final EmeraldProperties emeraldProps;
    private final RetailerOnboardingProfileAssembler assembler;
    private final RetailerBankService retailerBanks;
    private final InstantPayClient instantPayClient;
    private final InstantPayProperties instantPayProps;

    @Autowired
    public ProviderOnboardingService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AdminAccess access,
            FieldCipher fieldCipher,
            EmeraldClient emeraldClient,
            EmeraldProperties emeraldProps,
            RetailerOnboardingProfileAssembler assembler,
            RetailerBankService retailerBanks,
            InstantPayClient instantPayClient,
            InstantPayProperties instantPayProps) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.access = access;
        this.fieldCipher = fieldCipher;
        this.emeraldClient = emeraldClient;
        this.emeraldProps = emeraldProps;
        this.assembler = assembler;
        this.retailerBanks = retailerBanks;
        this.instantPayClient = instantPayClient;
        this.instantPayProps = instantPayProps;
    }

    public ProviderOnboardingService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AdminAccess access,
            FieldCipher fieldCipher,
            EmeraldClient emeraldClient,
            EmeraldProperties emeraldProps,
            RetailerOnboardingProfileAssembler assembler,
            RetailerBankService retailerBanks) {
        this(jdbc, mapper, access, fieldCipher, emeraldClient, emeraldProps, assembler, retailerBanks, null, null);
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

            // Idempotent recovery: if merchant mobile/PAN already exists on Emerald under this entity, locate it via /outlets/all or existing DB row
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
                if (outletCode == null || outletCode.isBlank()) {
                    List<String> savedCodes = jdbc.queryForList("""
                            SELECT outlet_code FROM provider_merchant_accounts
                             WHERE user_id = ? AND provider_code = ?
                            """, String.class, userId, PROVIDER_EMERALD);
                    if (!savedCodes.isEmpty() && savedCodes.get(0) != null && !savedCodes.get(0).isBlank()) {
                        outletCode = savedCodes.get(0).trim();
                        EmeraldClient.OutletResponse det = emeraldClient.getOutletDetails(outletCode);
                        if (det.success()) {
                            pipes = det.pipes();
                        }
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
        String rawPipeStatus = matchedPipe != null ? str(matchedPipe.onboardingStatus()) : "";
        boolean pipeFailed = "FAILED".equalsIgnoreCase(rawPipeStatus) || "REJECTED".equalsIgnoreCase(rawPipeStatus);
        String resolvedOnboardingStatus = pipeFailed ? "FAILED" : "ONBOARDED";
        String lastError = pipeFailed
                ? (createRes.message() == null || createRes.message().isBlank()
                        ? "Upstream pipe onboarding failed (" + rawPipeStatus + ")"
                        : createRes.message())
                : null;
        boolean alreadyFullKyc = matchedPipe != null && "FULL_KYC".equalsIgnoreCase(matchedPipe.kycStatus());

        upsertPipeActivation(
                userId,
                channelId,
                merchantAccountId,
                pipeOutletCode,
                resolvedOnboardingStatus,
                alreadyFullKyc ? "FULL_KYC" : "PENDING_BIOMETRIC",
                lastError);

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
        if (PROVIDER_INSTANTPAY.equals(providerCode)) {
            return onboardToInstantPay(admin, userId, aadhaarOverride, emailOverride);
        }
        if (PROVIDER_ISERVEU.equals(providerCode)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ISERVEU_DETAILS_REQUIRED",
                    "Enter the iServeU account number and VPA shared for this retailer.");
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_PROVIDER",
                "Automated API onboarding is not yet configured for provider: " + providerCode);
    }

    @Transactional
    public Map<String, Object> onboardToInstantPay(
            AuthPrincipal admin,
            UUID userId,
            String aadhaarOverride,
            String emailOverride) {
        access.assertNetworkUser(userId);
        requireInstantPayConfigured();
        ensureProfileRow(userId);

        RetailerOnboardingProfileAssembler.InstantPayOnboardingProfile initialProfile =
                assembler.assembleForInstantPay(userId);

        if (aadhaarOverride != null && !aadhaarOverride.isBlank()) {
            String validAadhaar = AadhaarValidator.validateAndNormalize(aadhaarOverride, initialProfile.aadhaarLast4());
            if (initialProfile.externalSignatoryId() != null) {
                jdbc.update("""
                        UPDATE business_signatories
                           SET aadhaar_enc = ?,
                               aadhaar_last4 = CASE WHEN aadhaar_last4 IS NULL OR aadhaar_last4 = '' THEN ? ELSE aadhaar_last4 END,
                               updated_at = now()
                         WHERE id = ? AND user_id = ?
                        """, fieldCipher.encrypt(validAadhaar), validAadhaar.substring(8),
                        initialProfile.externalSignatoryId(), userId);
            } else {
                jdbc.update("""
                        UPDATE agent_kyc_profiles
                           SET aadhaar_enc = ?,
                               aadhaar_last4 = CASE WHEN aadhaar_last4 IS NULL OR aadhaar_last4 = '' THEN ? ELSE aadhaar_last4 END,
                               updated_at = now()
                         WHERE user_id = ?
                        """, fieldCipher.encrypt(validAadhaar), validAadhaar.substring(8), userId);
            }
        }
        if (emailOverride != null && !emailOverride.isBlank()) {
            String cleanEmail = emailOverride.trim().toLowerCase(Locale.ROOT);
            if (!cleanEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EMAIL",
                        "Enter a valid email address for the retailer");
            }
            jdbc.update("UPDATE users SET email = ?, updated_at = now() WHERE id = ?", cleanEmail, userId);
        }

        RetailerOnboardingProfileAssembler.InstantPayOnboardingProfile ip = assembler.assembleForInstantPay(userId);
        if (!ip.readyForInstantPay()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ONBOARDING_FIELDS_MISSING",
                    "Missing required retailer fields for InstantPay onboarding: " + String.join(", ", ip.missingFields()));
        }

        UUID channelId = ensureInstantPayChannel();
        InstantPayClient.MinKycResponse minKycRes;
        String outletId;

        try (var ignored = EmeraldTraceContext.open(userId, "STEP1_ONBOARD", ip.bcAgentId())) {
            minKycRes = instantPayClient.signupMinKyc(new InstantPayClient.MinKycRequest(
                    ip.mobile(),
                    ip.panName(),
                    ip.gender(),
                    ip.individualPan(),
                    ip.email(),
                    ip.addressFull(),
                    ip.addressCity(),
                    ip.addressPincode(),
                    ip.aadhaar12(),
                    ip.dateOfBirth(),
                    ip.latitude(),
                    ip.longitude(),
                    ip.gstin()));
            outletId = minKycRes.outletId();

            // Idempotent recovery: if already registered on InstantPay, look up by mobile or PAN via /user/outlet/list
            if (outletId == null || outletId.isBlank()) {
                InstantPayClient.OutletListResponse byMob = instantPayClient.listOutlets(1, 20, 0, ip.mobile(), "");
                if (!byMob.records().isEmpty()) {
                    outletId = byMob.records().get(0).outletId();
                }
            }
            if (outletId == null || outletId.isBlank()) {
                InstantPayClient.OutletListResponse byPan = instantPayClient.listOutlets(1, 20, 0, "", ip.individualPan());
                if (!byPan.records().isEmpty()) {
                    outletId = byPan.records().get(0).outletId();
                }
            }
            if (outletId == null || outletId.isBlank()) {
                List<String> saved = jdbc.queryForList("""
                        SELECT outlet_code FROM provider_merchant_accounts
                         WHERE user_id = ? AND provider_code = ?
                        """, String.class, userId, PROVIDER_INSTANTPAY);
                if (!saved.isEmpty() && saved.get(0) != null && !saved.get(0).isBlank()) {
                    outletId = saved.get(0).trim();
                }
            }
        }

        if (outletId == null || outletId.isBlank()) {
            String reason = (minKycRes.message() == null || minKycRes.message().isBlank())
                    ? "InstantPay rejected outlet onboarding (code " + minKycRes.statusCode() + ")"
                    : minKycRes.message();
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INSTANTPAY_ONBOARD_FAILED", reason);
        }

        UUID merchantAccountId = upsertMerchantAccount(
                userId, PROVIDER_INSTANTPAY, "DIRECT", outletId, "ACTIVE", admin.userId(), minKycRes.raw());

        String initialKyc = "PENDING_BIOMETRIC";
        if (outletId.matches("[0-9]+")) {
            try (var ignored = EmeraldTraceContext.open(userId, "STEP1_EKYC_CHECK", ip.bcAgentId())) {
                String spkey = instantPayProps != null ? instantPayProps.defaultSpkey() : "WAP";
                String gw = instantPayProps != null ? instantPayProps.defaultGw() : "JA";
                InstantPayClient.BiometricStatusResponse bio = instantPayClient.checkBiometricKycStatus(outletId, spkey, gw);
                initialKyc = resolveInstantPayKycState(bio);
            } catch (Exception ex) {
                log.warn("INSTANTPAY initial biometric status check skipped user={} outlet={}: {}",
                        userId, outletId, ex.getMessage());
            }
        }

        Integer defaultPriority = nextAvailablePriority(userId, channelId);
        jdbc.update("""
                INSERT INTO provider_pipe_activations
                    (id, user_id, channel_id, merchant_account_id, pipe_outlet_code,
                     onboarding_status, kyc_status, enabled_for_retailer, retailer_priority, last_synced_at)
                VALUES (?, ?, ?, ?, ?, 'ONBOARDED', ?, FALSE, ?, now())
                ON CONFLICT (user_id, channel_id) DO UPDATE
                   SET merchant_account_id = EXCLUDED.merchant_account_id,
                       pipe_outlet_code = EXCLUDED.pipe_outlet_code,
                       onboarding_status = 'ONBOARDED',
                       kyc_status = CASE
                           WHEN provider_pipe_activations.kyc_status = 'FULL_KYC' THEN 'FULL_KYC'
                           ELSE EXCLUDED.kyc_status
                       END,
                       retailer_priority = COALESCE(provider_pipe_activations.retailer_priority, EXCLUDED.retailer_priority),
                       last_error = NULL,
                       last_synced_at = now(),
                       updated_at = now()
                """, UUID.randomUUID(), userId, channelId, merchantAccountId, outletId, initialKyc, defaultPriority);

        log.info("INSTANTPAY onboarded user={} outlet={} panSource={} kyc={} byAdmin={}",
                userId, outletId, ip.panSource(), initialKyc, admin.userId());

        // Mandatory Step 1b: immediately add retailer's verified bank account on InstantPay so payouts match 1:1
        if (ip.bankAccountId() != null) {
            try {
                syncInstantPayBankInternal(userId, ip, outletId, channelId);
            } catch (Exception ex) {
                log.warn("INSTANTPAY initial bank sync failed for user={} outlet={}: {}",
                        userId, outletId, ex.getMessage());
            }
        }

        return buildAdminView(userId);
    }

    @Transactional
    public Map<String, Object> recordIserveuOnboarding(
            AuthPrincipal admin,
            UUID userId,
            String accountNumberRaw,
            String payeeVpaRaw,
            String retailerCodeRaw,
            String pastedLine) {
        access.assertNetworkUser(userId);
        IserveuOnboardingLine parsed = IserveuOnboardingLine.parse(pastedLine);
        String account = digits(firstFilled(accountNumberRaw, parsed == null ? "" : parsed.accountNumber()));
        String vpa = firstFilled(payeeVpaRaw, parsed == null ? "" : parsed.vpa()).trim();
        String retailerCode = firstFilled(retailerCodeRaw, parsed == null ? "" : parsed.retailerCode());
        if (!account.matches("[0-9]{6,30}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ISERVEU_ACCOUNT_REQUIRED",
                    "Enter the iServeU account number from the onboarding line.");
        }
        if (!vpa.contains("@")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ISERVEU_VPA_REQUIRED",
                    "Enter this retailer's iServeU VPA.");
        }
        if (!retailerCode.isBlank()) {
            String actual = jdbc.queryForObject("SELECT code FROM users WHERE id = ?", String.class, userId);
            if (actual == null || !actual.equalsIgnoreCase(retailerCode.trim())) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "RETAILER_CODE_MISMATCH",
                        "The retailer code in the iServeU line does not match this retailer.");
            }
        }
        List<UUID> channels = jdbc.queryForList("""
                SELECT id FROM service_provider_channels WHERE provider_code = ? ORDER BY pipe_code
                """, UUID.class, PROVIDER_ISERVEU);
        if (channels.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CHANNEL_NOT_FOUND",
                    "iServeU channel is not available.");
        }
        UUID merchantId = upsertMerchantAccount(userId, PROVIDER_ISERVEU, "ISERVEU", account, "ACTIVE", admin.userId(), Map.of(
                "accountNumber", account,
                "payeeVpa", vpa));
        for (UUID channelId : channels) {
            jdbc.update("""
                    INSERT INTO provider_pipe_activations
                        (id, user_id, channel_id, merchant_account_id, pipe_outlet_code, payee_vpa,
                         onboarding_status, kyc_status, enabled_for_retailer, last_synced_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'ONBOARDED', 'NOT_REQUIRED', FALSE, now())
                    ON CONFLICT (user_id, channel_id) DO UPDATE
                       SET merchant_account_id = EXCLUDED.merchant_account_id,
                           pipe_outlet_code = EXCLUDED.pipe_outlet_code,
                           payee_vpa = CASE WHEN EXCLUDED.payee_vpa <> '' THEN EXCLUDED.payee_vpa ELSE provider_pipe_activations.payee_vpa END,
                           onboarding_status = 'ONBOARDED',
                           kyc_status = 'NOT_REQUIRED',
                           last_error = NULL,
                           last_synced_at = now(),
                           updated_at = now()
                    """, UUID.randomUUID(), userId, channelId, merchantId, account, vpa);
        }
        log.info("ISERVEU recorded user={} channels={} byAdmin={}", userId, channels.size(), admin.userId());
        return buildAdminView(userId);
    }

    @Transactional
    public Map<String, Object> setProviderPriorities(AuthPrincipal admin, UUID userId, List<ProviderPriority> priorities) {
        access.assertNetworkUser(userId);
        if (priorities == null || priorities.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PRIORITY_REQUIRED",
                    "Choose a priority for each provider.");
        }
        java.util.Set<Integer> used = new java.util.HashSet<>();
        java.util.Set<String> providers = new java.util.HashSet<>();
        for (ProviderPriority item : priorities) {
            String code = normalizeProviderCode(item.providerCode());
            int priority = item.priority() == null ? 0 : item.priority();
            if (priority < 1 || priority > 3) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRIORITY",
                        "Priority must be 1, 2, or 3.");
            }
            if (!providers.add(code) || !used.add(priority)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PRIORITY_TAKEN",
                        "Two providers cannot use the same priority.");
            }
            int updated = jdbc.update("""
                    UPDATE provider_pipe_activations a
                       SET retailer_priority = ?, updated_at = now()
                      FROM service_provider_channels c
                     WHERE a.channel_id = c.id
                       AND a.user_id = ?
                       AND c.provider_code = ?
                    """, priority, userId, code);
            if (updated == 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_ONBOARDED",
                        "Onboard " + code + " before setting its priority.");
            }
        }
        log.info("ADMIN set provider priorities user={} count={} by={}", userId, priorities.size(), admin.userId());
        return buildAdminView(userId);
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
        if (PROVIDER_INSTANTPAY.equals(providerCode)) {
            return syncInstantPayBank(admin, userId);
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_PROVIDER",
                "Bank sync is not yet configured for provider: " + providerCode);
    }

    @Transactional
    public Map<String, Object> syncInstantPayBank(AuthPrincipal admin, UUID userId) {
        access.assertNetworkUser(userId);
        requireInstantPayConfigured();
        RetailerOnboardingProfileAssembler.InstantPayOnboardingProfile ip = assembler.assembleForInstantPay(userId);
        if (ip.bankAccountId() == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_ACCOUNT_MISSING",
                    "Retailer does not have an active settlement bank account on file.");
        }
        if (!"VERIFIED".equalsIgnoreCase(ip.bankVerificationStatus())) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_NOT_VERIFIED",
                    "Verify the retailer's settlement bank account first before syncing to InstantPay.");
        }
        String outletId = requireInstantPayOutletCode(userId);
        UUID channelId = ensureInstantPayChannel();
        InstantPayClient.BankSyncResponse syncRes = syncInstantPayBankInternal(userId, ip, outletId, channelId);
        Map<String, Object> view = buildAdminView(userId);
        view.put("bankSyncStatus", syncRes.synced() ? "SYNCED" : "FAILED");
        view.put("bankSyncRemarks", syncRes.message());
        view.put("lastBankSyncResult", Map.of(
                "synced", syncRes.synced(),
                "statusCode", syncRes.statusCode(),
                "message", syncRes.message()));
        return view;
    }

    @Transactional
    public Map<String, Object> refreshEmeraldStatus(UUID userId) {
        access.assertNetworkUser(userId);
        syncFromEmeraldLive(userId);
        return buildAdminView(userId);
    }

    @Transactional
    public Map<String, Object> refreshInstantPayStatus(UUID userId) {
        access.assertNetworkUser(userId);
        syncFromInstantPayLive(userId);
        return buildAdminView(userId);
    }

    @Transactional
    public Map<String, Object> refreshProviderStatus(UUID userId, String providerCodeRaw) {
        String providerCode = normalizeProviderCode(providerCodeRaw);
        if (PROVIDER_EMERALD.equals(providerCode)) {
            return refreshEmeraldStatus(userId);
        }
        if (PROVIDER_INSTANTPAY.equals(providerCode)) {
            return refreshInstantPayStatus(userId);
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
    public boolean syncFromInstantPayLive(UUID userId) {
        if (instantPayClient == null || instantPayProps == null || !instantPayProps.isConfigured()) {
            return false;
        }
        List<Map<String, Object>> accounts = jdbc.queryForList("""
                SELECT id, outlet_code
                  FROM provider_merchant_accounts
                 WHERE user_id = ? AND provider_code = ?
                """, userId, PROVIDER_INSTANTPAY);
        if (accounts.isEmpty()) {
            return false;
        }
        UUID merchantAccountId = (UUID) accounts.get(0).get("id");
        String outletId = str(accounts.get(0).get("outlet_code"));
        if (!outletId.matches("[0-9]+")) {
            return false;
        }

        UUID channelId = ensureInstantPayChannel();
        InstantPayClient.BiometricStatusResponse bio;
        try (var ignored = EmeraldTraceContext.open(userId, "STEP3_STATUS_SYNC")) {
            bio = instantPayClient.checkBiometricKycStatus(
                    outletId, instantPayProps.defaultSpkey(), instantPayProps.defaultGw());
        }
        if (!bio.success()) {
            return false;
        }

        String nextKyc = resolveInstantPayKycState(bio);
        boolean verified = "FULL_KYC".equals(nextKyc);

        jdbc.update("""
                UPDATE provider_merchant_accounts
                   SET last_synced_at = now(), updated_at = now()
                 WHERE id = ?
                """, merchantAccountId);

        jdbc.update("""
                UPDATE provider_pipe_activations
                   SET kyc_status = CASE
                           WHEN kyc_status = 'FULL_KYC' THEN 'FULL_KYC'
                           ELSE ?
                       END,
                       biometric_verified_at = CASE
                           WHEN ? AND biometric_verified_at IS NULL THEN now()
                           ELSE biometric_verified_at
                       END,
                       last_synced_at = now(),
                       updated_at = now()
                 WHERE user_id = ? AND channel_id = ?
                """, nextKyc, verified, userId, channelId);
        return true;
    }

    @Transactional
    public Map<String, Object> updateRetailerChannel(
            AuthPrincipal admin,
            UUID userId,
            UUID channelId,
            Boolean enabledForRetailer,
            Boolean reopenBiometric,
            Integer retailerPriority) {
        access.assertNetworkUser(userId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT enabled_for_retailer, retailer_priority
                  FROM provider_pipe_activations
                 WHERE user_id = ? AND channel_id = ?
                """, userId, channelId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "ACTIVATION_NOT_FOUND",
                    "Retailer is not onboarded on this provider channel yet.");
        }
        boolean currentEnabled = Boolean.TRUE.equals(rows.get(0).get("enabled_for_retailer"));
        Integer currentPriority = rows.get(0).get("retailer_priority") instanceof Number n ? n.intValue() : null;
        boolean nextEnabled = enabledForRetailer != null ? enabledForRetailer : currentEnabled;
        Integer nextPriority = retailerPriority != null ? retailerPriority : currentPriority;
        boolean conflict = nextEnabled && nextPriority != null && priorityTaken(userId, channelId, nextPriority);
        requireRetailerPriority(nextPriority, nextEnabled, conflict);
        jdbc.update("""
                UPDATE provider_pipe_activations
                   SET enabled_for_retailer = ?,
                       retailer_priority = ?,
                       updated_at = now()
                 WHERE user_id = ? AND channel_id = ?
                """, nextEnabled, nextPriority, userId, channelId);
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

    private InstantPayClient.BankSyncResponse syncInstantPayBankInternal(
            UUID userId,
            RetailerOnboardingProfileAssembler.InstantPayOnboardingProfile ip,
            String outletId,
            UUID channelId) {
        String lat = ip.latitude().isBlank() ? "19.0760" : ip.latitude();
        String lng = ip.longitude().isBlank() ? "72.8777" : ip.longitude();

        InstantPayClient.BankSyncResponse res;
        try (var ignored = EmeraldTraceContext.open(
                userId,
                "STEP1_BANK_SYNC",
                ip.bankAccountId() != null ? ip.bankAccountId().toString() : null)) {
            res = instantPayClient.addBankAccount(
                    outletId,
                    ip.accountNumber(),
                    ip.ifsc(),
                    lat,
                    lng);
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
                """, nextSyncStatus, ip.bankAccountId(), res.message(), userId, channelId);
        return res;
    }

    private Map<String, Object> buildAdminView(UUID userId) {
        RetailerOnboardingProfileAssembler.RetailerProviderDossier d = assembler.assemble(userId);
        RetailerOnboardingProfileAssembler.InstantPayOnboardingProfile ip = assembler.assembleForInstantPay(userId);
        boolean emeraldEnabled = emeraldProps == null || emeraldProps.enabled();

        Map<String, Object> dossierSummary = new LinkedHashMap<>();
        dossierSummary.put("bcAgentId", d.bcAgentId());
        dossierSummary.put("fullName", d.fullName());
        dossierSummary.put("companyName", d.companyName());
        dossierSummary.put("shopName", d.shopName());
        dossierSummary.put("mobile", d.mobile());
        dossierSummary.put("email", d.email());
        dossierSummary.put("gender", d.gender());
        dossierSummary.put("dateOfBirth", d.dateOfBirth());
        dossierSummary.put("hasPan", !d.pan().isBlank() || !ip.individualPan().isBlank());
        dossierSummary.put("panMasked", ip.individualPanLast4().isBlank()
                ? (d.panLast4().isBlank() ? "" : "XXXXXX" + d.panLast4())
                : "XXXXXX" + ip.individualPanLast4());
        dossierSummary.put("panSource", ip.panSource());
        dossierSummary.put("hasFullAadhaar", ip.aadhaar12().matches("[0-9]{12}"));
        dossierSummary.put("aadhaarMasked", ip.aadhaarLast4().isBlank() ? "" : "XXXX-XXXX-" + ip.aadhaarLast4());
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
        dossierSummary.put("readyForInstantPay", ip.readyForInstantPay());
        boolean ipConfigured = instantPayProps != null && instantPayProps.isConfigured();
        dossierSummary.put("missingFields", ipConfigured ? ip.missingFields() : d.missingFields());

        List<Map<String, Object>> merchantAccounts = jdbc.queryForList("""
                SELECT id, provider_code, entity_code, outlet_code, status, onboarded_at, last_synced_at
                  FROM provider_merchant_accounts
                 WHERE user_id = ?
                 ORDER BY created_at ASC
                """, userId);

        String emeraldWhereFilter = emeraldEnabled ? "" : " WHERE c.provider_code <> 'EMERALD' ";
        String emeraldAndFilter = emeraldEnabled ? "" : " AND c.provider_code <> 'EMERALD' ";

        List<Map<String, Object>> activations = jdbc.queryForList("""
                SELECT a.id, a.channel_id, c.channel_key, c.provider_code, c.pipe_code, c.display_label,
                       c.active AS platform_channel_active,
                       m.outlet_code, a.pipe_outlet_code, a.onboarding_status, a.kyc_status,
                       a.biometric_verified_at, a.bank_sync_status, a.synced_bank_account_id,
                       a.bank_sync_remarks, a.enabled_for_retailer, a.retailer_priority, a.payee_vpa,
                       a.last_error, a.last_synced_at, a.updated_at
                  FROM provider_pipe_activations a
                  JOIN service_provider_channels c ON c.id = a.channel_id
                  JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                 WHERE a.user_id = ?
                """ + emeraldAndFilter + """
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
                       a.retailer_priority,
                       a.payee_vpa,
                       a.last_error,
                       a.last_synced_at
                  FROM service_provider_channels c
                  LEFT JOIN provider_pipe_activations a ON a.channel_id = c.id AND a.user_id = ?
                  LEFT JOIN provider_merchant_accounts m ON m.id = a.merchant_account_id
                """ + emeraldWhereFilter + """
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
        out.put("emeraldConfigured", emeraldEnabled && emeraldProps != null && emeraldProps.isConfigured());
        out.put("instantPayConfigured", instantPayProps != null && instantPayProps.isConfigured());
        out.put("defaultPipe", emeraldProps != null ? emeraldProps.defaultPipe() : "INSTANTPAY");
        out.put("entityCode", emeraldProps != null ? emeraldProps.entityCode() : "DIRECT");
        out.put("bankVerificationStatus", d.bankVerificationStatus());
        out.put("banks", retailerBanks != null ? retailerBanks.list(userId) : List.of());
        out.put("dossier", dossierSummary);
        out.put("merchantAccounts", merchantAccounts);
        out.put("providerChannels", providerChannels);
        out.put("activations", activations);
        out.put("biometricSessions", biometricSessions);
        return out;
    }

    static void requireRetailerPriority(Integer priority, boolean enabling, boolean conflict) {
        if (priority != null && (priority < 1 || priority > 3)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRIORITY",
                    "Priority must be 1, 2, or 3.");
        }
        if (enabling && priority == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PRIORITY_REQUIRED",
                    "Set a priority from 1 to 3 before enabling this provider.");
        }
        if (enabling && conflict) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PRIORITY_TAKEN",
                    "Another enabled provider already uses this priority for the same service.");
        }
    }

    private boolean priorityTaken(UUID userId, UUID channelId, int priority) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::int
                  FROM provider_pipe_activations a
                  JOIN service_provider_channels c ON c.id = a.channel_id
                  JOIN service_provider_channels target ON target.id = ?
                 WHERE a.user_id = ?
                   AND a.channel_id <> ?
                   AND a.enabled_for_retailer = TRUE
                   AND a.retailer_priority = ?
                   AND (
                        (target.supports_upi_cashout AND c.supports_upi_cashout)
                     OR (target.supports_aeps AND c.supports_aeps)
                     OR (target.supports_payout AND c.supports_payout)
                   )
                """, Integer.class, channelId, userId, channelId, priority);
        return count != null && count > 0;
    }

    private static String digits(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    private static String firstFilled(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
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
            String initialKycStatus,
            String lastError) {
        UUID id = UUID.randomUUID();
        Integer defaultPriority = nextAvailablePriority(userId, channelId);
        jdbc.update("""
                INSERT INTO provider_pipe_activations
                    (id, user_id, channel_id, merchant_account_id, pipe_outlet_code, onboarding_status, kyc_status, retailer_priority, last_error, last_synced_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (user_id, channel_id) DO UPDATE
                   SET merchant_account_id = EXCLUDED.merchant_account_id,
                       pipe_outlet_code = CASE WHEN EXCLUDED.pipe_outlet_code <> '' THEN EXCLUDED.pipe_outlet_code ELSE provider_pipe_activations.pipe_outlet_code END,
                       onboarding_status = EXCLUDED.onboarding_status,
                       kyc_status = CASE
                           WHEN provider_pipe_activations.kyc_status = 'FULL_KYC' THEN 'FULL_KYC'
                           ELSE EXCLUDED.kyc_status
                       END,
                       retailer_priority = COALESCE(provider_pipe_activations.retailer_priority, EXCLUDED.retailer_priority),
                       last_error = EXCLUDED.last_error,
                       last_synced_at = now(),
                       updated_at = now()
                """, id, userId, channelId, merchantAccountId,
                pipeOutletCode == null ? "" : pipeOutletCode, onboardingStatus, initialKycStatus, defaultPriority, lastError);
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

    private String requireInstantPayOutletCode(UUID userId) {
        List<String> codes = jdbc.queryForList("""
                SELECT outlet_code FROM provider_merchant_accounts
                 WHERE user_id = ? AND provider_code = ?
                """, String.class, userId, PROVIDER_INSTANTPAY);
        if (codes.isEmpty() || codes.get(0) == null || codes.get(0).isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_ONBOARDED_ON_INSTANTPAY",
                    "Onboard this retailer to InstantPay first before syncing bank details.");
        }
        return codes.get(0).trim();
    }

    private UUID ensureInstantPayChannel() {
        List<UUID> existing = jdbc.queryForList("""
                SELECT id FROM service_provider_channels WHERE provider_code = ? AND pipe_code = 'DIRECT'
                """, UUID.class, PROVIDER_INSTANTPAY);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        UUID id = UUID.fromString("e1000000-0000-0000-0000-000000000042");
        jdbc.update("""
                INSERT INTO service_provider_channels
                    (id, channel_key, provider_code, pipe_code, display_label, priority_order, config_json)
                VALUES (?, 'ch_instantpay_direct', ?, 'DIRECT', 'InstantPay · Direct', 2,
                        '{"spkey":"WAP","gw":"JA","upiCashout":{"min":100,"max":5000,"step":100}}'::jsonb)
                ON CONFLICT (provider_code, pipe_code) DO NOTHING
                """, id, PROVIDER_INSTANTPAY);
        return jdbc.queryForObject(
                "SELECT id FROM service_provider_channels WHERE provider_code = ? AND pipe_code = 'DIRECT'",
                UUID.class, PROVIDER_INSTANTPAY);
    }

    private Integer nextAvailablePriority(UUID userId, UUID targetChannelId) {
        List<Integer> taken = jdbc.queryForList("""
                SELECT DISTINCT retailer_priority
                  FROM provider_pipe_activations
                 WHERE user_id = ?
                   AND channel_id <> ?
                   AND retailer_priority IS NOT NULL
                """, Integer.class, userId, targetChannelId);
        for (int p = 1; p <= 3; p++) {
            if (!taken.contains(p)) {
                return p;
            }
        }
        return null;
    }

    public static String resolveInstantPayKycState(InstantPayClient.BiometricStatusResponse bio) {
        if (bio == null || !bio.success()) {
            return "PENDING_BIOMETRIC";
        }
        if ("NOTYETREGISTERED".equalsIgnoreCase(bio.actCode())) {
            return "PENDING_BIOMETRIC";
        }
        if ("NO-ACTION-REQUIRED".equalsIgnoreCase(bio.action())) {
            String bankSt = bio.bankStatus() == null ? "" : bio.bankStatus().trim().toUpperCase(Locale.ROOT);
            if ("APPROVED".equals(bankSt) || bankSt.isEmpty()) {
                return "FULL_KYC";
            }
            if ("PENDING".equals(bankSt) || "APPROVAL_PENDING".equals(bankSt)) {
                return "APPROVAL_PENDING";
            }
        }
        return "PENDING_BIOMETRIC";
    }

    private void requireInstantPayConfigured() {
        if (instantPayClient == null || instantPayProps == null || !instantPayProps.isConfigured()) {
            throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "INSTANTPAY_NOT_CONFIGURED",
                    "InstantPay API credentials (APP_INSTANTPAY_CLIENT_ID / APP_INSTANTPAY_CLIENT_SECRET) are not configured.");
        }
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

