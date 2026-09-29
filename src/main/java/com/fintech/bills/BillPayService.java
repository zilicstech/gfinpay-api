package com.fintech.bills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.ledger.ServiceSettlement;
import com.fintech.ledger.TransactionService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.idempotency.IdempotencyService;
import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.FeatureUnavailable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Set<String> BILL_TYPES = Set.of("BBPS");

    public record PayResult(boolean replay, Map<String, Object> body) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BillCatalog catalog;
    private final PlatformServiceGate services;
    private final IdempotencyService idempotencyService;
    private final ServiceSettlement settlement;
    private final TransactionService transactionService;

    public BillPayService(JdbcTemplate jdbc, ObjectMapper mapper, BillCatalog catalog,
                          PlatformServiceGate services,
                          IdempotencyService idempotencyService, ServiceSettlement settlement,
                          TransactionService transactionService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.catalog = catalog;
        this.services = services;
        this.idempotencyService = idempotencyService;
        this.settlement = settlement;
        this.transactionService = transactionService;
    }

    public List<Map<String, String>> categories() {
        services.requireEnabled(PlatformServiceGate.BBPS);
        return BillCatalog.BBPS_CATEGORIES;
    }

    public List<Map<String, Object>> operators(String category) {
        services.requireEnabled(PlatformServiceGate.BBPS);
        return catalog.operators(category);
    }

    public Map<String, Object> fetch(int operatorId, String consumerNumber) {
        FeatureUnavailable.throwIfCalled();
        long startMs = System.currentTimeMillis();
        Map<String, Object> operator = catalog.requireOperator(operatorId);
        String txnType = String.valueOf(operator.get("txn_type"));
        services.requireEnabled(PlatformServiceGate.BBPS);
        String consumer = catalog.normalizeConsumer(operator, consumerNumber);
        if (Boolean.FALSE.equals(operator.get("view_bill"))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FETCH_NOT_REQUIRED",
                    "This biller does not need a bill fetch — collect the amount and pay");
        }
        Map<String, Object> bill = catalog.mockFetch(operator, consumer);
        log.info("BILL_FETCH operator={} consumerLast={} in {} ms",
                operator.get("name"), last4(consumer), System.currentTimeMillis() - startMs);
        return bill;
    }

    @Transactional
    public PayResult pay(UUID agentId, String idemKey, int operatorId, String consumerNumber,
                         BigDecimal amount, String customerName, String customerMobile,
                         Map<String, Object> billFetch, String canonical) {
        FeatureUnavailable.throwIfCalled();
        long startMs = System.currentTimeMillis();
        log.info("BILL_PAY started agent={} operator={}", agentId, operatorId);
        try {
            IdempotencyService.Claim claim = idempotencyService.claim(agentId, idemKey, canonical);
            if (claim.replay()) {
                Map<String, Object> stored = mapper.readValue(claim.responseBody(), new TypeReference<>() {});
                return new PayResult(true, stored);
            }
            Map<String, Object> operator = catalog.requireOperator(operatorId);
            String txnType = String.valueOf(operator.get("txn_type"));
            if (!BILL_TYPES.contains(txnType)) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "WRONG_RAIL",
                        "This biller is not available on the BBPS rail");
            }
            services.requireEnabled(PlatformServiceGate.BBPS);
            String consumer = catalog.normalizeConsumer(operator, consumerNumber);
            if (amount == null || amount.compareTo(BigDecimal.ONE) < 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT", "Enter at least ₹1");
            }
            if (Boolean.TRUE.equals(operator.get("view_bill")) && (billFetch == null || billFetch.isEmpty())) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BILL_FETCH_REQUIRED",
                        "Fetch the bill before paying this biller");
            }
            String name = customerName == null ? "" : customerName.trim();
            String mobile = customerMobile == null ? "" : customerMobile.replaceAll("\\D", "");
            if (mobile.length() != 10) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CUSTOMER_REQUIRED",
                        "Record a 10-digit mobile for the receipt");
            }

            String partnerRef = "PS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            ServiceSettlement.Result settled = settlement.debitAndSettle(
                    agentId, txnType, amount, "PAYSPRINT", partnerRef, claim.keyId());

            String fetchJson = mapper.writeValueAsString(billFetch == null ? Map.of() : billFetch);
            LocalDate due = null;
            if (billFetch != null && billFetch.get("dueDate") != null) {
                due = LocalDate.parse(String.valueOf(billFetch.get("dueDate")));
            }
            jdbc.update("""
                    INSERT INTO bill_payments
                        (id, transaction_id, operator_id, consumer_number, customer_mobile, customer_name,
                         bill_amount, due_date, bill_fetch)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, UUID.randomUUID(), settled.transactionId(), operatorId, consumer, mobile,
                    name.isBlank() ? null : name, amount, due, fetchJson);
            transactionService.setCustomerRef(settled.transactionId(), mapper.writeValueAsString(Map.of(
                    "name", name, "mobile", mobile, "consumerLast4", last4(consumer),
                    "operator", operator.get("name"), "category", operator.get("category"))));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("transactionId", settled.transactionId());
            body.put("type", txnType);
            body.put("state", settled.state());
            body.put("amount", settled.amount());
            body.put("fee", settled.fee());
            body.put("totalDebited", settled.total());
            body.put("walletBalanceAfter", settled.walletBalanceAfter());
            body.put("partnerRef", settled.partnerRef());
            body.put("operatorName", operator.get("name"));
            body.put("consumerLast4", last4(consumer));
            body.put("statusPollUrl", "/api/v1/transactions/" + settled.transactionId());
            idempotencyService.complete(claim.keyId(), 200, mapper.writeValueAsString(body));
            log.info("BILL_PAY success txnId={} type={}", settled.transactionId(), txnType);
            return new PayResult(false, body);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("BILL_PAY failed agent={} reason={}", agentId, e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            log.info("BILL_PAY completed in {} ms", System.currentTimeMillis() - startMs);
        }
    }

    private static String last4(String value) {
        if (value == null || value.length() < 4) {
            return "••••";
        }
        return value.substring(value.length() - 4);
    }
}
