package com.fintech.bills;

import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BillCatalog {

    public static final List<Map<String, String>> BBPS_CATEGORIES = List.of(
            Map.of("code", "ELECTRICITY", "label", "Electricity", "hint", "Power board CA / RR number"),
            Map.of("code", "WATER", "label", "Water", "hint", "Jal board / municipal water"),
            Map.of("code", "GAS", "label", "Piped gas", "hint", "IGL, MGL and other city gas"),
            Map.of("code", "BROADBAND", "label", "Broadband", "hint", "Fibre and landline bills"),
            Map.of("code", "LPG", "label", "LPG cylinder", "hint", "Bharat Gas, HP, Indane"),
            Map.of("code", "CREDIT_CARD", "label", "Credit card", "hint", "Card outstanding"),
            Map.of("code", "LOAN", "label", "Loan EMI", "hint", "NBFC and bank loan accounts"),
            Map.of("code", "INSURANCE", "label", "General insurance", "hint", "Non-LIC premiums"),
            Map.of("code", "EDUCATION", "label", "Education", "hint", "School and college fees"),
            Map.of("code", "MUNICIPAL", "label", "Municipal / tax", "hint", "Property tax and civic dues")
    );

    private final JdbcTemplate jdbc;

    public BillCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> operators(String category) {
        String cat = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        return jdbc.queryForList("""
                SELECT id, partner_id, name, category, txn_type::text AS txn_type,
                       view_bill, consumer_label, regex
                  FROM bill_operators
                 WHERE enabled = TRUE AND category = ?
                 ORDER BY name
                """, cat);
    }

    public Map<String, Object> requireOperator(int operatorId) {
        var rows = jdbc.queryForList("""
                SELECT id, partner_id, name, category, txn_type::text AS txn_type,
                       view_bill, consumer_label, regex
                  FROM bill_operators WHERE id = ? AND enabled = TRUE
                """, operatorId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "OPERATOR_NOT_FOUND", "This biller is not available");
        }
        return rows.get(0);
    }

    public String normalizeConsumer(Map<String, Object> operator, String raw) {
        String value = raw == null ? "" : raw.trim();
        String category = String.valueOf(operator.get("category"));
        if ("FASTAG".equals(category)) {
            value = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        } else if ("PREPAID".equals(category)) {
            value = value.replaceAll("\\D", "");
        } else {
            value = value.replaceAll("\\s+", "");
        }
        String regex = (String) operator.get("regex");
        if (regex != null && !regex.isBlank() && !value.matches(regex)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CONSUMER",
                    "Enter a valid " + operator.get("consumer_label"));
        }
        if (value.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CONSUMER",
                    "Enter the " + operator.get("consumer_label"));
        }
        return value;
    }

    public Map<String, Object> mockFetch(Map<String, Object> operator, String consumer) {
        int seed = Math.abs(consumer.hashCode());
        BigDecimal amount = BigDecimal.valueOf(120 + (seed % 4880)).setScale(2, RoundingMode.HALF_UP);
        if ("LIC".equals(operator.get("category"))) {
            amount = BigDecimal.valueOf(1548 + (seed % 3500)).setScale(2, RoundingMode.HALF_UP);
        }
        if ("FASTAG".equals(operator.get("category"))) {
            amount = BigDecimal.valueOf(200 + (seed % 800)).setScale(2, RoundingMode.HALF_UP);
        }
        String due = LocalDate.now().plusDays(5 + (seed % 10)).format(DateTimeFormatter.ISO_DATE);
        String billNumber = "BL" + Integer.toHexString(seed).toUpperCase(Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operatorId", operator.get("id"));
        out.put("operatorName", operator.get("name"));
        out.put("category", operator.get("category"));
        out.put("consumerNumber", consumer);
        out.put("customerName", "Walk-in customer");
        out.put("billAmount", amount);
        out.put("billnetamount", amount);
        out.put("dueDate", due);
        out.put("billNumber", billNumber);
        out.put("billdate", LocalDate.now().toString());
        out.put("acceptPayment", true);
        out.put("acceptPartPay", false);
        return out;
    }

    public List<Map<String, Object>> plans(int operatorId) {
        Map<String, Object> operator = requireOperator(operatorId);
        String category = String.valueOf(operator.get("category"));
        if ("PREPAID".equals(category)) {
            return List.of(
                    plan("Unlimited 28 days", new BigDecimal("199.00")),
                    plan("Unlimited 56 days", new BigDecimal("349.00")),
                    plan("Unlimited 84 days", new BigDecimal("579.00")),
                    plan("Talktime 100", new BigDecimal("100.00")),
                    plan("Talktime 500", new BigDecimal("500.00"))
            );
        }
        if ("DTH".equals(category)) {
            return List.of(
                    plan("Hindi Super 30 days", new BigDecimal("249.00")),
                    plan("Sports 30 days", new BigDecimal("399.00")),
                    plan("All-in-one 90 days", new BigDecimal("799.00")),
                    plan("Add-on 100", new BigDecimal("100.00"))
            );
        }
        return List.of();
    }

    private static Map<String, Object> plan(String name, BigDecimal amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("amount", amount);
        return row;
    }
}
