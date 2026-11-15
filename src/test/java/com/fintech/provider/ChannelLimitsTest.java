package com.fintech.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChannelLimitsTest {

    @Test
    void usesDefaultsWhenConfigIsEmpty() {
        ChannelLimits.MoneyRule cash = ChannelLimits.upiCashout("{}");
        ChannelLimits.MoneyRule aeps = ChannelLimits.aeps(null);
        assertEquals(200, cash.min());
        assertEquals(5000, cash.max());
        assertEquals(100, cash.step());
        assertEquals(100, aeps.min());
        assertEquals(50, aeps.step());
    }

    @Test
    void readsChannelOverride() {
        ChannelLimits.MoneyRule rule = ChannelLimits.upiCashout(
                "{\"upiCashout\":{\"min\":100,\"max\":5000,\"step\":100}}");
        assertEquals(100, rule.min());
        assertEquals(100, rule.step());
        assertEquals("merchant@upi", ChannelLimits.text(
                "{\"payeeVpa\":\"merchant@upi\"}", "payeeVpa", ""));
    }
}
