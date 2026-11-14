package com.fintech.sms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.web.ApiException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "r2win")
@EnableConfigurationProperties(R2WinSmsProperties.class)
public class R2WinSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(R2WinSmsSender.class);

    public static final String DEFAULT_BASE_URL = "https://smsapi.r2win.com";
    public static final String DEFAULT_HEADER = "GFINPY";
    public static final String DEFAULT_OTP_TEMPLATE_ID = "1077291870062568175";
    public static final String DEFAULT_OTP_TEMPLATE_TEXT =
            "Dear User, <VAR1> is your One-Time Password (OTP) to access your account. Valid for <VAR2> mins. Do not share it with anyone. Team GFinPay";

    private final R2WinSmsProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper;

    @Autowired
    public R2WinSmsSender(R2WinSmsProperties props, ObjectMapper mapper) {
        this(props, defaultRestClient(), mapper);
    }

    R2WinSmsSender(R2WinSmsProperties props, RestClient rest, ObjectMapper mapper) {
        this.props = props;
        this.rest = rest;
        this.mapper = mapper;
    }

    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        rf.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        return RestClient.builder().requestFactory(rf).build();
    }

    @Override
    public void send(String mobile, String templateKey, Map<String, String> params) {
        Map<String, String> safeParams = params == null ? Map.of() : params;
        boolean isOtp = (templateKey != null && templateKey.startsWith("OTP_")) || safeParams.containsKey("otp");

        if (!isOtp) {
            log.info("R2WIN_SMS_SKIPPED_NON_OTP mobile={} template={}", mask(mobile), templateKey);
            return;
        }

        String accessKey = props.accessKey() == null ? "" : props.accessKey().trim();
        if (accessKey.isEmpty()) {
            throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "SMS_CONFIG_MISSING",
                    "SMS gateway access key is not configured");
        }

        String recipient = normalizeRecipient(mobile);
        String otp = safeParams.getOrDefault("otp", "").trim();
        String ttlMinutes = safeParams.getOrDefault("ttlMinutes", "5").trim();
        String messageText = formatOtpText(otp, ttlMinutes);
        String header = notBlankOrDefault(props.header(), DEFAULT_HEADER);
        String templateId = notBlankOrDefault(props.otpTemplateId(), DEFAULT_OTP_TEMPLATE_ID);
        String baseUrl = notBlankOrDefault(props.baseUrl(), DEFAULT_BASE_URL);

        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/push")
                .queryParam("accesskey", accessKey)
                .queryParam("to", recipient)
                .queryParam("text", messageText)
                .queryParam("from", header)
                .queryParam("tid", templateId)
                .build()
                .encode()
                .toUri();

        String rawBody;
        try {
            rawBody = rest.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException httpEx) {
            String errBody = httpEx.getResponseBodyAsString();
            String gatewayMsg = extractFailureMessage(errBody, "HTTP " + httpEx.getStatusCode().value());
            log.warn("R2WIN_SMS_HTTP_ERROR status={} mobile={} template={} msg={}",
                    httpEx.getStatusCode().value(), mask(recipient), templateKey, gatewayMsg);
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "SMS_PROVIDER_ERROR",
                    "Could not send OTP SMS: " + gatewayMsg);
        } catch (Exception ex) {
            log.warn("R2WIN_SMS_UNREACHABLE mobile={} template={} err={}",
                    mask(recipient), templateKey, ex.getMessage());
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "SMS_PROVIDER_UNAVAILABLE",
                    "SMS gateway could not be reached. Please try again.");
        }

        verifyGatewayResponse(rawBody, recipient, templateKey);
    }

    public String formatOtpText(String otp, String ttlMinutes) {
        String template = notBlankOrDefault(props.otpTemplateText(), DEFAULT_OTP_TEMPLATE_TEXT);
        return template
                .replace("<VAR1>", otp == null ? "" : otp)
                .replace("<VAR2>", ttlMinutes == null || ttlMinutes.isBlank() ? "5" : ttlMinutes);
    }

    public static String normalizeRecipient(String mobile) {
        String digits = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (digits.length() == 10) {
            char first = digits.charAt(0);
            if (first < '6' || first > '9') {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                        "Mobile number must start with 6, 7, 8, or 9");
            }
            return "91" + digits;
        }
        if (digits.length() == 12 && digits.startsWith("91")) {
            char third = digits.charAt(2);
            if (third < '6' || third > '9') {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                        "Mobile number must start with 6, 7, 8, or 9");
            }
            return digits;
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MOBILE",
                "Enter a valid 10-digit Indian mobile number");
    }

    @SuppressWarnings("unchecked")
    void verifyGatewayResponse(String rawBody, String recipient, String templateKey) {
        if (rawBody == null || rawBody.isBlank()) {
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "SMS_PROVIDER_ERROR",
                    "Empty response from SMS gateway");
        }
        Map<String, Object> parsed;
        try {
            parsed = mapper.readValue(rawBody.trim(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("R2WIN_SMS_PARSE_WARN mobile={} raw={}", mask(recipient), rawBody);
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "SMS_PROVIDER_ERROR",
                    "Unexpected response from SMS gateway");
        }

        String status = String.valueOf(parsed.getOrDefault("status", "")).trim();
        if (!"success".equalsIgnoreCase(status)) {
            String msg = String.valueOf(parsed.getOrDefault("message", "SMS delivery failed")).trim();
            log.warn("R2WIN_SMS_REJECTED mobile={} template={} msg={}", mask(recipient), templateKey, msg);
            throw ApiException.of(HttpStatus.BAD_GATEWAY, "SMS_PROVIDER_ERROR",
                    "Could not send OTP SMS: " + msg);
        }

        String messageId = "accepted";
        Object messageObj = parsed.get("message");
        if (messageObj instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map && !map.isEmpty()) {
                Object val = map.values().iterator().next();
                String valStr = val == null ? "" : String.valueOf(val).trim();
                String lower = valStr.toLowerCase(Locale.ROOT);
                if (lower.contains("fail") || lower.contains("wrong") || lower.contains("ncpr") || lower.contains("invalid")) {
                    log.warn("R2WIN_SMS_RECIPIENT_REJECTED mobile={} template={} reason={}",
                            mask(recipient), templateKey, valStr);
                    throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "SMS_DELIVERY_FAILED",
                            "SMS could not be delivered to this number: " + valStr);
                }
                if (!valStr.isEmpty()) {
                    messageId = valStr;
                }
            }
        }

        log.info("R2WIN_SMS_SENT mobile={} template={} messageId={}",
                mask(recipient), templateKey, messageId);
    }

    private String extractFailureMessage(String rawBody, String fallback) {
        if (rawBody == null || rawBody.isBlank()) {
            return fallback;
        }
        try {
            Map<String, Object> parsed = mapper.readValue(rawBody.trim(), new TypeReference<Map<String, Object>>() {});
            Object msg = parsed.get("message");
            if (msg != null && !String.valueOf(msg).isBlank()) {
                return String.valueOf(msg).trim();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String notBlankOrDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String mask(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return "****";
        }
        return "******" + mobile.substring(mobile.length() - 4);
    }
}

