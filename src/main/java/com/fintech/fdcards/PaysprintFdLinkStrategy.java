package com.fintech.fdcards;

import com.fintech.paysprint.PaysprintFdClient;
import com.fintech.platform.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PaysprintFdLinkStrategy implements FdLinkStrategy {

    private final PaysprintFdClient fdClient;

    public PaysprintFdLinkStrategy(PaysprintFdClient fdClient) {
        this.fdClient = fdClient;
    }

    @Override
    public boolean supports(String rail) {
        return "PAYSPRINT_FD".equals(rail);
    }

    @Override
    public FdJourneyResult build(Map<String, Object> lead, String redirectUrl) {
        String refid = String.valueOf(lead.get("provider_refid"));
        String merchantCode = merchantCode(lead);
        PaysprintFdClient.GenerateUrlResult result = fdClient.generateUrl(refid, merchantCode);
        return new FdJourneyResult(result.url(), result.encdata(), "GET");
    }

    static String merchantCode(Map<String, Object> lead) {
        String retailer = text(lead.get("retailer_code"));
        if (!retailer.isBlank()) {
            return retailer;
        }
        String distributor = text(lead.get("distributor_code"));
        if (!distributor.isBlank()) {
            return distributor;
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "MERCHANT_CODE_MISSING",
                "This outlet does not have a GFIN code to send to PaySprint");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
