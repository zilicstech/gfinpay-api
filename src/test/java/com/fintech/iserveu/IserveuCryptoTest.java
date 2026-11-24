package com.fintech.iserveu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.idempotency.ExternalRefs;
import com.fintech.provider.canonical.ProviderModels;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IserveuCryptoTest {

    @Test
    void roundTripUsesAFreshIv() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i + 1);
        }
        String encoded = Base64.getEncoder().encodeToString(raw);
        byte[] key = IserveuCrypto.key(encoded);
        String first = IserveuCrypto.encrypt("{\"amount\":\"100\"}", key);
        String second = IserveuCrypto.encrypt("{\"amount\":\"100\"}", key);
        assertNotEquals(first, second);
        assertEquals("{\"amount\":\"100\"}", IserveuCrypto.decrypt(first, key));
        assertEquals("{\"amount\":\"100\"}", IserveuCrypto.decrypt(second, key));
    }

    @Test
    void bankErrorPayloadIsAFailedQr() throws Exception {
        String json = """
                {"status":"FAILED","statusCode":-1,"statusDesc":"Bank service internal server error",
                 "paymentState":"FAILED","txnId":"544485245462905700","clientRefId":"ABCD455100AA0011",
                 "qrData":"","intentData":"","amount":100}
                """;
        ProviderModels.QrResult result = IserveuCashpointAdapter.interpret(new ObjectMapper().readTree(json));
        assertFalse(result.success());
        assertTrue(result.qrPngBase64() == null || result.qrPngBase64().isBlank());
        assertEquals("Bank service internal server error", result.message());
    }

    @Test
    void callbackMapsStatusCodeMinusOneToFailed() throws Exception {
        String json = """
                {"status":"FAILED","statusCode":-1,"txnId":"544","clientRefId":"ABCD455100AA0011","rrn":""}
                """;
        ProviderModels.SettlementEvent event = IserveuCallbackAdapter.interpret(new ObjectMapper().readTree(json));
        assertEquals("FAILED", event.status());
        assertEquals("ABCD455100AA0011", event.externalRef());
    }

    @Test
    void cashpointStatusMapsSuccessFailedAndNoRecord() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProviderModels.StatusResult success = IserveuCashpointAdapter.interpretStatus(mapper.readTree("""
                {"length":1,"message":"Success","results":[{"status":"SUCCESS","txnId":"18","rrn":"RRN1","payerVPA":"payer@upi"}]}
                """), "");
        assertEquals("SUCCESS", success.qrStatus());
        assertEquals("RRN1", success.utr());
        assertEquals("payer@upi", success.payerVpa());

        ProviderModels.StatusResult failed = IserveuCashpointAdapter.interpretStatus(mapper.readTree("""
                {"length":1,"results":[{"status":"FAILED","statusDesc":"Declined","txnId":"19"}]}
                """), "");
        assertEquals("FAILED", failed.qrStatus());

        ProviderModels.StatusResult missing = IserveuCashpointAdapter.interpretStatus(mapper.readTree("""
                {"message":"No transaction record found","length":0,"Report":[],"status":1}
                """), "keep");
        assertEquals("PENDING", missing.qrStatus());
        assertEquals("keep", missing.txnId());
    }

    @Test
    void payoutStatusPairsAndWebhookStayPendingWhileInProgress() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(ProviderModels.Outcome.SUCCESS, IserveuPayoutAdapter.mapOutcome("SUCCESS", 0));
        assertEquals(ProviderModels.Outcome.SUCCESS, IserveuPayoutAdapter.mapOutcome("SUCCESS", 1));
        assertEquals(ProviderModels.Outcome.FAILED, IserveuPayoutAdapter.mapOutcome("FAILED", -1));
        assertEquals(ProviderModels.Outcome.PENDING, IserveuPayoutAdapter.mapOutcome("INPROGRESS", 1));
        assertEquals(ProviderModels.Outcome.PENDING, IserveuPayoutAdapter.mapOutcome("SUCCESS", 9));

        ProviderModels.SettlementEvent payout = IserveuCallbackAdapter.interpret(mapper.readTree("""
                {"productCode":"PAYOUT","txnType":"WALLET2CASHOUT_IMPS","status":"SUCCESS",
                 "statusDesc":"CO00","txnId":"371412641292288","clientRefId":"GFP0000000000000001","rrn":"308214158501","txnAmount":"100"}
                """));
        assertEquals("SUCCESS", payout.status());
        assertEquals("GFP0000000000000001", payout.externalRef());
        assertEquals("308214158501", payout.utr());

        ProviderModels.SettlementEvent waiting = IserveuCallbackAdapter.interpret(mapper.readTree("""
                {"productCode":"PAYOUT","status":"INPROGRESS","txnId":"371","clientRefId":"GFP0000000000000001"}
                """));
        assertEquals("PENDING", waiting.status());
    }

    @Test
    void onboardingLineAndPayoutRef() {
        IserveuOnboardingLine line = IserveuOnboardingLine.parse(
                "GFIN9XM3WG -> User Onboarded Successfully -> 63043914493699866190 -> gfin9xm3wg.cwbc@nsdlpbma");
        assertEquals("GFIN9XM3WG", line.retailerCode());
        assertEquals("63043914493699866190", line.accountNumber());
        assertEquals("gfin9xm3wg.cwbc@nsdlpbma", line.vpa());

        String ref = ExternalRefs.payoutClientRef(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertEquals("63043914493699866190", IserveuCashpointAdapter.terminalId("63043914493699866190"));
        assertEquals("63043914493699866190", IserveuCashpointAdapter.terminalId("6304-3914-4936-9986-6190"));
        assertEquals(20, IserveuCashpointAdapter.terminalId("63043914493699866190123").length());
        assertEquals("", IserveuCashpointAdapter.terminalId(""));

        assertEquals(19, ref.length());
        assertTrue(ref.matches("[A-Z]{3}[0-9]{16}"));
        assertEquals(ref, ExternalRefs.payoutClientRef(UUID.fromString("11111111-1111-1111-1111-111111111111")));
    }

    @Test
    void auditHeadersHideThePassKeyAndRailsAreNamedFromTheUrl() {
        var headers = IserveuAuditLogService.outboundHeaders("ciphertext-header");
        assertEquals("[REDACTED]", headers.get("pass_key"));
        assertFalse(headers.toString().contains("live-pass-key"));
        assertEquals("CASHPOINT_QR", IserveuAuditLogService.railFromUrl(
                "https://example.test/commonAdmin/api/upi/initiate-dynamic-transaction/v2"));
        assertEquals("CASHPOINT_STATUS", IserveuAuditLogService.railFromUrl("https://example.test/upi/status/v2"));
        assertEquals("PAYOUT_STATUS", IserveuAuditLogService.railFromUrl("https://example.test/payout/status"));
        assertEquals("PAYOUT", IserveuAuditLogService.railFromUrl(
                "https://api-prod.txninfra.com/encrV1/w1w2-payout/w1/cashtransfer"));
        assertEquals("PAYOUT_STATUS", IserveuAuditLogService.railFromUrl(
                "https://api-prod.txninfra.com/encrV1/payout/statuscheck/txnreport"));
    }

    @Test
    void payoutTransferBodyAndSubmitStatuses() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProviderModels.PayoutRequest request = new ProviderModels.PayoutRequest(
                "Shop Owner", "026291800001191", "sbin0001083", "IMPS", "100.00",
                "GFP0000000000000001", "12.9716", "77.5946", "Settlement",
                "Retailer Name Longer Than Twenty Five", "9876543210", "560001", "State Bank of India", "203.0.113.10");
        Map<String, Object> body = IserveuPayoutAdapter.transferBody(request, "IMPS");
        assertEquals("026291800001191", body.get("beneAccountNo"));
        assertEquals("SBIN0001083", body.get("beneifsc"));
        assertEquals("IMPS", body.get("fundTransferType"));
        assertEquals("GFP0000000000000001", body.get("clientReferenceNo"));
        assertEquals(9876543210L, body.get("benePhoneNo"));
        assertEquals(25, String.valueOf(body.get("custName")).length());
        assertEquals("203.0.113.10", body.get("custIpAddress"));

        ProviderModels.PayoutResult success = IserveuPayoutAdapter.interpretSubmit(mapper.readTree("""
                {"status":"SUCCESS","statusCode":0,"statusDesc":"CO00","txnId":"371","rrn":"308214158501"}
                """));
        assertEquals(ProviderModels.Outcome.SUCCESS, success.outcome());
        assertEquals("308214158501", success.utr());

        ProviderModels.PayoutResult waiting = IserveuPayoutAdapter.interpretSubmit(mapper.readTree("""
                {"status":"INPROGRESS","statusCode":1,"statusDesc":"Awaiting bank"}
                """));
        assertEquals(ProviderModels.Outcome.PENDING, waiting.outcome());

        ProviderModels.PayoutResult declined = IserveuPayoutAdapter.interpretSubmit(mapper.readTree("""
                {"status":"FAILED","statusCode":-1,"statusDesc":"Failed from Bank"}
                """));
        assertEquals(ProviderModels.Outcome.FAILED, declined.outcome());
    }
}
