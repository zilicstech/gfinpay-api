package com.fintech.verification;

import com.fintech.platform.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

public interface DmtProvider {
    void execute();
}

@Component
class UnconfiguredDmtProvider implements DmtProvider {
    @Override
    public void execute() {
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_NOT_CONFIGURED",
                "DMT is not connected yet. The vendor API will be added later.");
    }
}
