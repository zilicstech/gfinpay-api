package com.fintech.platform.config;

import com.fintech.paysprint.PaysprintProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RequiredSecretsValidator {

    private final String jwtSecret;
    private final PaysprintProperties paysprint;

    public RequiredSecretsValidator(@Value("${app.jwt.secret}") String jwtSecret,
                                    PaysprintProperties paysprint) {
        this.jwtSecret = jwtSecret;
        this.paysprint = paysprint;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException("APP_JWT_SECRET must be set");
        }
        if (!StringUtils.hasText(paysprint.jwtKey())) {
            throw new IllegalStateException("PAYSPRINT_JWT_KEY must be set");
        }
        if (!StringUtils.hasText(paysprint.authorisedKey())) {
            throw new IllegalStateException("PAYSPRINT_AUTHORISED_KEY must be set");
        }
        if (!StringUtils.hasText(paysprint.aesKey()) || !StringUtils.hasText(paysprint.aesIv())) {
            throw new IllegalStateException("PAYSPRINT_AES_KEY and PAYSPRINT_AES_IV must be set");
        }
    }
}
