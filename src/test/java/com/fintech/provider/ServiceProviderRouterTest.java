package com.fintech.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fintech.platform.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceProviderRouterTest {

    @Test
    void upiUsesTheFirstRetailerPriorityAndSkipsRoundRobin() {
        List<Map<String, Object>> ordered = List.of(Map.of("name", "priority-1"), Map.of("name", "priority-2"));
        Map<String, Object> chosen = ServiceProviderRouter.choose(
                ServiceProviderRouter.UPI_CASHOUT, ordered, "ROUND_ROBIN", 1);
        assertEquals("priority-1", chosen.get("name"));
    }

    @Test
    void disabledPriorityOneIsSkippedWhenTheListStartsAtTwo() {
        List<Map<String, Object>> ordered = List.of(Map.of("name", "priority-2"));
        Map<String, Object> chosen = ServiceProviderRouter.choose(
                ServiceProviderRouter.PAYOUT, ordered, "PRIMARY_FALLBACK", 0);
        assertEquals("priority-2", chosen.get("name"));
    }

    @Test
    void aepsRoundRobinStillRotates() {
        List<Map<String, Object>> ordered = List.of(Map.of("name", "a"), Map.of("name", "b"));
        Map<String, Object> chosen = ServiceProviderRouter.choose(ServiceProviderRouter.AEPS, ordered, "ROUND_ROBIN", 1);
        assertEquals("b", chosen.get("name"));
    }

    @Test
    void iserveuWithoutBiometricIsEligibleAndEmeraldPendingIsNot() {
        assertTrue(ServiceProviderRouter.kycEligible(false, "NOT_REQUIRED"));
        assertTrue(ServiceProviderRouter.kycEligible(true, "FULL_KYC"));
        assertFalse(ServiceProviderRouter.kycEligible(true, "PENDING_BIOMETRIC"));
        assertFalse(ServiceProviderRouter.kycEligible(false, "PENDING_BIOMETRIC"));
    }

    @Test
    void duplicateEnabledPriorityIsRejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> ProviderOnboardingService.requireRetailerPriority(1, true, true));
        assertEquals("PRIORITY_TAKEN", ex.getCode());
        assertThrows(ApiException.class, () -> ProviderOnboardingService.requireRetailerPriority(null, true, false));
        assertThrows(ApiException.class, () -> ProviderOnboardingService.requireRetailerPriority(4, false, false));
    }
}
