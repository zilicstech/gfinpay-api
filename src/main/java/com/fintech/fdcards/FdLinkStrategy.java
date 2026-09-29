package com.fintech.fdcards;

import java.util.Map;

public interface FdLinkStrategy {
    boolean supports(String rail);

    FdJourneyResult build(Map<String, Object> lead, String redirectUrl);
}
