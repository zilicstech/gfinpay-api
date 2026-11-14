package com.fintech.sms;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "mock", matchIfMissing = true)
public class MockSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(MockSmsSender.class);

    public record SentSms(String mobile, String templateKey, Map<String, String> params, Instant sentAt) {}

    private final ConcurrentHashMap<String, SentSms> lastByMobile = new ConcurrentHashMap<>();
    private final AtomicReference<SentSms> lastGlobal = new AtomicReference<>();

    @Override
    public void send(String mobile, String templateKey, Map<String, String> params) {
        String cleanMobile = mobile == null ? "" : mobile.replaceAll("\\D", "");
        SentSms sent = new SentSms(cleanMobile, templateKey, params == null ? Map.of() : Map.copyOf(params), Instant.now());
        if (!cleanMobile.isEmpty()) {
            lastByMobile.put(cleanMobile, sent);
        }
        lastGlobal.set(sent);
        String otp = params != null ? params.get("otp") : null;
        if (otp != null) {
            log.info("MOCK_SMS mobile={} template={} otp={}", mask(cleanMobile), templateKey, otp);
        } else {
            log.info("MOCK_SMS mobile={} template={} params={}", mask(cleanMobile), templateKey, params);
        }
    }

    public SentSms lastFor(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return lastGlobal.get();
        }
        String clean = mobile.replaceAll("\\D", "");
        return lastByMobile.get(clean);
    }

    public void clear() {
        lastByMobile.clear();
        lastGlobal.set(null);
    }

    private static String mask(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return "****";
        }
        return "******" + mobile.substring(mobile.length() - 4);
    }
}

