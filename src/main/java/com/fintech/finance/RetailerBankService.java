package com.fintech.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.kyc.surepass.SurepassClient;
import com.fintech.onboarding.JourneyTracker;
import com.fintech.onboarding.NameMatcher;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bank accounts a retailer or distributor registers so earnings can be paid out and wallet activated. */
@Service
public class RetailerBankService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String SELECT = """
            SELECT id, user_id, account_name, account_number, ifsc, bank_name,
                   COALESCE(account_type, 'SAVINGS') AS account_type,
                   branch_name, imps_ref_no, verification_remarks,
                   COALESCE(ifsc_details, '{}'::jsonb)::text AS ifsc_details_json,
                   active, verification_status, verified_name, name_match_score, provider_ref, verified_at,
                   created_at, updated_at
              FROM retailer_bank_accounts
            """;

    private final JdbcTemplate jdbc;
    private final SurepassClient surepass;
    private final JourneyTracker journeyTracker;
    private final ObjectMapper mapper;

    public RetailerBankService(
            JdbcTemplate jdbc,
            SurepassClient surepass,
            JourneyTracker journeyTracker,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.surepass = surepass;
        this.journeyTracker = journeyTracker;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> list(UUID userId) {
        return jdbc.queryForList(SELECT + " WHERE user_id = ? ORDER BY active DESC, bank_name, account_name", userId)
                .stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> create(AuthPrincipal me, String accountName, String accountNumber, String ifsc, String bankName) {
        return create(me, accountName, accountNumber, ifsc, bankName, "SAVINGS");
    }

    @Transactional
    public Map<String, Object> create(AuthPrincipal me, String accountName, String accountNumber, String ifsc,
                                      String bankName, String accountTypeRaw) {
        UUID id = UUID.randomUUID();
        String accountType = normalizeAccountType(accountTypeRaw);
        try {
            jdbc.update("""
                    INSERT INTO retailer_bank_accounts
                        (id, user_id, account_name, account_number, ifsc, bank_name, account_type, active, verification_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, 'NOT_VERIFIED')
                    """, id, me.userId(), accountName.trim(), accountNumber(accountNumber), ifsc(ifsc), bankName.trim(), accountType);
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_ALREADY_USED",
                    "This account number is already on your profile");
        }
        markProviderBankSyncStale(me.userId());
        return get(me.userId(), id);
    }

    /**
     * Validates a bank account directly via Surepass {@code /api/v1/bank-verification} using only
     * Account Number + IFSC, verifies account existence and retailer name match, and saves it as VERIFIED.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verifyAndCreate(
            AuthPrincipal me,
            String accountNumberRaw,
            String ifscRaw,
            String accountTypeRaw) {
        String accNum = accountNumber(accountNumberRaw);
        String ifscCode = ifsc(ifscRaw);
        String accountType = normalizeAccountType(accountTypeRaw);

        Integer dupCount = jdbc.queryForObject("""
                SELECT COUNT(*)::int FROM retailer_bank_accounts WHERE user_id = ? AND account_number = ?
                """, Integer.class, me.userId(), accNum);
        if (dupCount != null && dupCount > 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_ALREADY_USED",
                    "This account number is already on your profile");
        }

        VerifiedBankEvaluation eval = evaluateWithSurepass(me.userId(), null, accNum, ifscCode);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO retailer_bank_accounts
                        (id, user_id, account_name, account_number, ifsc, bank_name, account_type,
                         branch_name, imps_ref_no, verification_remarks, ifsc_details,
                         active, verification_status, verified_name, name_match_score, provider_ref, verified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, TRUE, 'VERIFIED', ?, ?, ?, now())
                    """,
                    id,
                    me.userId(),
                    eval.holderName(),
                    accNum,
                    eval.canonicalIfsc(),
                    eval.bankName(),
                    accountType,
                    blankToNull(eval.branchName()),
                    blankToNull(eval.impsRefNo()),
                    blankToNull(eval.remarks()),
                    eval.ifscDetailsJson(),
                    eval.holderName(),
                    BigDecimal.valueOf(eval.score()),
                    blankToNull(eval.providerRef()));
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_ALREADY_USED",
                    "This account number is already on your profile");
        }

        markProfileBankVerified(me.userId());
        markProviderBankSyncStale(me.userId());
        journeyTracker.recordUserMilestone(
                me.userId(), JourneyTracker.BANK_VERIFIED, "SUCCESS",
                Map.of(
                        "bankAccountId", id.toString(),
                        "verifiedName", eval.holderName(),
                        "bankName", eval.bankName(),
                        "score", eval.score(),
                        "providerRef", eval.providerRef()));
        return get(me.userId(), id);
    }

    @Transactional
    public Map<String, Object> update(AuthPrincipal me, UUID id, String accountName, String accountNumber,
                                      String ifsc, String bankName) {
        return update(me, id, accountName, accountNumber, ifsc, bankName, null);
    }

    @Transactional
    public Map<String, Object> update(AuthPrincipal me, UUID id, String accountName, String accountNumber,
                                      String ifsc, String bankName, String accountTypeRaw) {
        Map<String, Object> existing = load(me.userId(), id);
        String nextAcc = accountNumber(accountNumber);
        String nextIfsc = ifsc(ifsc);
        String nextType = (accountTypeRaw == null || accountTypeRaw.isBlank())
                ? normalizeAccountType(str(existing.get("account_type")))
                : normalizeAccountType(accountTypeRaw);
        boolean changedBankDetails = !nextAcc.equals(existing.get("account_number"))
                || !nextIfsc.equals(existing.get("ifsc"));
        try {
            if (changedBankDetails) {
                jdbc.update("""
                        UPDATE retailer_bank_accounts
                           SET account_name = ?, account_number = ?, ifsc = ?, bank_name = ?, account_type = ?,
                               branch_name = NULL, imps_ref_no = NULL, verification_remarks = NULL,
                               ifsc_details = '{}'::jsonb,
                               verification_status = 'NOT_VERIFIED', verified_name = NULL,
                               name_match_score = NULL, provider_ref = NULL, verified_at = NULL,
                               updated_at = now()
                         WHERE id = ? AND user_id = ?
                        """, accountName.trim(), nextAcc, nextIfsc, bankName.trim(), nextType, id, me.userId());
                syncProfileBankStatus(me.userId());
            } else {
                jdbc.update("""
                        UPDATE retailer_bank_accounts
                           SET account_name = ?, bank_name = ?, account_type = ?, updated_at = now()
                         WHERE id = ? AND user_id = ?
                        """, accountName.trim(), bankName.trim(), nextType, id, me.userId());
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_ALREADY_USED",
                    "This account number is already on your profile");
        }
        markProviderBankSyncStale(me.userId());
        return get(me.userId(), id);
    }

    /**
     * Updates an existing bank account using Account Number + IFSC and re-verifies via Surepass if changed or unverified.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verifyAndUpdate(
            AuthPrincipal me,
            UUID id,
            String accountNumberRaw,
            String ifscRaw,
            String accountTypeRaw) {
        Map<String, Object> existing = load(me.userId(), id);
        String nextAcc = accountNumber(accountNumberRaw);
        String nextIfsc = ifsc(ifscRaw);
        String nextType = (accountTypeRaw == null || accountTypeRaw.isBlank())
                ? normalizeAccountType(str(existing.get("account_type")))
                : normalizeAccountType(accountTypeRaw);

        boolean sameDigitsAndIfsc = nextAcc.equals(existing.get("account_number"))
                && nextIfsc.equalsIgnoreCase(str(existing.get("ifsc")));
        boolean alreadyVerified = "VERIFIED".equalsIgnoreCase(str(existing.get("verification_status")));

        if (sameDigitsAndIfsc && alreadyVerified) {
            jdbc.update("""
                    UPDATE retailer_bank_accounts
                       SET account_type = ?, updated_at = now()
                     WHERE id = ? AND user_id = ?
                    """, nextType, id, me.userId());
            markProviderBankSyncStale(me.userId());
            return get(me.userId(), id);
        }

        if (!nextAcc.equals(existing.get("account_number"))) {
            Integer dupCount = jdbc.queryForObject("""
                    SELECT COUNT(*)::int FROM retailer_bank_accounts
                     WHERE user_id = ? AND account_number = ? AND id <> ?
                    """, Integer.class, me.userId(), nextAcc, id);
            if (dupCount != null && dupCount > 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_ALREADY_USED",
                        "This account number is already on your profile");
            }
        }

        UUID failureTargetId = sameDigitsAndIfsc ? id : null;
        VerifiedBankEvaluation eval = evaluateWithSurepass(me.userId(), failureTargetId, nextAcc, nextIfsc);
        try {
            jdbc.update("""
                    UPDATE retailer_bank_accounts
                       SET account_name = ?,
                           account_number = ?,
                           ifsc = ?,
                           bank_name = ?,
                           account_type = ?,
                           branch_name = ?,
                           imps_ref_no = ?,
                           verification_remarks = ?,
                           ifsc_details = ?::jsonb,
                           verification_status = 'VERIFIED',
                           verified_name = ?,
                           name_match_score = ?,
                           provider_ref = ?,
                           verified_at = now(),
                           updated_at = now()
                     WHERE id = ? AND user_id = ?
                    """,
                    eval.holderName(),
                    nextAcc,
                    eval.canonicalIfsc(),
                    eval.bankName(),
                    nextType,
                    blankToNull(eval.branchName()),
                    blankToNull(eval.impsRefNo()),
                    blankToNull(eval.remarks()),
                    eval.ifscDetailsJson(),
                    eval.holderName(),
                    BigDecimal.valueOf(eval.score()),
                    blankToNull(eval.providerRef()),
                    id,
                    me.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_ALREADY_USED",
                    "This account number is already on your profile");
        }

        markProfileBankVerified(me.userId());
        markProviderBankSyncStale(me.userId());
        journeyTracker.recordUserMilestone(
                me.userId(), JourneyTracker.BANK_VERIFIED, "SUCCESS",
                Map.of(
                        "bankAccountId", id.toString(),
                        "verifiedName", eval.holderName(),
                        "bankName", eval.bankName(),
                        "score", eval.score(),
                        "providerRef", eval.providerRef()));
        return get(me.userId(), id);
    }

    @Transactional
    public Map<String, Object> setActive(AuthPrincipal me, UUID id, boolean active) {
        return setActiveForUser(me.userId(), id, active);
    }

    @Transactional
    public Map<String, Object> setActiveForUser(UUID userId, UUID id, boolean active) {
        load(userId, id);
        jdbc.update("""
                UPDATE retailer_bank_accounts SET active = ?, updated_at = now() WHERE id = ? AND user_id = ?
                """, active, id, userId);
        syncProfileBankStatus(userId);
        markProviderBankSyncStale(userId);
        return get(userId, id);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verify(AuthPrincipal me, UUID id) {
        return verifyForUser(me.userId(), id);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verifyForUser(UUID userId, UUID id) {
        Map<String, Object> row = load(userId, id);
        String accNum = String.valueOf(row.get("account_number"));
        String ifscCode = String.valueOf(row.get("ifsc"));

        VerifiedBankEvaluation eval = evaluateWithSurepass(userId, id, accNum, ifscCode);

        String resolvedAccountName = !eval.holderName().isBlank() ? eval.holderName() : str(row.get("account_name"));
        String resolvedBankName = !eval.bankName().isBlank() ? eval.bankName() : str(row.get("bank_name"));

        jdbc.update("""
                UPDATE retailer_bank_accounts
                   SET account_name = ?,
                       bank_name = ?,
                       ifsc = ?,
                       branch_name = ?,
                       imps_ref_no = ?,
                       verification_remarks = ?,
                       ifsc_details = ?::jsonb,
                       verification_status = 'VERIFIED',
                       verified_name = ?,
                       name_match_score = ?,
                       provider_ref = ?,
                       verified_at = now(),
                       updated_at = now()
                 WHERE id = ? AND user_id = ?
                """,
                resolvedAccountName,
                resolvedBankName,
                eval.canonicalIfsc(),
                blankToNull(eval.branchName()),
                blankToNull(eval.impsRefNo()),
                blankToNull(eval.remarks()),
                eval.ifscDetailsJson(),
                eval.holderName(),
                BigDecimal.valueOf(eval.score()),
                blankToNull(eval.providerRef()),
                id,
                userId);

        markProfileBankVerified(userId);
        markProviderBankSyncStale(userId);

        journeyTracker.recordUserMilestone(
                userId, JourneyTracker.BANK_VERIFIED, "SUCCESS",
                Map.of(
                        "bankAccountId", id.toString(),
                        "verifiedName", eval.holderName(),
                        "bankName", resolvedBankName,
                        "score", eval.score(),
                        "providerRef", eval.providerRef()));

        return get(userId, id);
    }

    private record VerifiedBankEvaluation(
            String holderName,
            String bankName,
            String branchName,
            String canonicalIfsc,
            String impsRefNo,
            String remarks,
            String providerRef,
            String ifscDetailsJson,
            int score
    ) {}

    private VerifiedBankEvaluation evaluateWithSurepass(
            UUID userId,
            UUID existingBankId,
            String accNum,
            String ifscCode) {
        Map<String, Object> holderContext = jdbc.queryForMap("""
                SELECT u.full_name AS user_name,
                       k.identity_details->>'fullName' AS identity_name,
                       k.personal_details->>'fullName' AS personal_name,
                       k.personal_details->>'panName' AS pan_name,
                       k.shop_name,
                       k.business_status,
                       COALESCE(k.business_verified, FALSE) AS business_verified,
                       k.business_details->>'legalName' AS legal_name,
                       k.business_details->>'tradeName' AS trade_name,
                       k.business_details->>'companyName' AS company_name
                  FROM users u
                  LEFT JOIN agent_kyc_profiles k ON k.user_id = u.id
                 WHERE u.id = ?
                """, userId);

        String userName = str(holderContext.get("user_name"));
        String identityName = str(holderContext.get("identity_name"));
        String personalName = str(holderContext.get("personal_name"));
        String panName = str(holderContext.get("pan_name"));
        String defaultHint = firstNonBlank(personalName, identityName, panName, userName);

        List<String> candidateNames = new ArrayList<>();
        addIfNonBlank(candidateNames, personalName);
        addIfNonBlank(candidateNames, identityName);
        addIfNonBlank(candidateNames, panName);
        addIfNonBlank(candidateNames, userName);

        boolean bizVerified = Boolean.TRUE.equals(holderContext.get("business_verified"))
                || "BUSINESS_VERIFIED".equalsIgnoreCase(str(holderContext.get("business_status")));
        if (bizVerified) {
            addIfNonBlank(candidateNames, str(holderContext.get("legal_name")));
            addIfNonBlank(candidateNames, str(holderContext.get("trade_name")));
            addIfNonBlank(candidateNames, str(holderContext.get("company_name")));
            addIfNonBlank(candidateNames, str(holderContext.get("shop_name")));
        }

        SurepassClient.BankRecord rec = surepass.bankVerification(accNum, ifscCode, defaultHint);
        String verifiedHolder = str(rec.fullName());
        int score = 0;
        for (String candidate : candidateNames) {
            score = Math.max(score, NameMatcher.score(verifiedHolder, candidate));
        }

        String ifscDetailsJson = serializeIfscDetailsWithoutContact(rec.ifscDetails());
        String branchName = rec.ifscDetails() != null ? str(rec.ifscDetails().branch()) : "";
        String resolvedBankName = firstNonBlank(
                rec.bankName(),
                rec.ifscDetails() != null ? rec.ifscDetails().bankName() : "",
                rec.ifscDetails() != null ? rec.ifscDetails().bank() : "",
                ifscCode.substring(0, 4) + " Bank");

        boolean railsUnsupported = rec.ifscDetails() != null
                && !rec.ifscDetails().imps()
                && !rec.ifscDetails().neft()
                && !rec.ifscDetails().rtgs();

        if (!rec.accountExists() || verifiedHolder.isBlank() || score < NameMatcher.DEFAULT_THRESHOLD || railsUnsupported) {
            if (existingBankId != null) {
                jdbc.update("""
                        UPDATE retailer_bank_accounts
                           SET verification_status = 'FAILED',
                               verified_name = ?,
                               name_match_score = ?,
                               provider_ref = ?,
                               imps_ref_no = ?,
                               verification_remarks = ?,
                               branch_name = ?,
                               ifsc_details = ?::jsonb,
                               updated_at = now()
                         WHERE id = ? AND user_id = ?
                        """,
                        blankToNull(verifiedHolder),
                        BigDecimal.valueOf(score),
                        blankToNull(rec.providerRef()),
                        blankToNull(rec.impsRefNo()),
                        blankToNull(rec.remarks()),
                        blankToNull(branchName),
                        ifscDetailsJson,
                        existingBankId,
                        userId);
                syncProfileBankStatus(userId);
            }

            Map<String, Object> failMeta = new LinkedHashMap<>();
            if (existingBankId != null) {
                failMeta.put("bankAccountId", existingBankId.toString());
            }
            failMeta.put("verifiedName", verifiedHolder);
            failMeta.put("score", score);
            failMeta.put("accountExists", rec.accountExists());
            journeyTracker.recordUserMilestone(userId, JourneyTracker.BANK_VERIFIED, "FAILURE", failMeta);

            if (!rec.accountExists()) {
                String detail = firstNonBlank(
                        rec.remarks(),
                        !str(rec.status()).equalsIgnoreCase("failed") && !str(rec.status()).equalsIgnoreCase("success")
                                ? str(rec.status()).replace('_', ' ')
                                : "");
                String bankContext = !resolvedBankName.isBlank()
                        ? " with " + resolvedBankName + (!branchName.isBlank() ? " (" + branchName + ")" : "")
                        : " with the bank";
                String remarkSuffix = !detail.isBlank() ? ": " + detail : "";
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_VERIFICATION_FAILED",
                        "Bank account could not be verified" + bankContext + remarkSuffix
                                + ". Please check the account number and IFSC code.");
            }
            if (railsUnsupported) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_RAIL_UNSUPPORTED",
                        "This bank branch does not support electronic IMPS/NEFT/RTGS settlements.");
            }
            if (verifiedHolder.isBlank()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_VERIFICATION_FAILED",
                        "Bank did not return an account holder name for verification.");
            }
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_NAME_MISMATCH",
                    "Bank account holder name (" + verifiedHolder + ") does not match your verified name ("
                            + defaultHint + "). Score: " + score);
        }

        // Preserve the user-entered IFSC code (validated 11-char format) because CBSpenny-drop responses
        // occasionally return a generic head-office IFSC inside ifsc_details.ifsc (e.g. CNRB0000000).
        return new VerifiedBankEvaluation(
                verifiedHolder,
                resolvedBankName,
                branchName,
                ifscCode,
                str(rec.impsRefNo()),
                str(rec.remarks()),
                str(rec.providerRef()),
                ifscDetailsJson,
                score);
    }

    /**
     * Serializes IFSC branch metadata to JSONB while strictly omitting any branch contact phone number.
     */
    private String serializeIfscDetailsWithoutContact(SurepassClient.IfscDetails d) {
        if (d == null) {
            return "{}";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ifsc", str(d.ifsc()));
        m.put("micr", str(d.micr()));
        m.put("iso3166", str(d.iso3166()));
        m.put("swift", d.swift());
        m.put("bank", str(d.bank()));
        m.put("bankCode", str(d.bankCode()));
        m.put("bankName", str(d.bankName()));
        m.put("branch", str(d.branch()));
        m.put("centre", str(d.centre()));
        m.put("district", str(d.district()));
        m.put("state", str(d.state()));
        m.put("city", str(d.city()));
        m.put("address", str(d.address()));
        m.put("imps", d.imps());
        m.put("rtgs", d.rtgs());
        m.put("neft", d.neft());
        m.put("upi", d.upi());
        m.put("micrCheck", d.micrCheck());
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void markProfileBankVerified(UUID userId) {
        jdbc.update("""
                INSERT INTO agent_kyc_profiles (user_id, kyc_status, bank_verification_status)
                VALUES (?, 'PENDING', 'VERIFIED')
                ON CONFLICT (user_id) DO UPDATE
                   SET bank_verification_status = 'VERIFIED', updated_at = now()
                """, userId);
    }

    private void syncProfileBankStatus(UUID userId) {
        Integer verifiedCount = jdbc.queryForObject("""
                SELECT COUNT(*)::int
                  FROM retailer_bank_accounts
                 WHERE user_id = ? AND active IS TRUE AND verification_status = 'VERIFIED'
                """, Integer.class, userId);
        if (verifiedCount != null && verifiedCount > 0) {
            jdbc.update("""
                    UPDATE agent_kyc_profiles
                       SET bank_verification_status = 'VERIFIED', updated_at = now()
                     WHERE user_id = ?
                    """, userId);
            return;
        }
        Integer failedCount = jdbc.queryForObject("""
                SELECT COUNT(*)::int
                  FROM retailer_bank_accounts
                 WHERE user_id = ? AND active IS TRUE AND verification_status = 'FAILED'
                """, Integer.class, userId);
        String nextStatus = (failedCount != null && failedCount > 0) ? "FAILED" : "NOT_VERIFIED";
        jdbc.update("""
                UPDATE agent_kyc_profiles
                   SET bank_verification_status = ?, updated_at = now()
                 WHERE user_id = ?
                """, nextStatus, userId);
    }

    private Map<String, Object> get(UUID userId, UUID id) {
        return view(load(userId, id));
    }

    private Map<String, Object> load(UUID userId, UUID id) {
        try {
            return jdbc.queryForMap(SELECT + " WHERE id = ? AND user_id = ?", id, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BANK_NOT_FOUND", "Bank account does not exist");
        }
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("account_name", row.get("account_name"));
        out.put("account_number", row.get("account_number"));
        out.put("ifsc", row.get("ifsc"));
        out.put("bank_name", row.get("bank_name"));
        out.put("account_type", row.getOrDefault("account_type", "SAVINGS"));
        out.put("branch_name", row.get("branch_name"));
        out.put("imps_ref_no", row.get("imps_ref_no"));
        out.put("verification_remarks", row.get("verification_remarks"));
        out.put("ifsc_details", parseJsonMap(row.get("ifsc_details_json")));
        out.put("active", row.get("active"));
        out.put("verification_status", row.get("verification_status"));
        out.put("verified_name", row.get("verified_name"));
        out.put("name_match_score", row.get("name_match_score"));
        out.put("provider_ref", row.get("provider_ref"));
        out.put("verified_at", row.get("verified_at"));
        out.put("created_at", row.get("created_at"));
        out.put("updated_at", row.get("updated_at"));
        return out;
    }

    private Map<String, Object> parseJsonMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        String s = String.valueOf(raw).trim();
        if (s.isBlank() || "{}".equals(s)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(s, MAP_TYPE);
            parsed.remove("contact");
            return parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String accountNumber(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() < 9 || digits.length() > 18) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ACCOUNT",
                    "Account number should be 9 to 18 digits");
        }
        return digits;
    }

    private static String ifsc(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z]{4}0[A-Z0-9]{6}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IFSC",
                    "IFSC should look like BARB0DAMOHA or HDFC0001234");
        }
        return value;
    }

    private void markProviderBankSyncStale(UUID userId) {
        jdbc.update("""
                UPDATE provider_pipe_activations
                   SET bank_sync_status = 'NOT_SYNCED',
                       updated_at = now()
                 WHERE user_id = ?
                """, userId);
    }

    private static String normalizeAccountType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "SAVINGS";
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        if ("CURRENT".equals(norm) || "CA".equals(norm)) {
            return "CURRENT";
        }
        if ("SAVINGS".equals(norm) || "SAVING".equals(norm) || "SB".equals(norm)) {
            return "SAVINGS";
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ACCOUNT_TYPE",
                "Account type must be SAVINGS or CURRENT");
    }

    private static void addIfNonBlank(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value.trim());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static String blankToNull(String val) {
        return (val == null || val.isBlank()) ? null : val.trim();
    }

    private static String str(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}
