package com.fintech.dmt;

import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.verification.DmtProvider;
import com.fintech.verification.VerificationGate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** DMT desk. The provider call waits until the vendor API is connected. */
@Service
public class DmtCasaService {

    private final VerificationGate verification;
    private final DmtProvider provider;
    private final PlatformServiceGate services;

    public DmtCasaService(VerificationGate verification, DmtProvider provider, PlatformServiceGate services) {
        this.verification = verification;
        this.provider = provider;
        this.services = services;
    }

    public Map<String, Object> queryRemitter(AuthPrincipal me, String mobile) {
        return blocked(me);
    }

    public List<Map<String, Object>> beneficiaries(AuthPrincipal me, UUID senderId) {
        blocked(me);
        return List.of();
    }

    public Map<String, Object> sendBeneOtp(AuthPrincipal me, UUID senderId, String name, String accountNumber,
                                           String ifsc, String bankId) {
        return blocked(me);
    }

    public Map<String, Object> addBeneficiary(AuthPrincipal me, UUID senderId, UUID sessionId, String otp) {
        return blocked(me);
    }

    public Map<String, Object> verifyBeneficiary(AuthPrincipal me, UUID beneficiaryId) {
        return blocked(me);
    }

    public Map<String, Object> deleteBeneficiary(AuthPrincipal me, UUID beneficiaryId) {
        return blocked(me);
    }

    public Map<String, Object> sendTxnOtp(AuthPrincipal me, UUID senderId, UUID beneficiaryId,
                                          BigDecimal amount, String transferMode) {
        return blocked(me);
    }

    public Map<String, Object> process(AuthPrincipal me, UUID sessionId, String otp, String idemKey, String canonical) {
        return blocked(me);
    }

    public Map<String, Object> status(AuthPrincipal me, UUID txnId) {
        return blocked(me);
    }

    public Map<String, Object> refundOtp(AuthPrincipal me, UUID txnId) {
        return blocked(me);
    }

    public Map<String, Object> refundClaim(AuthPrincipal me, UUID txnId, String otp) {
        return blocked(me);
    }

    private Map<String, Object> blocked(AuthPrincipal me) {
        services.requireEnabled(PlatformServiceGate.DMT);
        verification.requireBusiness(me.userId());
        provider.execute();
        return Map.of();
    }
}
