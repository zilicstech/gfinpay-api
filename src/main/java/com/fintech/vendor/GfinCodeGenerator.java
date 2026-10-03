package com.fintech.vendor;

import com.fintech.platform.web.ApiException;
import java.security.SecureRandom;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class GfinCodeGenerator {

    private static final String PREFIX = "GFIN";
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public GfinCodeGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextCode(String errorCode, String message) {
        for (int attempt = 0; attempt < 32; attempt++) {
            StringBuilder suffix = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                suffix.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
            }
            String code = PREFIX + suffix;
            if (available(code)) {
                return code;
            }
        }
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }

    public boolean available(String code) {
        int hubs = jdbc.queryForObject("SELECT COUNT(*)::int FROM hubs WHERE code = ?", Integer.class, code);
        int users = jdbc.queryForObject("SELECT COUNT(*)::int FROM users WHERE code = ?", Integer.class, code);
        int vendors = jdbc.queryForObject("SELECT COUNT(*)::int FROM vendors WHERE code = ?", Integer.class, code);
        int affiliates = jdbc.queryForObject(
                "SELECT COUNT(*)::int FROM vendor_affiliates WHERE gfin_code = ?", Integer.class, code);
        return hubs == 0 && users == 0 && vendors == 0 && affiliates == 0;
    }
}
