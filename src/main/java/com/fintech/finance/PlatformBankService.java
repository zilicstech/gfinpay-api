package com.fintech.finance;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import com.fintech.storage.SpacesStorage;
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
import org.springframework.web.multipart.MultipartFile;

/** Platform collection accounts shown to retailers for wallet and activation transfers. */
@Service
public class PlatformBankService {

    private static final String SELECT = """
            SELECT id, account_name, account_number, ifsc, bank_name, upi_id,
                   qr_key, qr_filename, qr_content_type, active, created_at, updated_at
              FROM platform_bank_accounts
            """;

    private final JdbcTemplate jdbc;
    private final SpacesStorage spaces;

    public PlatformBankService(JdbcTemplate jdbc, SpacesStorage spaces) {
        this.jdbc = jdbc;
        this.spaces = spaces;
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList(SELECT + " ORDER BY active DESC, bank_name, account_name")
                .stream().map(this::view).toList();
    }

    public List<Map<String, Object>> listActive() {
        return jdbc.queryForList(SELECT + " WHERE active = TRUE ORDER BY bank_name, account_name")
                .stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> create(AuthPrincipal me, String accountName, String accountNumber, String ifsc,
                                      String bankName, String upiId, boolean active, MultipartFile qr) {
        UUID id = UUID.randomUUID();
        Qr stored = storeQr(qr);
        try {
            jdbc.update("""
                    INSERT INTO platform_bank_accounts
                        (id, account_name, account_number, ifsc, bank_name, upi_id,
                         qr_key, qr_filename, qr_content_type, active, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, accountName.trim(), accountNumber(accountNumber), ifsc(ifsc), bankName.trim(),
                    upi(upiId), stored.key(), stored.filename(), stored.contentType(), active, me.userId());
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(UUID id, String accountName, String accountNumber, String ifsc,
                                      String bankName, String upiId, MultipartFile qr) {
        Map<String, Object> existing = load(id);
        Qr stored = qr == null || qr.isEmpty()
                ? new Qr(text(existing.get("qr_key")), text(existing.get("qr_filename")), text(existing.get("qr_content_type")))
                : storeQr(qr);
        try {
            jdbc.update("""
                    UPDATE platform_bank_accounts
                       SET account_name = ?, account_number = ?, ifsc = ?, bank_name = ?, upi_id = ?,
                           qr_key = ?, qr_filename = ?, qr_content_type = ?, updated_at = now()
                     WHERE id = ?
                    """, accountName.trim(), accountNumber(accountNumber), ifsc(ifsc), bankName.trim(), upi(upiId),
                    blankToNull(stored.key()), blankToNull(stored.filename()), blankToNull(stored.contentType()), id);
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> setActive(UUID id, boolean active) {
        load(id);
        jdbc.update("UPDATE platform_bank_accounts SET active = ?, updated_at = now() WHERE id = ?", active, id);
        return get(id);
    }

    private Map<String, Object> get(UUID id) {
        return view(load(id));
    }

    private Map<String, Object> load(UUID id) {
        try {
            return jdbc.queryForMap(SELECT + " WHERE id = ?", id);
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
        out.put("upi_id", row.get("upi_id"));
        out.put("active", row.get("active"));
        out.put("qr_filename", row.get("qr_filename"));
        String key = text(row.get("qr_key"));
        out.put("qr_url", key.isBlank() ? null : qrUrl(key));
        out.put("created_at", row.get("created_at"));
        out.put("updated_at", row.get("updated_at"));
        return out;
    }

    private String qrUrl(String key) {
        try {
            return spaces.presignedGet(key);
        } catch (ApiException e) {
            return null;
        }
    }

    private Qr storeQr(MultipartFile qr) {
        if (qr == null || qr.isEmpty()) {
            return new Qr(null, null, null);
        }
        String type = qr.getContentType() == null ? "" : qr.getContentType().toLowerCase(Locale.ROOT);
        if (!type.startsWith("image/")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "QR_TYPE", "Upload the QR as a PNG, JPG, or WebP image");
        }
        SpacesStorage.StoredObject stored = spaces.upload("bank-qr", qr);
        return new Qr(stored.key(), stored.filename(), stored.contentType());
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

    private static String upi(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9._-]{2,}@[a-z]{2,}")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_UPI", "Enter a UPI id like name@bank");
        }
        return value;
    }

    private static ApiException duplicate(DuplicateKeyException e) {
        String message = e.getMostSpecificCause().getMessage();
        if (message != null && message.contains("upi")) {
            return ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UPI_ALREADY_USED", "This UPI id is already on another account");
        }
        return ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_ALREADY_USED", "This account number is already added");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record Qr(String key, String filename, String contentType) {}
}
