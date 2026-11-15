package com.fintech.iserveu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.provider.canonical.ProviderModels;
import java.util.Base64;
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
}
