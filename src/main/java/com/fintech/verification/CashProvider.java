package com.fintech.verification;

import com.fintech.platform.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

public interface CashProvider {
    void start(String mode);
}

@Component
class UnconfiguredCashProvider implements CashProvider {
    @Override
    public void start(String mode) {
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_NOT_CONFIGURED",
                "Cash " + mode + " is not connected yet. The vendor API will be added later.");
    }
}
