package com.fintech.emerald;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmeraldLogSanitizerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sanitizesSecretHeadersWhilePreservingEmeraldDiagnosticHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Client-Id", "EMD_CLIENT_001");
        headers.put("X-Client-Secret", "SEC_LIVE_998877665544");
        headers.put("X-Timestamp", "1763030000123");
        headers.put("X-Signature", "a1b2c3d4e5f6");
        headers.put("X-Entity-Code", "ENT000002");
        headers.put("X-Outlet-Code", "OUT_02_99");
        headers.put("X-Request-Id", "req-uuid-123");
        headers.put("X-Emerald-Callback-Secret", "CB_SECRET_7788");

        Map<String, String> sanitized = EmeraldLogSanitizer.sanitizeHeaders(headers);

        assertEquals("EMD_CLIENT_001", sanitized.get("X-Client-Id"));
        assertEquals("SEC_****5544", sanitized.get("X-Client-Secret"));
        assertEquals("1763030000123", sanitized.get("X-Timestamp"));
        assertEquals("a1b2c3d4e5f6", sanitized.get("X-Signature"));
        assertEquals("ENT000002", sanitized.get("X-Entity-Code"));
        assertEquals("OUT_02_99", sanitized.get("X-Outlet-Code"));
        assertEquals("req-uuid-123", sanitized.get("X-Request-Id"));
        assertEquals("CB_S****7788", sanitized.get("X-Emerald-Callback-Secret"));
    }

    @Test
    void redactsBiometricCiphertextsAndAadhaarWhilePreservingRdDeviceMetadataAndTopLevelData() {
        String biometricReq = """
                {
                  "pipeCode": "INSTANTPAY",
                  "referenceKey": "REF_BIO_778899",
                  "externalRef": "BIO123456",
                  "biometricData": {
                    "encryptedAadhaar": "499118665243",
                    "dpId": "MANTRA.MSIPL",
                    "rdsId": "MANTRA.AND.001",
                    "rdsVer": "1.0.8",
                    "mi": "MFS110",
                    "dc": "dc-uuid-1234",
                    "ci": "20281029",
                    "qScore": "85",
                    "nmPoints": "46",
                    "data": "PID_DATA_BASE64_CIPHERTEXT",
                    "hmac": "HMAC_BASE64_CIPHERTEXT",
                    "skey": "SKEY_BASE64_CIPHERTEXT",
                    "mc": "CERT_BASE64"
                  }
                }
                """;

        String sanitizedReq = EmeraldLogSanitizer.sanitizeJsonOrRaw(mapper, biometricReq);

        assertFalse(sanitizedReq.contains("499118665243"), "Full 12-digit Aadhaar must be masked");
        assertTrue(sanitizedReq.contains("XXXXXXXX5243"), "Aadhaar last 4 must remain visible for tracing");
        assertFalse(sanitizedReq.contains("PID_DATA_BASE64_CIPHERTEXT"), "UIDAI PID data block must be redacted");
        assertFalse(sanitizedReq.contains("HMAC_BASE64_CIPHERTEXT"), "UIDAI HMAC must be redacted");
        assertFalse(sanitizedReq.contains("SKEY_BASE64_CIPHERTEXT"), "UIDAI Skey must be redacted");
        assertTrue(sanitizedReq.contains("[REDACTED_BIOMETRIC len="));
        // Critical Emerald support fields must remain intact
        assertTrue(sanitizedReq.contains("MANTRA.MSIPL"));
        assertTrue(sanitizedReq.contains("MFS110"));
        assertTrue(sanitizedReq.contains("REF_BIO_778899"));
        assertTrue(sanitizedReq.contains("\"qScore\":\"85\""));

        String emeraldRes = """
                {
                  "statuscode": "2000",
                  "success": true,
                  "message": "Outlet details",
                  "data": {
                    "outletCode": "OUT_02_99",
                    "bankAccounts": [
                      {
                        "bankAccountNo": "501002349988",
                        "bankIfsc": "HDFC0001234"
                      }
                    ]
                  }
                }
                """;

        String sanitizedRes = EmeraldLogSanitizer.sanitizeJsonOrRaw(mapper, emeraldRes);
        assertTrue(sanitizedRes.contains("\"outletCode\":\"OUT_02_99\""), "Top-level data object must stay intact");
        assertTrue(sanitizedRes.contains("501002349988"), "Account number preserved unmasked");
        assertTrue(sanitizedRes.contains("HDFC0001234"));
    }
}

