package com.fintech.sms;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class R2WinSmsSenderTest {

    private MockRestServiceServer server;
    private R2WinSmsSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        R2WinSmsProperties props = new R2WinSmsProperties(
                "https://smsapi.r2win.com",
                "qYP2wxFfRZVitUp69hboyn7DMs5ClQ",
                "GFINPY",
                "1077291870062568175",
                "Dear User, <VAR1> is your One-Time Password (OTP) to access your account. Valid for <VAR2> mins. Do not share it with anyone. Team GFinPay"
        );
        sender = new R2WinSmsSender(props, builder.build(), new ObjectMapper());
    }

    @Test
    void formatsDltOtpTextWithVariables() {
        String text = sender.formatOtpText("482910", "5");
        assertEquals(
                "Dear User, 482910 is your One-Time Password (OTP) to access your account. Valid for 5 mins. Do not share it with anyone. Team GFinPay",
                text
        );
    }

    @Test
    void normalizesAndValidatesIndianMobileNumbers() {
        assertEquals("919871701375", R2WinSmsSender.normalizeRecipient("9871701375"));
        assertEquals("918744851927", R2WinSmsSender.normalizeRecipient("+91 87448-51927"));
        assertEquals("916123456789", R2WinSmsSender.normalizeRecipient("6123456789"));

        assertThrows(ApiException.class, () -> R2WinSmsSender.normalizeRecipient("5123456789"));
        assertThrows(ApiException.class, () -> R2WinSmsSender.normalizeRecipient("915123456789"));
        assertThrows(ApiException.class, () -> R2WinSmsSender.normalizeRecipient("98717013"));
    }

    @Test
    void sendsOtpToR2WinPushEndpointAndAcceptsSuccessResponse() {
        String expectedEncodedText =
                "Dear%20User,%20654321%20is%20your%20One-Time%20Password%20(OTP)%20to%20access%20your%20account.%20Valid%20for%205%20mins.%20Do%20not%20share%20it%20with%20anyone.%20Team%20GFinPay";

        server.expect(method(HttpMethod.GET))
                .andExpect(queryParam("accesskey", "qYP2wxFfRZVitUp69hboyn7DMs5ClQ"))
                .andExpect(queryParam("to", "919871701375"))
                .andExpect(queryParam("text", expectedEncodedText))
                .andExpect(queryParam("from", "GFINPY"))
                .andExpect(queryParam("tid", "1077291870062568175"))
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"message\":[{\"9871701375\":\"249\"}]}",
                        MediaType.APPLICATION_JSON));

        assertDoesNotThrow(() -> sender.send(
                "9871701375",
                "OTP_ONBOARD",
                Map.of("otp", "654321", "purpose", "ONBOARD", "ttlMinutes", "5")
        ));
        server.verify();
    }

    @Test
    void rejectsWhenRecipientEntryReportsDndFailure() {
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":\"success\",\"message\":[{\"9827202228\":\"DND Failed\"}]}",
                        MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> sender.send(
                "9827202228",
                "OTP_ONBOARD",
                Map.of("otp", "112233", "ttlMinutes", "5")
        ));
        assertEquals("SMS_DELIVERY_FAILED", ex.getCode());
        assertTrue(ex.getMessage().contains("DND Failed"));
    }

    @Test
    void rejectsOnHttp401InvalidAccessKey() {
        server.expect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"fail\",\"message\":\"Invalid access key.\"}"));

        ApiException ex = assertThrows(ApiException.class, () -> sender.send(
                "9871701375",
                "OTP_REVERIFY",
                Map.of("otp", "998877", "ttlMinutes", "5")
        ));
        assertEquals("SMS_PROVIDER_ERROR", ex.getCode());
        assertTrue(ex.getMessage().contains("Invalid access key"));
    }

    @Test
    void skipsNonOtpTemplatesWithoutCallingR2Win() {
        assertDoesNotThrow(() -> sender.send(
                "9871701375",
                "ONBOARD_INVITE",
                Map.of("name", "Partner", "link", "https://app.gfinpay.com/onboard/abc")
        ));
        server.verify();
    }
}
