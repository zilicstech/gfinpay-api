package com.fintech.fdcards;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class FdLinkRouter {

    private final List<FdLinkStrategy> strategies;

    public FdLinkRouter(List<FdLinkStrategy> strategies) {
        this.strategies = strategies;
    }

    public Optional<FdJourneyResult> tryStart(Map<String, Object> lead, String redirectUrl) {
        if (!"FD_CARD".equals(String.valueOf(lead.get("category_code")))) {
            return Optional.empty();
        }
        String rail = String.valueOf(lead.get("rail"));
        for (FdLinkStrategy strategy : strategies) {
            if (strategy.supports(rail)) {
                return Optional.of(strategy.build(lead, redirectUrl));
            }
        }
        return Optional.empty();
    }
}
