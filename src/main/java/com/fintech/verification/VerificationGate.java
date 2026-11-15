package com.fintech.verification;

import com.fintech.agreement.AgreementProperties;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class VerificationGate {

    private final JdbcTemplate jdbc;
    private final AgreementProperties agreementProps;

    @Autowired
    public VerificationGate(JdbcTemplate jdbc, AgreementProperties agreementProps) {
        this.jdbc = jdbc;
        this.agreementProps = agreementProps != null
                ? agreementProps
                : new AgreementProperties(true, BigDecimal.ZERO, null);
    }

    public VerificationGate(JdbcTemplate jdbc) {
        this(jdbc, new AgreementProperties(true, BigDecimal.ZERO, null));
    }

    public void requireIdentity(UUID userId) {
        requireActive(userId);
        if (!"VERIFIED".equals(status(userId, "identity_status"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY_VERIFICATION_REQUIRED",
                    "Complete identity verification before using this service");
        }
    }

    public void requirePersonal(UUID userId) {
        requireIdentity(userId);
    }

    public void requireBank(UUID userId) {
        requireIdentity(userId);
        if (!"VERIFIED".equals(status(userId, "bank_verification_status"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BANK_VERIFICATION_REQUIRED",
                    "Verify a bank account to activate your wallet before using this service");
        }
    }

    public void requireBusiness(UUID userId) {
        requireBank(userId);
        if (!"BUSINESS_VERIFIED".equals(status(userId, "business_status"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_VERIFICATION_REQUIRED",
                    "Finish business registration and approval before using this service");
        }
        if (agreementProps.enforce() && !"SIGNED".equals(status(userId, "agreement_status"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "AGREEMENT_SIGNATURE_REQUIRED",
                    "Sign the Partner Master Service Agreement before using this service");
        }
    }

    private void requireActive(UUID userId) {
        String status = jdbc.queryForObject("SELECT status::text FROM users WHERE id = ?", String.class, userId);
        if (!"ACTIVE".equals(status)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "USER_NOT_ACTIVE",
                    "This account has to be active before the service can be used");
        }
    }

    private String status(UUID userId, String column) {
        String sql = switch (column) {
            case "identity_status" -> "SELECT identity_status FROM agent_kyc_profiles WHERE user_id = ?";
            case "bank_verification_status" -> "SELECT bank_verification_status FROM agent_kyc_profiles WHERE user_id = ?";
            case "business_status" -> "SELECT business_status FROM agent_kyc_profiles WHERE user_id = ?";
            case "agreement_status" -> "SELECT agreement_status FROM agent_kyc_profiles WHERE user_id = ?";
            default -> throw new IllegalArgumentException("Unsupported status column");
        };
        try {
            String value = jdbc.queryForObject(sql, String.class, userId);
            return value == null ? "NOT_VERIFIED" : value;
        } catch (EmptyResultDataAccessException e) {
            return "NOT_VERIFIED";
        }
    }
}
