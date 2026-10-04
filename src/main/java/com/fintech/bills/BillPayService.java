package com.fintech.bills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.commission.CommissionEngine;
import com.fintech.ledger.ServiceSettlement;
import com.fintech.ledger.TransactionService;
import com.fintech.ledger.WalletService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.idempotency.IdempotencyService;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillPayService {

    private static final Logger log = LoggerFactory.getLogger(BillPayService.class);

    public record PayResult(boolean replay, Map<String, Object> body) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KbillClient kbill;
    private final PlatformServiceGate services;
    private final IdempotencyService idempotencyService;
    private final ServiceSettlement settlement;
    private final TransactionService transactionService;
    private final WalletService walletService;
    private final CommissionEngine commissionEngine;

    public BillPayService(JdbcTemplate jdbc, ObjectMapper mapper, KbillClient kbill,
                          PlatformServiceGate services,
                          IdempotencyService idempotencyService, ServiceSettlement settlement,
                          TransactionService transactionService, WalletService walletService,
                          CommissionEngine commissionEngine) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.kbill = kbill;
        this.services = services;
        this.idempotencyService = idempotencyService;
        this.settlement = settlement;
        this.transactionService = transactionService;
        this.walletService = walletService;
        this.commissionEngine = commissionEngine;
    }

    public List<Map<String, Object>> categories() {
        services.requireEnabled(PlatformServiceGate.BBPS);
        JsonNode data = kbill.categories().path("data");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode row : data) {
            int id = row.path("operator_category_id").asInt();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("name", row.path("operator_category_name").asText());
            item.put("locationRequired", KbillRails.locationRequired(id));
            item.put("payEnabled", KbillRails.payEnabled(id));
            out.add(item);
        }
        return out;
    }

    public List<Map<String, Object>> locations() {
        services.requireEnabled(PlatformServiceGate.BBPS);
        JsonNode data = kbill.locations().path("data");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode row : data) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", parseLocationId(row.path("operator_location_id").asText()));
            item.put("name", row.path("operator_location_name").asText());
            item.put("abbreviation", row.path("abbreviation").asText(""));
            out.add(item);
        }
        return out;
    }

    public List<Map<String, Object>> operators(int categoryId, Integer locationId) {
        services.requireEnabled(PlatformServiceGate.BBPS);
        if (KbillRails.locationRequired(categoryId) && locationId == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LOCATION_REQUIRED",
                    "Pick a state for electricity bills");
        }
        JsonNode data = kbill.operators(categoryId, locationId).path("data");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode row : data) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.path("operator_id").asInt());
            item.put("name", row.path("name").asText());
            item.put("categoryId", row.path("operator_category").asInt(categoryId));
            item.put("locationId", row.path("location_id").asInt());
            item.put("kycRequired", row.path("kyc_required").asInt() == 1);
            out.add(item);
        }
        return out;
    }

    public List<Map<String, Object>> parameters(int operatorId, int categoryId) {
        services.requireEnabled(PlatformServiceGate.BBPS);
        JsonNode data = kbill.parameters(operatorId, categoryId).path("data");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode row : data) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.path("param_name").asText("utility_acc_no"));
            item.put("label", row.path("param_label").asText("Account number"));
            item.put("regex", blankToNull(row.path("regex").asText(null)));
            item.put("type", row.path("param_type").asText("Numeric"));
            item.put("errorMessage", blankToNull(row.path("error_message").asText(null)));
            out.add(item);
        }
        if (out.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("name", "utility_acc_no");
            fallback.put("label", categoryId == KbillRails.CREDIT_CARD ? "Card number" : "Account number");
            fallback.put("regex", null);
            fallback.put("type", "Numeric");
            fallback.put("errorMessage", null);
            out.add(fallback);
        }
        return out;
    }

    public Map<String, Object> fetch(int categoryId, int operatorId, String consumerNumber,
                                     String customerMobile, String senderName,
                                     Map<String, String> extraParams) {
        services.requireEnabled(PlatformServiceGate.BBPS);
        String consumer = requireConsumer(consumerNumber);
        String mobile = digits10(customerMobile);
        String name = senderName == null || senderName.isBlank() ? "Customer" : senderName.trim();
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("operator_id", operatorId);
        query.put("utility_acc_no", consumer);
        query.put("category", categoryId);
        query.put("client_ref_id", "GFIN-F-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        query.put("mobile_no", mobile);
        query.put("sender_name", name);
        if (extraParams != null) {
            extraParams.forEach((k, v) -> {
                if (k != null && !"utility_acc_no".equals(k) && v != null && !v.isBlank()) {
                    query.put(k, v.trim());
                }
            });
        }
        JsonNode root = kbill.fetchBill(query);
        JsonNode data = root.path("data");
        BigDecimal amount = parseAmount(data.path("amount").asText(null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operatorId", operatorId);
        out.put("categoryId", categoryId);
        out.put("consumerNumber", consumer);
        out.put("customerName", data.path("utilitycustomername").asText(""));
        out.put("amount", amount);
        out.put("dueDate", isoDue(data.path("billDueDate").asText(null)));
        out.put("billFetchResponse", data.path("billfetchresponse").asText(""));
        out.put("customerId", data.path("customer_id").asText(consumer));
        out.put("payEnabled", KbillRails.payEnabled(categoryId) && amount != null);
        out.put("raw", mapper.convertValue(data, new TypeReference<Map<String, Object>>() {}));
        log.info("BILL_FETCH operator={} category={} last4={}", operatorId, categoryId, last4(consumer));
        return out;
    }

    @Transactional
    public PayResult pay(UUID agentId, String idemKey, int categoryId, int operatorId, String consumerNumber,
                         BigDecimal amount, String customerName, String customerMobile,
                         Map<String, Object> billFetch, Map<String, String> extraParams, String canonical) {
        long startMs = System.currentTimeMillis();
        log.info("BILL_PAY started agent={} operator={} category={}", agentId, operatorId, categoryId);
        try {
            IdempotencyService.Claim claim = idempotencyService.claim(agentId, idemKey, canonical);
            if (claim.replay()) {
                Map<String, Object> stored = mapper.readValue(claim.responseBody(), new TypeReference<>() {});
                return new PayResult(true, stored);
            }
            services.requireEnabled(PlatformServiceGate.BBPS);
            if (!KbillRails.payEnabled(categoryId)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CATEGORY_PAY_UNAVAILABLE",
                        "Payment is not available for this bill type yet");
            }
            String consumer = requireConsumer(consumerNumber);
            if (amount == null || amount.compareTo(BigDecimal.ONE) < 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Enter at least ₹1");
            }
            if (categoryId == KbillRails.CREDIT_CARD && amount.compareTo(new BigDecimal("100")) < 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT",
                        "Credit card payments must be at least ₹100");
            }
            if (billFetch == null || billFetch.isEmpty()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BILL_FETCH_REQUIRED",
                        "Fetch the bill before paying");
            }
            BigDecimal fetched = parseAmount(String.valueOf(billFetch.getOrDefault("amount", "")));
            if (fetched != null && fetched.compareTo(amount) != 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BILL_AMOUNT_MISMATCH",
                        "Amount does not match the fetched bill");
            }
            String name = customerName == null ? "" : customerName.trim();
            String mobile = digits10(customerMobile);

            CommissionEngine.Resolution pricing = commissionEngine.resolve(agentId, "BBPS", amount);
            BigDecimal total = amount.add(pricing.fee());
            WalletService.WalletInfo wallet = walletService.getByUser(agentId);
            if (wallet.available().compareTo(total) < 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_WALLET_BALANCE",
                        "Wallet balance is less than amount plus fee",
                        Map.of("required", total, "available", wallet.available()), false);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("operator_id", operatorId);
            body.put("utility_acc_no", consumer);
            body.put("category", categoryId);
            body.put("client_ref_id", "GFIN-P-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            body.put("mobile_no", mobile);
            body.put("sender_name", name.isBlank() ? "Customer" : name);
            body.put("amount", amount);
            if (categoryId == KbillRails.CREDIT_CARD) {
                String card = extraValue(extraParams, "card_number");
                body.put("card_number", card == null ? consumer : card);
            }
            if (extraParams != null) {
                extraParams.forEach((k, v) -> {
                    if (k != null && v != null && !v.isBlank()
                            && !body.containsKey(k) && !"utility_acc_no".equals(k) && !"card_number".equals(k)) {
                        body.put(k, v.trim());
                    }
                });
            }

            JsonNode paid = kbill.pay(categoryId, body);
            String partnerRef = firstNonBlank(
                    paid.path("transaction_id").asText(null),
                    paid.path("data").path("transaction_id").asText(null),
                    String.valueOf(body.get("client_ref_id")));

            ServiceSettlement.Result settled;
            try {
                settled = settlement.debitAndSettle(agentId, "BBPS", amount, "KBILL", partnerRef, claim.keyId());
            } catch (RuntimeException e) {
                log.error("BILL_PAY wallet settle failed after partner success partnerRef={}", partnerRef, e);
                throw e;
            }

            String fetchJson = mapper.writeValueAsString(billFetch);
            LocalDate due = parseDue(billFetch.get("dueDate"));
            jdbc.update("""
                    INSERT INTO bill_payments
                        (id, transaction_id, operator_id, category_id, consumer_number, customer_mobile, customer_name,
                         bill_amount, due_date, bill_fetch, partner_txn_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, UUID.randomUUID(), settled.transactionId(), operatorId, categoryId, consumer, mobile,
                    name.isBlank() ? null : name, amount, due, fetchJson, partnerRef);
            transactionService.setCustomerRef(settled.transactionId(), mapper.writeValueAsString(Map.of(
                    "name", name, "mobile", mobile, "consumerLast4", last4(consumer),
                    "operatorId", operatorId, "categoryId", categoryId)));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("transactionId", settled.transactionId());
            response.put("type", "BBPS");
            response.put("state", settled.state());
            response.put("amount", settled.amount());
            response.put("fee", settled.fee());
            response.put("totalDebited", settled.total());
            response.put("walletBalanceAfter", settled.walletBalanceAfter());
            response.put("partnerRef", settled.partnerRef());
            response.put("consumerLast4", last4(consumer));
            response.put("statusPollUrl", "/api/v1/transactions/" + settled.transactionId());
            idempotencyService.complete(claim.keyId(), 200, mapper.writeValueAsString(response));
            log.info("BILL_PAY success txnId={} partnerRef={}", settled.transactionId(), partnerRef);
            return new PayResult(false, response);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("BILL_PAY failed agent={} reason={}", agentId, e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            log.info("BILL_PAY completed in {} ms", System.currentTimeMillis() - startMs);
        }
    }

    private static String requireConsumer(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CONSUMER", "Enter the account number");
        }
        return value;
    }

    private static String digits10(String raw) {
        String mobile = raw == null ? "" : raw.replaceAll("\\D", "");
        if (mobile.length() != 10) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOMER_REQUIRED",
                    "Record a 10-digit mobile for the receipt");
        }
        return mobile;
    }

    private static Integer parseLocationId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String isoDue(String raw) {
        LocalDate date = parseDue(raw);
        return date == null ? null : date.toString();
    }

    private static LocalDate parseDue(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            if (text.length() == 8 && text.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String extraValue(Map<String, String> extra, String key) {
        if (extra == null) {
            return null;
        }
        String value = extra.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return UUID.randomUUID().toString();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    private static String last4(String value) {
        if (value == null || value.length() < 4) {
            return "••••";
        }
        return value.substring(value.length() - 4);
    }
}
