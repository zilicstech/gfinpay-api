package com.fintech.dmt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.paysprint.PaysprintDmtCasaClient;
import com.fintech.paysprint.PaysprintMessages;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Retailer DMT CASA. PaySprint is the source of truth for the remitter and the beneficiaries. */
@Service
public class DmtCasaService {

    private static final Logger log = LoggerFactory.getLogger(DmtCasaService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PaysprintDmtCasaClient paysprint;
    private final DmtCasaLedger ledger;
    private final PlatformServiceGate services;

    public DmtCasaService(JdbcTemplate jdbc, ObjectMapper mapper, PaysprintDmtCasaClient paysprint,
                          DmtCasaLedger ledger, PlatformServiceGate services) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.paysprint = paysprint;
        this.ledger = ledger;
        this.services = services;
    }

    public Map<String, Object> queryRemitter(AuthPrincipal me, String mobile) {
        requireReady(me.userId());
        String digits = DmtService.digits(mobile);
        if (digits.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE", "Enter a 10-digit mobile number");
        }
        String merchant = merchantCode(me.userId());
        log.info("DMT_CASA query remitter retailer={} merchantcode={} mobile={}", me.userId(), merchant, digits);
        JsonNode root = paysprint.queryRemitter(merchant, digits);
        int code = DmtCasaTexts.code(root);
        boolean ok = root.path("status").asBoolean(false);
        String message = PaysprintMessages.fromNode(root, "");
        log.info("DMT_CASA query remitter result merchantcode={} mobile={} response_code={} status={} message={}",
                merchant, digits, code, ok, DmtCasaTexts.clip(message));
        String providerStatus;
        boolean exists = false;
        String url = "";
        String sdk = "";
        BigDecimal limit = null;
        Object rejections = List.of();
        Object formData = null;
        JsonNode data = root.path("data");
        if (code == 1 && ok) {
            exists = true;
            providerStatus = "ACTIVE";
            limit = DmtCasaTexts.money(data.isObject() ? data : root, "limit");
        } else if (code == 0) {
            providerStatus = "NOT_FOUND";
            url = DmtCasaTexts.text(root, "url");
            sdk = DmtCasaTexts.text(root, "sdk_token");
        } else if (code == 2) {
            providerStatus = "PENDING";
        } else if (code == 14) {
            providerStatus = "REVISION";
            rejections = data.isArray() ? mapper.convertValue(data, List.class) : List.of();
            formData = root.path("form_data").isArray() ? mapper.convertValue(root.path("form_data"), List.class) : null;
        } else if (!ok) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_CASA_ERROR",
                    message.isBlank() ? "PaySprint could not look up this customer" : message);
        } else {
            providerStatus = "BLOCKED";
        }
        UUID senderId = upsertSender(me.userId(), digits, providerStatus, limit, url, sdk);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("senderId", senderId);
        out.put("mobile", digits);
        out.put("exists", exists);
        out.put("status", providerStatus);
        out.put("limit", limit);
        out.put("accountOpeningUrl", url.isBlank() ? null : url);
        out.put("sdkToken", sdk.isBlank() ? null : sdk);
        out.put("rejections", rejections);
        out.put("formData", formData);
        out.put("message", message);
        return out;
    }

    public List<Map<String, Object>> beneficiaries(AuthPrincipal me, UUID senderId) {
        requireReady(me.userId());
        Map<String, Object> sender = sender(senderId, me.userId());
        String merchant = merchantCode(me.userId());
        String mobile = String.valueOf(sender.get("mobile"));
        log.info("DMT_CASA bene list retailer={} merchantcode={} mobile={} sender={}", me.userId(), merchant, mobile, senderId);
        JsonNode root = paysprint.beneList(merchant, mobile);
        log.info("DMT_CASA bene list result merchantcode={} mobile={} response_code={} message={}",
                merchant, mobile, DmtCasaTexts.code(root), DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        paysprint.requireOk(root, "PaySprint could not list beneficiaries");
        JsonNode data = root.path("data");
        List<String> seen = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                String beneId = DmtCasaTexts.text(item, "bene_id");
                if (beneId.isBlank()) {
                    continue;
                }
                upsertBene(senderId, beneId, item, null);
                seen.add(beneId);
            }
        }
        retireMissing(senderId, seen);
        return listLocal(senderId);
    }

    public Map<String, Object> sendBeneOtp(AuthPrincipal me, UUID senderId, String name, String accountNumber,
                                           String ifsc, String bankId) {
        requireReady(me.userId());
        Map<String, Object> sender = sender(senderId, me.userId());
        String account = DmtService.digits(accountNumber);
        if (account.length() < 9 || account.length() > 18) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ACCOUNT", "Account number must be 9 to 18 digits");
        }
        String code = DmtCasaTexts.ifsc(ifsc);
        if (!code.matches("[A-Z]{4}0[A-Z0-9]{6}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IFSC",
                    "IFSC should look like HDFC0001234 (11 characters, 5th is zero)");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_NAME", "Beneficiary name is required");
        }
        String providerBankId = requireActiveBank(bankId);
        String merchant = merchantCode(me.userId());
        String mobile = String.valueOf(sender.get("mobile"));
        log.info("DMT_CASA bene otp retailer={} merchantcode={} mobile={} sender={} bankid={}",
                me.userId(), merchant, mobile, senderId, providerBankId);
        JsonNode root = paysprint.sendBeneOtp(merchant, mobile, trimmed, providerBankId, account, code);
        log.info("DMT_CASA bene otp result merchantcode={} mobile={} response_code={} stateresp_present={} message={}",
                merchant, mobile, DmtCasaTexts.code(root), !DmtCasaTexts.text(root, "stateresp").isBlank(),
                DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        paysprint.requireOk(root, "PaySprint did not send the beneficiary OTP");
        String stateResp = DmtCasaTexts.text(root, "stateresp");
        if (stateResp.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_CASA_ERROR", "PaySprint did not return a beneficiary reference");
        }
        UUID sessionId = UUID.randomUUID();
        String enc = Base64.getEncoder().encodeToString(account.getBytes());
        jdbc.update("""
                INSERT INTO dmt_bene_otp_sessions
                    (id, sender_id, agent_user_id, name, bank_id, account_number_enc, ifsc, stateresp, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now() + interval '10 minutes')
                """, sessionId, senderId, me.userId(), trimmed, providerBankId, enc, code, stateResp);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("message", PaysprintMessages.fromNode(root, "OTP sent to the customer"));
        return out;
    }

    public Map<String, Object> addBeneficiary(AuthPrincipal me, UUID senderId, UUID sessionId, String otp) {
        requireReady(me.userId());
        Map<String, Object> sender = sender(senderId, me.userId());
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT id, name, bank_id, account_number_enc, ifsc, stateresp, consumed_at,
                       expires_at < now() AS expired
                  FROM dmt_bene_otp_sessions
                 WHERE id = ? AND sender_id = ? AND agent_user_id = ?
                """, sessionId, senderId, me.userId());
        if (sessions.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "OTP_NOT_FOUND", "Send a beneficiary OTP first");
        }
        Map<String, Object> session = sessions.get(0);
        if (session.get("consumed_at") != null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_USED", "This beneficiary OTP was already used");
        }
        if (Boolean.TRUE.equals(session.get("expired"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_EXPIRED", "The beneficiary OTP expired. Send a new one");
        }
        String account = new String(Base64.getDecoder().decode(String.valueOf(session.get("account_number_enc"))));
        String merchant = merchantCode(me.userId());
        String mobile = String.valueOf(sender.get("mobile"));
        log.info("DMT_CASA add bene retailer={} merchantcode={} mobile={} sender={} bankid={} otp_present={}",
                me.userId(), merchant, mobile, senderId, session.get("bank_id"), otp != null && !otp.isBlank());
        JsonNode root = paysprint.addBene(merchant, mobile, String.valueOf(session.get("name")),
                String.valueOf(session.get("bank_id")), account, String.valueOf(session.get("ifsc")),
                otp, String.valueOf(session.get("stateresp")));
        log.info("DMT_CASA add bene result merchantcode={} mobile={} response_code={} bene_present={} message={}",
                merchant, mobile, DmtCasaTexts.code(root), !DmtCasaTexts.text(root, "bene_id").isBlank(),
                DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        paysprint.requireOk(root, "PaySprint did not add the beneficiary");
        String beneId = DmtCasaTexts.text(root, "bene_id");
        if (beneId.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_CASA_ERROR", "PaySprint did not return a beneficiary id");
        }
        JsonNode item = root.path("data").isObject() ? root.path("data") : root;
        try {
            JsonNode fetched = paysprint.fetchBene(merchant, mobile, beneId);
            JsonNode fresh = fetched.path("data");
            if (fresh.isArray() && !fresh.isEmpty()) {
                fresh = fresh.get(0);
            }
            if (fresh.isObject() && !DmtCasaTexts.text(fresh, "bene_id").isBlank()) {
                item = fresh;
            }
        } catch (ApiException e) {
            log.info("DMT_CASA fetch bene skipped bene={} message={}", beneId, e.getMessage());
        }
        jdbc.update("UPDATE dmt_bene_otp_sessions SET consumed_at = now() WHERE id = ?", sessionId);
        UUID id = upsertBene(senderId, beneId, item, account);
        log.info("DMT_CASA bene added sender={} bene={} providerBene={}", senderId, id, beneId);
        return oneBene(id);
    }

    public Map<String, Object> verifyBeneficiary(AuthPrincipal me, UUID beneficiaryId) {
        requireReady(me.userId());
        Map<String, Object> bene = bene(beneficiaryId, me.userId());
        String merchant = merchantCode(me.userId());
        String reference = ledger.nextReference();
        String account = accountOf(bene);
        log.info("DMT_CASA verify bene retailer={} merchantcode={} mobile={} bene={} referenceid={}",
                me.userId(), merchant, bene.get("mobile"), beneficiaryId, reference);
        JsonNode root = paysprint.verifyBene(merchant, reference, String.valueOf(bene.get("mobile")),
                String.valueOf(bene.get("provider_bene_id")), account, String.valueOf(bene.get("provider_bank_id")),
                String.valueOf(bene.get("ifsc")), String.valueOf(bene.get("name")));
        log.info("DMT_CASA verify bene result referenceid={} response_code={} txn_status={} message={}",
                reference, DmtCasaTexts.code(root), DmtCasaTexts.txnStatus(root),
                DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        paysprint.requireOk(root, "PaySprint could not verify this beneficiary");
        String verifiedName = DmtCasaTexts.text(root, "benename");
        jdbc.update("""
                UPDATE dmt_beneficiaries
                   SET verified_at = now(), verified_name = COALESCE(?, verified_name)
                 WHERE id = ?
                """, DmtCasaTexts.blankToNull(verifiedName), beneficiaryId);
        Map<String, Object> out = oneBene(beneficiaryId);
        out.put("message", PaysprintMessages.fromNode(root, "Beneficiary verified"));
        return out;
    }

    public Map<String, Object> deleteBeneficiary(AuthPrincipal me, UUID beneficiaryId) {
        requireReady(me.userId());
        Map<String, Object> bene = bene(beneficiaryId, me.userId());
        String merchant = merchantCode(me.userId());
        log.info("DMT_CASA delete bene retailer={} merchantcode={} mobile={} bene={}",
                me.userId(), merchant, bene.get("mobile"), beneficiaryId);
        JsonNode root = paysprint.deleteBene(merchant, String.valueOf(bene.get("mobile")),
                String.valueOf(bene.get("provider_bene_id")));
        log.info("DMT_CASA delete bene result response_code={} message={}",
                DmtCasaTexts.code(root), DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        paysprint.requireOk(root, "PaySprint did not delete the beneficiary");
        jdbc.update("UPDATE dmt_beneficiaries SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", beneficiaryId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", beneficiaryId);
        out.put("deleted", true);
        out.put("message", PaysprintMessages.fromNode(root, "Beneficiary deleted"));
        return out;
    }

    public Map<String, Object> sendTxnOtp(AuthPrincipal me, UUID senderId, UUID beneficiaryId,
                                          BigDecimal amount, String transferMode) {
        requireReady(me.userId());
        if (amount == null || amount.compareTo(BigDecimal.ONE) < 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Amount must be at least 1");
        }
        String mode = transferMode == null ? "" : transferMode.trim().toUpperCase();
        if (!mode.equals("IMPS") && !mode.equals("NEFT")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MODE", "Choose IMPS or NEFT");
        }
        Map<String, Object> sender = sender(senderId, me.userId());
        Map<String, Object> bene = bene(beneficiaryId, me.userId());
        if (!senderId.equals(bene.get("sender_id"))) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BENEFICIARY_NOT_FOUND", "Beneficiary does not belong to this customer");
        }
        DmtCasaLedger.Quote quote = ledger.quote(me.userId(), amount);
        String merchant = merchantCode(me.userId());
        String mobile = String.valueOf(sender.get("mobile"));
        log.info("DMT_CASA txn otp retailer={} merchantcode={} mobile={} bene={} amount={} fee={}",
                me.userId(), merchant, mobile, beneficiaryId, amount, quote.fee());
        JsonNode root = paysprint.sendTxnOtp(merchant, mobile, String.valueOf(bene.get("provider_bene_id")),
                DmtCasaTexts.amountText(amount));
        log.info("DMT_CASA txn otp result merchantcode={} mobile={} response_code={} stateresp_present={} message={}",
                merchant, mobile, DmtCasaTexts.code(root), !DmtCasaTexts.text(root, "stateresp").isBlank(),
                DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        paysprint.requireOk(root, "PaySprint did not send the transfer OTP");
        String stateResp = DmtCasaTexts.text(root, "stateresp");
        if (stateResp.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_CASA_ERROR", "PaySprint did not return a transfer reference");
        }
        String reference = ledger.nextReference();
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dmt_txn_otp_sessions
                    (id, sender_id, agent_user_id, beneficiary_id, provider_bene_id, amount, fee,
                     txn_type, reference_id, stateresp, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now() + interval '10 minutes')
                """, sessionId, senderId, me.userId(), beneficiaryId, bene.get("provider_bene_id"),
                amount, quote.fee(), mode, reference, stateResp);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("amount", amount);
        out.put("fee", quote.fee());
        out.put("total", quote.total());
        out.put("available", quote.available());
        out.put("referenceId", reference);
        out.put("transferMode", mode);
        out.put("message", PaysprintMessages.fromNode(root, "OTP sent to the customer"));
        return out;
    }

    public Map<String, Object> process(AuthPrincipal me, UUID sessionId, String otp, String idemKey, String canonical) {
        requireReady(me.userId());
        String merchant = merchantCode(me.userId());
        DmtCasaLedger.HoldOutcome held = ledger.hold(me.userId(), sessionId, idemKey, canonical);
        if (held.replay()) {
            log.info("DMT_CASA process replay retailer={} session={}", me.userId(), sessionId);
            return held.body();
        }
        log.info("DMT_CASA process retailer={} merchantcode={} mobile={} bene={} amount={} referenceid={} otp_present={}",
                me.userId(), merchant, held.mobile(), held.beneId(), held.amount(), held.referenceId(),
                otp != null && !otp.isBlank());
        JsonNode root;
        try {
            root = paysprint.processTxn(merchant, held.mobile(), held.beneId(), held.referenceId(),
                    held.txnType(), DmtCasaTexts.amountText(held.amount()), otp, held.stateResp());
        } catch (ApiException e) {
            log.info("DMT_CASA process error txnId={} referenceid={} message={}", held.txnId(), held.referenceId(), e.getMessage());
            return ledger.markPending(held.txnId(), e.getMessage(), held.keyId());
        }
        log.info("DMT_CASA process result referenceid={} response_code={} txn_status={} message={}",
                held.referenceId(), DmtCasaTexts.code(root), DmtCasaTexts.txnStatus(root),
                DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        return ledger.apply(held.txnId(), root, held.keyId());
    }

    public Map<String, Object> status(AuthPrincipal me, UUID txnId) {
        requireReady(me.userId());
        Map<String, Object> facts = ledger.transferFacts(txnId, me.userId());
        String reference = String.valueOf(facts.get("reference_id"));
        log.info("DMT_CASA status retailer={} referenceid={} from={}", me.userId(), reference, facts.get("status"));
        JsonNode root = paysprint.txnStatus(reference);
        log.info("DMT_CASA status result referenceid={} response_code={} txn_status={} message={}",
                reference, DmtCasaTexts.code(root), DmtCasaTexts.txnStatus(root),
                DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        return ledger.apply(txnId, root, null);
    }

    public Map<String, Object> refundOtp(AuthPrincipal me, UUID txnId) {
        requireReady(me.userId());
        Map<String, Object> facts = ledger.transferFacts(txnId, me.userId());
        if (!"CLAIM_REQUIRED".equals(String.valueOf(facts.get("refund_state")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_NOT_DUE",
                    "This transfer does not have a refund waiting");
        }
        String ackno = facts.get("provider_ackno") == null ? "" : String.valueOf(facts.get("provider_ackno"));
        if (ackno.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_NOT_DUE", "PaySprint did not return an acknowledgement to refund");
        }
        String merchant = merchantCode(me.userId());
        String reference = String.valueOf(facts.get("reference_id"));
        log.info("DMT_CASA refund otp retailer={} merchantcode={} referenceid={} ackno={}", me.userId(), merchant, reference, ackno);
        JsonNode root = paysprint.refundOtp(merchant, ackno, reference);
        log.info("DMT_CASA refund otp result referenceid={} response_code={} stateresp_present={} message={}",
                reference, DmtCasaTexts.code(root), !DmtCasaTexts.text(root, "stateresp").isBlank(),
                DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        paysprint.requireOk(root, "PaySprint did not send the refund OTP");
        String stateResp = DmtCasaTexts.text(root, "stateresp");
        if (stateResp.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_CASA_ERROR", "PaySprint did not return a refund reference");
        }
        ledger.rememberRefundOtp(txnId, me.userId(), stateResp);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transactionId", txnId);
        out.put("message", PaysprintMessages.fromNode(root, "Refund OTP sent to the customer"));
        return out;
    }

    public Map<String, Object> refundClaim(AuthPrincipal me, UUID txnId, String otp) {
        requireReady(me.userId());
        Map<String, Object> facts = ledger.transferFacts(txnId, me.userId());
        String stateResp = facts.get("refund_stateresp") == null ? "" : String.valueOf(facts.get("refund_stateresp"));
        if (stateResp.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_OTP_REQUIRED", "Send the refund OTP first");
        }
        String ackno = String.valueOf(facts.get("provider_ackno"));
        String reference = String.valueOf(facts.get("reference_id"));
        String merchant = merchantCode(me.userId());
        log.info("DMT_CASA refund claim retailer={} merchantcode={} referenceid={} ackno={} otp_present={}",
                me.userId(), merchant, reference, ackno, otp != null && !otp.isBlank());
        JsonNode root = paysprint.refundClaim(merchant, ackno, reference, otp, stateResp);
        log.info("DMT_CASA refund claim result referenceid={} response_code={} message={}",
                reference, DmtCasaTexts.code(root), DmtCasaTexts.clip(PaysprintMessages.fromNode(root, "")));
        return ledger.refund(txnId, me.userId(), root);
    }

    private UUID upsertSender(UUID agentId, String mobile, String status, BigDecimal limit, String url, String sdk) {
        return jdbc.queryForObject("""
                INSERT INTO dmt_senders
                    (id, mobile, full_name, registered_by, provider_status, provider_limit, provider_checked_at, ao_url, ao_sdk_token)
                VALUES (?, ?, NULL, ?, ?, ?, now(), ?, ?)
                ON CONFLICT (registered_by, mobile) DO UPDATE
                    SET provider_status = EXCLUDED.provider_status,
                        provider_limit = EXCLUDED.provider_limit,
                        provider_checked_at = now(),
                        ao_url = COALESCE(EXCLUDED.ao_url, dmt_senders.ao_url),
                        ao_sdk_token = COALESCE(EXCLUDED.ao_sdk_token, dmt_senders.ao_sdk_token)
                RETURNING id
                """, UUID.class, UUID.randomUUID(), mobile, agentId, status, limit,
                DmtCasaTexts.blankToNull(url), DmtCasaTexts.blankToNull(sdk));
    }

    private UUID upsertBene(UUID senderId, String beneId, JsonNode item, String fullAccount) {
        String name = DmtCasaTexts.text(item, "name", "benename");
        if (name.isBlank()) {
            name = "Beneficiary";
        }
        String ifsc = DmtCasaTexts.ifsc(DmtCasaTexts.text(item, "ifsc", "ifsccode"));
        if (ifsc.isBlank()) {
            ifsc = "XXXXXXXXXXX";
        }
        String masked = DmtCasaTexts.text(item, "accno", "account_number");
        String bankId = DmtCasaTexts.blankToNull(DmtCasaTexts.text(item, "bankid"));
        String verified = DmtCasaTexts.text(item, "verified");
        String enc = fullAccount == null ? null : Base64.getEncoder().encodeToString(fullAccount.getBytes());
        String last4 = DmtCasaTexts.last4(fullAccount != null ? fullAccount : masked);
        List<UUID> existing = jdbc.query("""
                SELECT id FROM dmt_beneficiaries
                 WHERE sender_id = ? AND provider_bene_id = ?
                 ORDER BY created_at DESC
                 LIMIT 1
                """, (rs, row) -> rs.getObject("id", UUID.class), senderId, beneId);
        if (!existing.isEmpty()) {
            jdbc.update("""
                    UPDATE dmt_beneficiaries
                       SET name = ?, ifsc = ?, account_masked = ?, account_last4 = ?,
                           provider_bank_id = COALESCE(?, provider_bank_id),
                           account_number_enc = COALESCE(?, account_number_enc),
                           verified_at = CASE WHEN ? = '1' THEN COALESCE(verified_at, now()) ELSE verified_at END,
                           deleted_at = NULL
                     WHERE id = ?
                    """, name, ifsc, DmtCasaTexts.blankToNull(masked), last4, bankId, enc, verified, existing.get(0));
            return existing.get(0);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO dmt_beneficiaries
                    (id, sender_id, name, account_number_enc, account_last4, ifsc, provider_bank_id,
                     provider_bene_id, account_masked, verified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = '1' THEN now() ELSE NULL END)
                """, id, senderId, name, enc, last4, ifsc, bankId, beneId, DmtCasaTexts.blankToNull(masked), verified);
        return id;
    }

    private void retireMissing(UUID senderId, List<String> seen) {
        if (seen.isEmpty()) {
            jdbc.update("""
                    UPDATE dmt_beneficiaries
                       SET deleted_at = now()
                     WHERE sender_id = ? AND deleted_at IS NULL AND provider_bene_id IS NOT NULL
                    """, senderId);
            return;
        }
        String marks = String.join(",", seen.stream().map(id -> "?").toList());
        Object[] args = new Object[seen.size() + 1];
        args[0] = senderId;
        for (int i = 0; i < seen.size(); i++) {
            args[i + 1] = seen.get(i);
        }
        jdbc.update("""
                UPDATE dmt_beneficiaries
                   SET deleted_at = now()
                 WHERE sender_id = ? AND deleted_at IS NULL AND provider_bene_id IS NOT NULL
                   AND provider_bene_id NOT IN (%s)
                """.formatted(marks), args);
    }

    private List<Map<String, Object>> listLocal(UUID senderId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.id, b.name, b.account_last4, b.account_masked, b.ifsc, b.provider_bank_id,
                       b.provider_bene_id, b.verified_at, b.verified_name, k.bank_name
                  FROM dmt_beneficiaries b
                  LEFT JOIN dmt_bank_list k ON k.bank_id = b.provider_bank_id
                 WHERE b.sender_id = ? AND b.deleted_at IS NULL
                 ORDER BY b.created_at
                """, senderId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(beneView(row));
        }
        return out;
    }

    private Map<String, Object> oneBene(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.id, b.name, b.account_last4, b.account_masked, b.ifsc, b.provider_bank_id,
                       b.provider_bene_id, b.verified_at, b.verified_name, k.bank_name
                  FROM dmt_beneficiaries b
                  LEFT JOIN dmt_bank_list k ON k.bank_id = b.provider_bank_id
                 WHERE b.id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BENEFICIARY_NOT_FOUND", "Beneficiary not found");
        }
        return beneView(rows.get(0));
    }

    private static Map<String, Object> beneView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("name", row.get("verified_name") != null ? row.get("verified_name") : row.get("name"));
        out.put("accountLast4", row.get("account_last4"));
        out.put("accountMasked", row.get("account_masked"));
        out.put("ifsc", row.get("ifsc"));
        out.put("bankId", row.get("provider_bank_id"));
        out.put("bankName", row.get("bank_name"));
        out.put("providerBeneId", row.get("provider_bene_id"));
        out.put("verified", row.get("verified_at") != null);
        return out;
    }

    private Map<String, Object> sender(UUID senderId, UUID agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, mobile, provider_status FROM dmt_senders WHERE id = ? AND registered_by = ?
                """, senderId, agentId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SENDER_NOT_FOUND", "Customer is not registered");
        }
        if (!"ACTIVE".equals(String.valueOf(rows.get(0).get("provider_status")))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "REMITTER_NOT_ACTIVE",
                    "This customer cannot send money until the account is active");
        }
        return rows.get(0);
    }

    private Map<String, Object> bene(UUID beneficiaryId, UUID agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.id, b.sender_id, b.name, b.ifsc, b.provider_bank_id, b.provider_bene_id,
                       b.account_number_enc, b.account_masked, s.mobile
                  FROM dmt_beneficiaries b
                  JOIN dmt_senders s ON s.id = b.sender_id
                 WHERE b.id = ? AND s.registered_by = ? AND b.deleted_at IS NULL
                """, beneficiaryId, agentId);
        if (rows.isEmpty() || rows.get(0).get("provider_bene_id") == null) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BENEFICIARY_NOT_FOUND", "Beneficiary not found");
        }
        return rows.get(0);
    }

    private static String accountOf(Map<String, Object> bene) {
        Object enc = bene.get("account_number_enc");
        if (enc != null && !String.valueOf(enc).isBlank()) {
            return new String(Base64.getDecoder().decode(String.valueOf(enc)));
        }
        Object masked = bene.get("account_masked");
        return masked == null ? "" : String.valueOf(masked);
    }

    private String requireActiveBank(String bankId) {
        String id = bankId == null ? "" : bankId.trim();
        if (id.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_REQUIRED", "Choose the beneficiary bank");
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*)::int FROM dmt_bank_list WHERE bank_id = ? AND active", Integer.class, id);
        if (n == null || n == 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_INACTIVE", "That bank is not available for money transfer");
        }
        return id;
    }

    private void requireReady(UUID userId) {
        services.requireEnabled(PlatformServiceGate.DMT);
        Integer ready = jdbc.queryForObject("""
                SELECT count(*)::int FROM paysprint_bank_ekyc WHERE user_id = ? AND is_dmt_active
                """, Integer.class, userId);
        if (ready == null || ready == 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DMT_INACTIVE",
                    "Activate DMT before sending money");
        }
    }

    private String merchantCode(UUID userId) {
        String code = jdbc.queryForObject("SELECT code FROM users WHERE id = ?", String.class, userId);
        if (code == null || code.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MERCHANT_CODE_MISSING",
                    "This retailer has no merchant code yet");
        }
        return code.trim();
    }
}
