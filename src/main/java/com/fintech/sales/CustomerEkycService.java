package com.fintech.sales;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerEkycService {

    private static final Logger log = LoggerFactory.getLogger(CustomerEkycService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> OVD_TYPES = Set.of("AADHAAR", "PAN", "VOTER_ID", "DL", "PASSPORT");

    private final JdbcTemplate jdbc;
    private final CustomerService customers;

    public CustomerEkycService(JdbcTemplate jdbc, CustomerService customers) {
        this.jdbc = jdbc;
        this.customers = customers;
    }

    @Transactional
    public Map<String, Object> start(AuthPrincipal me, UUID customerId, String ovdType, String ovdLast4) {
        CustomerService.requireSalesDesk(me);
        Map<String, Object> customer = customers.get(me, customerId);
        if ("VERIFIED".equals(String.valueOf(customer.get("ekyc_status")))) {
            throw ApiException.of(HttpStatus.CONFLICT, "EKYC_ALREADY_VERIFIED", "eKYC is already verified for this customer");
        }
        String type = ovdType == null ? "" : ovdType.trim().toUpperCase().replace(' ', '_');
        if (!OVD_TYPES.contains(type)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OVD",
                    "ID must be Aadhaar, PAN, voter ID, driving licence or passport");
        }
        String compact = ovdLast4 == null ? "" : ovdLast4.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (compact.length() < 4) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_OVD",
                    "Enter the last 4 characters of the ID. The full number is never stored.");
        }
        String last4 = compact.substring(compact.length() - 4);
        java.util.ArrayList<Object> ovdArgs = new java.util.ArrayList<>();
        ovdArgs.add(type);
        ovdArgs.add(last4);
        String ovdWhere = CustomerService.deskCustomerWhere(me, ovdArgs, customerId);
        int ovdRows = jdbc.update("""
                UPDATE customers SET ovd_type = ?, ovd_last4 = ?, updated_at = now()
                 WHERE %s
                """.formatted(ovdWhere), ovdArgs.toArray());
        if (ovdRows == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found");
        }
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("""
                UPDATE customer_ekyc_challenges SET expires_at = now()
                 WHERE customer_id = ? AND verified_at IS NULL AND expires_at > now()
                """, customerId);
        jdbc.update("""
                INSERT INTO customer_ekyc_challenges (id, customer_id, otp_hash, expires_at)
                VALUES (?, ?, ?, now() + interval '5 minutes')
                """, UUID.randomUUID(), customerId, hash(otp, customerId));
        log.info("CUSTOMER_EKYC_OTP customer={}", customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", true);
        out.put("expiresInSeconds", 300);
        return out;
    }

    @Transactional
    public Map<String, Object> verify(AuthPrincipal me, UUID customerId, String otp) {
        CustomerService.requireSalesDesk(me);
        Map<String, Object> customer = customers.get(me, customerId);
        if ("VERIFIED".equals(String.valueOf(customer.get("ekyc_status")))) {
            return customer;
        }
        if (customer.get("ovd_last4") == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EKYC_INCOMPLETE", "Capture the ID before verifying the OTP");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, otp_hash FROM customer_ekyc_challenges
                 WHERE customer_id = ? AND verified_at IS NULL AND expires_at > now()
                 ORDER BY created_at DESC
                 LIMIT 1
                """, customerId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_EXPIRED", "OTP expired. Send a new one.");
        }
        String expected = String.valueOf(rows.get(0).get("otp_hash"));
        if (!expected.equals(hash(otp == null ? "" : otp.trim(), customerId))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "OTP_INVALID", "Incorrect OTP");
        }
        jdbc.update("UPDATE customer_ekyc_challenges SET verified_at = now() WHERE id = ?", rows.get(0).get("id"));
        java.util.ArrayList<Object> verifyArgs = new java.util.ArrayList<>();
        String verifyWhere = CustomerService.deskCustomerWhere(me, verifyArgs, customerId);
        int verifyRows = jdbc.update("""
                UPDATE customers
                   SET ekyc_status = 'VERIFIED', ekyc_verified_at = now(), updated_at = now()
                 WHERE %s
                """.formatted(verifyWhere), verifyArgs.toArray());
        if (verifyRows == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found");
        }
        log.info("CUSTOMER_EKYC_VERIFIED customer={}", customerId);
        return customers.get(me, customerId);
    }

    private static String hash(String otp, UUID customerId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((otp + ":" + customerId + ":EKYC").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("OTP hash failed", e);
        }
    }
}
