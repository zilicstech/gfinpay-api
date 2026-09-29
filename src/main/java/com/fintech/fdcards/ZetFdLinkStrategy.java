package com.fintech.fdcards;

import com.fintech.platform.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ZetFdLinkStrategy implements FdLinkStrategy {

    @Override
    public boolean supports(String rail) {
        return "ZET_LINK".equals(rail);
    }

    @Override
    public FdJourneyResult build(Map<String, Object> lead, String redirectUrl) {
        String template = lead.get("apply_url") == null ? "" : String.valueOf(lead.get("apply_url"));
        if (template.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LINK_UNAVAILABLE", "This card link is not configured");
        }
        String refid = String.valueOf(lead.get("provider_refid"));
        return new FdJourneyResult(template.replace("{ref}", refid), "", "GET");
    }
}
