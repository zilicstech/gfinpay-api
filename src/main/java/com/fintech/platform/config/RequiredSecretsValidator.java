package com.fintech.platform.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RequiredSecretsValidator {

    private static final String DEV_PII_KEY = "dev-pii-key-32-bytes-minimum-key!";

    private final String jwtSecret;
    private final String surepassMode;
    private final String surepassToken;
    private final String piiKey;
    private final Environment env;

    public RequiredSecretsValidator(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.surepass.mode:live}") String surepassMode,
            @Value("${app.surepass.api-token:}") String surepassToken,
            @Value("${app.pii.key:}") String piiKey,
            Environment env) {
        this.jwtSecret = jwtSecret;
        this.surepassMode = surepassMode;
        this.surepassToken = surepassToken;
        this.piiKey = piiKey;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException("APP_JWT_SECRET must be set");
        }
        boolean prod = Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
        if (prod && "mock".equalsIgnoreCase(surepassMode)) {
            throw new IllegalStateException("app.surepass.mode cannot be 'mock' in production profile");
        }
        if (!"mock".equalsIgnoreCase(surepassMode) && !StringUtils.hasText(surepassToken)) {
            throw new IllegalStateException("APP_SUREPASS_TOKEN must be set when app.surepass.mode=" + surepassMode);
        }
        if (!StringUtils.hasText(piiKey) || (prod && DEV_PII_KEY.equals(piiKey))) {
            throw new IllegalStateException("APP_PII_KEY must be set to a non-default secret in production");
        }
    }
}
