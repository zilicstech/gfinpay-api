package com.fintech.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fintech.platform.web.ApiException;
import org.junit.jupiter.api.Test;

class CredentialRulesTest {

    @Test
    void validatesPasswords() {
        assertEquals("SecurePass1", CredentialRules.validatePassword("SecurePass1"));
        assertThrows(ApiException.class, () -> CredentialRules.validatePassword("short1"));
        assertThrows(ApiException.class, () -> CredentialRules.validatePassword("onlyletters"));
        assertThrows(ApiException.class, () -> CredentialRules.validatePassword("1234567890"));
    }

    @Test
    void validatesPinsAndRejectsTrivialPins() {
        assertEquals("482917", CredentialRules.validatePin("482917"));
        assertThrows(ApiException.class, () -> CredentialRules.validatePin("12345"));
        assertThrows(ApiException.class, () -> CredentialRules.validatePin("1234567"));
        assertThrows(ApiException.class, () -> CredentialRules.validatePin("12a456"));
        assertThrows(ApiException.class, () -> CredentialRules.validatePin("000000"));
        assertThrows(ApiException.class, () -> CredentialRules.validatePin("111111"));
        assertThrows(ApiException.class, () -> CredentialRules.validatePin("123456"));
        assertThrows(ApiException.class, () -> CredentialRules.validatePin("654321"));
    }
}

