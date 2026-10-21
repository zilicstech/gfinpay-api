package com.fintech.dmt;

import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.PageWindow;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DmtBankListService {

    private static final Logger log = LoggerFactory.getLogger(DmtBankListService.class);

    private final JdbcTemplate jdbc;

    public DmtBankListService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> list(String query, Boolean active, Integer limit, Integer offset) {
        String q = query == null ? "" : query.trim();
        String like = "%" + q + "%";
        String flag = active == null ? "" : (active ? "true" : "false");
        int size = PageWindow.limit(limit);
        int skip = PageWindow.offset(offset);
        Integer total = jdbc.queryForObject("""
                SELECT count(*)::int FROM dmt_bank_list
                 WHERE (? = '' OR bank_id ILIKE ? OR bank_name ILIKE ?)
                   AND (? = '' OR active = (? = 'true'))
                """, Integer.class, q, like, like, flag, flag);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT id, bank_id, bank_name, active, created_at, updated_at
                  FROM dmt_bank_list
                 WHERE (? = '' OR bank_id ILIKE ? OR bank_name ILIKE ?)
                   AND (? = '' OR active = (? = 'true'))
                 ORDER BY bank_name
                 LIMIT ? OFFSET ?
                """, q, like, like, flag, flag, size, skip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total == null ? 0 : total);
        return out;
    }

    /** Active banks for the retailer desk. A blank query returns the first page in name order. */
    public List<Map<String, Object>> active(String query, Integer limit) {
        String q = query == null ? "" : query.trim();
        String like = "%" + q + "%";
        return jdbc.queryForList("""
                SELECT bank_id, bank_name
                  FROM dmt_bank_list
                 WHERE active
                   AND (? = '' OR bank_id ILIKE ? OR bank_name ILIKE ?)
                 ORDER BY bank_name
                 LIMIT ?
                """, q, like, like, Math.min(PageWindow.limit(limit), 30));
    }

    @Transactional
    public Map<String, Object> create(String bankId, String bankName) {
        String id = cleanId(bankId);
        String name = cleanName(bankName);
        try {
            UUID rowId = jdbc.queryForObject("""
                    INSERT INTO dmt_bank_list (bank_id, bank_name, active)
                    VALUES (?, ?, TRUE)
                    RETURNING id
                    """, UUID.class, id, name);
            log.info("DMT_BANK created bankId={}", id);
            return one(rowId);
        } catch (DuplicateKeyException e) {
            throw ApiException.of(HttpStatus.CONFLICT, "BANK_EXISTS", "This bank id is already in the list.");
        }
    }

    @Transactional
    public Map<String, Object> setActive(UUID id, boolean active) {
        int n = jdbc.update("""
                UPDATE dmt_bank_list SET active = ?, updated_at = now() WHERE id = ?
                """, active, id);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BANK_NOT_FOUND", "That bank is not in the list.");
        }
        log.info("DMT_BANK active id={} active={}", id, active);
        return one(id);
    }

    @Transactional
    public Map<String, Object> upload(MultipartFile file) {
        List<DmtBankWorkbook.RowData> rows = DmtBankWorkbook.read(file);
        Set<String> known = new HashSet<>(jdbc.queryForList("SELECT bank_id FROM dmt_bank_list", String.class));
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        for (DmtBankWorkbook.RowData row : rows) {
            String id;
            String name;
            try {
                id = cleanId(row.bankId());
                name = cleanName(row.bankName());
            } catch (ApiException e) {
                skipped++;
                continue;
            }
            jdbc.update("""
                    INSERT INTO dmt_bank_list (bank_id, bank_name, active)
                    VALUES (?, ?, TRUE)
                    ON CONFLICT (bank_id) DO UPDATE
                       SET bank_name = EXCLUDED.bank_name,
                           updated_at = now()
                    """, id, name);
            if (known.add(id)) {
                inserted++;
            } else {
                updated++;
            }
        }
        log.info("DMT_BANK upload inserted={} updated={} skipped={}", inserted, updated, skipped);
        return Map.of("inserted", inserted, "updated", updated, "skipped", skipped);
    }

    private Map<String, Object> one(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, bank_id, bank_name, active, created_at, updated_at
                  FROM dmt_bank_list WHERE id = ?
                """, id);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "BANK_NOT_FOUND", "That bank is not in the list.");
        }
        return rows.get(0);
    }

    private static String cleanId(String bankId) {
        String id = bankId == null ? "" : bankId.trim();
        if (id.isBlank() || id.length() > 40) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BANK_ID", "Enter a bank id.");
        }
        return id;
    }

    private static String cleanName(String bankName) {
        String name = bankName == null ? "" : bankName.trim().replaceAll("\\s+", " ");
        if (name.isBlank() || name.length() > 200) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BANK_NAME", "Enter a bank name.");
        }
        return name;
    }
}
