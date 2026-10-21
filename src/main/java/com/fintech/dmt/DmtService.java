package com.fintech.dmt;

import com.fintech.kyc.CustomerKycService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.web.ApiException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local sender mirror. PaySprint decides whether the customer can send money. */
@Service
public class DmtService {

    private static final Logger log = LoggerFactory.getLogger(DmtService.class);

    private final JdbcTemplate jdbc;
    private final CustomerKycService kyc;
    private final PlatformServiceGate services;

    public DmtService(JdbcTemplate jdbc, CustomerKycService kyc, PlatformServiceGate services) {
        this.jdbc = jdbc;
        this.kyc = kyc;
        this.services = services;
    }

    @Transactional
    public Map<String, Object> registerSender(String mobile, String fullName, UUID agentId) {
        services.requireEnabled(PlatformServiceGate.DMT);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO dmt_senders (id, mobile, full_name, registered_by) VALUES (?, ?, ?, ?)",
                    id, mobile, fullName, agentId);
            log.info("DMT_SENDER_REGISTERED senderId={} by agent={}", id, agentId);
        } catch (DuplicateKeyException e) {
            return findSenderByMobile(mobile, agentId);
        }
        return findSenderByMobile(mobile, agentId);
    }

    public Map<String, Object> findSenderByMobile(String mobile, UUID agentId) {
        services.requireEnabled(PlatformServiceGate.DMT);
        var rows = jdbc.queryForList("""
                SELECT s.id, s.mobile, s.full_name, s.kyc_level, s.ovd_type, s.ovd_last4, s.address_line,
                       s.pan_last4, s.mobile_verified_at, s.ovd_captured_at, s.provider_status, s.provider_limit
                  FROM dmt_senders s
                 WHERE s.mobile = ? AND s.registered_by = ?
                """, mobile, agentId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SENDER_NOT_FOUND", "No sender registered with this mobile");
        }
        return kyc.withFlags(rows.get(0));
    }

    public Map<String, Object> getSender(UUID senderId, UUID agentId) {
        services.requireEnabled(PlatformServiceGate.DMT);
        Integer owned = jdbc.queryForObject(
                "SELECT count(*)::int FROM dmt_senders WHERE id = ? AND registered_by = ?", Integer.class, senderId, agentId);
        if (owned == null || owned == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "SENDER_NOT_FOUND", "Customer is not registered");
        }
        return kyc.getCustomer(senderId);
    }

    public static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }
}
