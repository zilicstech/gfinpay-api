package com.fintech.fdcards;

import com.fintech.platform.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GrowmoreFdLinkStrategy implements FdLinkStrategy {

    @Override
    public boolean supports(String rail) {
        return "GROWMORE_LINK".equals(rail);
    }

    @Override
    public FdJourneyResult build(Map<String, Object> lead, String redirectUrl) {
        String template = lead.get("apply_url") == null ? "" : String.valueOf(lead.get("apply_url"));
        if (template.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LINK_UNAVAILABLE", "GrowMore link is not configured");
        }
        String refid = String.valueOf(lead.get("provider_refid"));
        String url = template.contains("{ref}") ? template.replace("{ref}", refid) : template;
        return new FdJourneyResult(url, "", "GET");
    }
}
