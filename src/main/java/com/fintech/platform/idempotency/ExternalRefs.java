package com.fintech.platform.idempotency;

import com.fintech.platform.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Builds a stable provider reference from an idempotency row so a retry cannot create a second payment. */
public final class ExternalRefs {

    private ExternalRefs() {}

    public static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.of(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header for this payment.");
        }
        String key = idempotencyKey.trim();
        if (key.length() > 200) {
            throw ApiException.of(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is too long.");
        }
        return key;
    }

    /** At most 19 characters, which is the iServeU clientRefId limit and is also safe for Emerald. */
    public static String fromKey(String prefix, UUID keyId) {
        String hex = keyId.toString().replace("-", "").toUpperCase();
        String body = prefix + hex;
        return body.length() > 19 ? body.substring(0, 19) : body;
    }
}
