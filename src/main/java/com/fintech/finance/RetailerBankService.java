package com.fintech.finance;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bank accounts a retailer registers so earnings can be paid out to them. */
@Service
public class RetailerBankService {

    private static final String SELECT = """
            SELECT id, user_id, account_name, account_number, ifsc, bank_name, active, created_at, updated_at
              FROM retailer_bank_accounts
            """;

    private final JdbcTemplate jdbc;

    public RetailerBankService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(UUID userId) {
        return jdbc.queryForList(SELECT + " WHERE user_id = ? ORDER BY active DESC, bank_name, account_name", userId)
                .stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> create(AuthPrincipal me, String accountName, String accountNumber, String ifsc, String bankName) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO retailer_bank_accounts
                        (id, user_id, account_name, account_number, ifsc, bank_name, active)
                    VALUES (?, ?, ?, ?, ?, ?, TRUE)
                    """, id, me.userId(), accountName.trim(), accountNumber(accountNumber), ifsc(ifsc), bankName.trim());
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_ALREADY_USED",
                    "This account number is already on your profile");
        }
        return get(me.userId(), id);
    }

    @Transactional
    public Map<String, Object> update(AuthPrincipal me, UUID id, String accountName, String accountNumber,
                                      String ifsc, String bankName) {
        load(me.userId(), id);
        try {
            jdbc.update("""
                    UPDATE retailer_bank_accounts
                       SET account_name = ?, account_number = ?, ifsc = ?, bank_name = ?, updated_at = now()
                     WHERE id = ? AND user_id = ?
                    """, accountName.trim(), accountNumber(accountNumber), ifsc(ifsc), bankName.trim(), id, me.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_ALREADY_USED",
                    "This account number is already on your profile");
        }
        return get(me.userId(), id);
    }

    @Transactional
    public Map<String, Object> setActive(AuthPrincipal me, UUID id, boolean active) {
        load(me.userId(), id);
        jdbc.update("""
                UPDATE retailer_bank_accounts SET active = ?, updated_at = now() WHERE id = ? AND user_id = ?
                """, active, id, me.userId());
        return get(me.userId(), id);
    }

    private Map<String, Object> get(UUID userId, UUID id) {
        return view(load(userId, id));
    }

    private Map<String, Object> load(UUID userId, UUID id) {
        try {
            return jdbc.queryForMap(SELECT + " WHERE id = ? AND user_id = ?", id, userId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BANK_NOT_FOUND", "Bank account does not exist");
        }
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("account_name", row.get("account_name"));
        out.put("account_number", row.get("account_number"));
        out.put("ifsc", row.get("ifsc"));
        out.put("bank_name", row.get("bank_name"));
        out.put("active", row.get("active"));
        out.put("created_at", row.get("created_at"));
        out.put("updated_at", row.get("updated_at"));
        return out;
    }

    private static String accountNumber(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() < 9 || digits.length() > 18) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ACCOUNT",
                    "Account number should be 9 to 18 digits");
        }
        return digits;
    }

    private static String ifsc(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z]{4}0[A-Z0-9]{6}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IFSC",
                    "IFSC should look like HDFC0001234");
        }
        return value;
    }
}
