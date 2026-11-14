package com.fintech.onboarding;

import com.fintech.platform.web.ApiException;
import java.util.Set;
import org.springframework.http.HttpStatus;

public final class CredentialRules {

    private static final Set<String> TRIVIAL_PINS = Set.of(
            "000000", "111111", "222222", "333333", "444444",
            "555555", "666666", "777777", "888888", "999999",
            "123456", "654321", "012345", "543210", "112233",
            "121212", "123123"
    );

    private CredentialRules() {}

    public static String validatePassword(String raw) {
        String pwd = raw == null ? "" : raw;
        if (pwd.length() < 8) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PASSWORD",
                    "Password must be at least 8 characters long");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < pwd.length(); i++) {
            char c = pwd.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PASSWORD",
                    "Password must include at least one letter and one digit");
        }
        return pwd;
    }

    public static String validatePin(String raw) {
        String pin = raw == null ? "" : raw.trim();
        if (!pin.matches("\\d{6}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PIN",
                    "PIN must be a 6-digit number");
        }
        if (TRIVIAL_PINS.contains(pin)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PIN",
                    "Choose a less predictable 6-digit PIN (avoid 123456 or repeated digits)");
        }
        return pin;
    }
}

