package com.fintech.sales;

import com.fintech.commission.CommissionEngine;
import com.fintech.fdcards.FdEligibilityService;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Which offerings a customer can still be sold in a category.
 * One catalog item is one product from one provider. A converted sale blocks only that
 * offering, so the same product_key can come back from a different provider.
 */
@Service
public class ProductEligibilityService {

    private final JdbcTemplate jdbc;
    private final CustomerService customers;
    private final PlatformServiceGate services;
    private final FdEligibilityService fdEligibility;
    private final CommissionEngine commissionEngine;

    public ProductEligibilityService(JdbcTemplate jdbc, CustomerService customers, PlatformServiceGate services,
                                       FdEligibilityService fdEligibility, CommissionEngine commissionEngine) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.services = services;
        this.fdEligibility = fdEligibility;
        this.commissionEngine = commissionEngine;
    }

    public Map<String, Object> check(AuthPrincipal me, UUID customerId, String categoryCode, BigDecimal budget) {
        CustomerService.requireSalesDesk(me);
        customers.get(me, customerId);
        String code = categoryCode == null ? "" : categoryCode.trim().toUpperCase();
        services.requireCatalogCategory(code);
        List<Map<String, Object>> categories = jdbc.queryForList(
                "SELECT code, name FROM catalog_categories WHERE code = ?", code);
        if (categories.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_CATEGORY", "Choose a catalog category");
        }
        if (budget == null || budget.signum() <= 0) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BUDGET", "Enter the customer's budget");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category_code", code);
        out.put("category_name", categories.get(0).get("name"));
        out.put("budget", budget);

        boolean fdCard = "FD_CARD".equals(code);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT id, code, name, product_key, provider, min_budget
                  FROM catalog_items
                 WHERE category_code = ?
                   AND (? OR active = TRUE)
                 ORDER BY sort_order, name
                """, code, fdCard);
        if (fdCard) {
            Map<String, Object> fd = fdEligibility.evaluate(customerId, budget, items);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> products = (List<Map<String, Object>>) fd.get("products");
            attachRetailerCommission(me.userId(), products);
            out.put("products", products);
            if (fd.get("message") != null) {
                out.put("message", fd.get("message"));
            }
            return out;
        }
        List<Map<String, Object>> leads = jdbc.queryForList("""
                SELECT id, catalog_item_id, state, payment_link_url
                  FROM sales_leads
                 WHERE customer_id = ?
                   AND state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS', 'CONVERTED', 'ACTIVATED')
                """, customerId);
        Map<UUID, Map<String, Object>> leadByItem = new LinkedHashMap<>();
        for (Map<String, Object> lead : leads) {
            leadByItem.putIfAbsent((UUID) lead.get("catalog_item_id"), lead);
        }

        List<Map<String, Object>> products = new ArrayList<>();
        int belowBudget = 0;
        int alreadySold = 0;
        for (Map<String, Object> item : items) {
            BigDecimal minimum = item.get("min_budget") == null ? BigDecimal.ZERO : (BigDecimal) item.get("min_budget");
            if (budget.compareTo(minimum) < 0) {
                belowBudget++;
                continue;
            }
            Map<String, Object> lead = leadByItem.get(item.get("id"));
            if (lead != null && ("CONVERTED".equals(String.valueOf(lead.get("state")))
                    || "ACTIVATED".equals(String.valueOf(lead.get("state"))))) {
                alreadySold++;
                continue;
            }
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("id", item.get("id"));
            product.put("code", item.get("code"));
            product.put("name", item.get("name"));
            product.put("product_key", item.get("product_key"));
            product.put("min_budget", minimum);
            if (lead != null) {
                product.put("existing_lead_id", lead.get("id"));
                product.put("payment_link_url", lead.get("payment_link_url"));
                product.put("state", lead.get("state"));
            }
            products.add(product);
        }
        out.put("products", products);
        if (products.isEmpty()) {
            out.put("message", emptyMessage(items.isEmpty(), belowBudget, alreadySold));
        }
        return out;
    }

    public void requireEligible(AuthPrincipal me, UUID customerId, UUID catalogItemId, BigDecimal budget) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category_code FROM catalog_items WHERE id = ? AND active = TRUE", catalogItemId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ITEM_UNAVAILABLE", "That product is not available");
        }
        Map<String, Object> result = check(me, customerId, String.valueOf(rows.get(0).get("category_code")), budget);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) result.get("products");
        boolean allowed = products.stream().anyMatch(product -> catalogItemId.equals(product.get("id")));
        if (!allowed) {
            String message = result.get("message") == null
                    ? "This customer is not eligible for that product"
                    : String.valueOf(result.get("message"));
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_ELIGIBLE", message);
        }
    }

    private void attachRetailerCommission(UUID retailerUserId, List<Map<String, Object>> products) {
        if (products == null) {
            return;
        }
        for (Map<String, Object> product : products) {
            String provider = String.valueOf(product.get("provider"));
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT commission_txn_type FROM fd_providers WHERE code = ?", provider);
            if (rows.isEmpty()) {
                product.put("retailer_commission", null);
                continue;
            }
            String txnType = String.valueOf(rows.get(0).get("commission_txn_type"));
            CommissionEngine.Resolution pricing = commissionEngine.resolve(retailerUserId, txnType, BigDecimal.ONE);
            product.put("retailer_commission", pricing.ruleId() == null ? null : pricing.retailerShare());
        }
    }

    private static String emptyMessage(boolean noneInCategory, int belowBudget, int alreadySold) {
        if (noneInCategory) {
            return "Nothing in this category is available to offer";
        }
        if (alreadySold > 0 && belowBudget == 0) {
            return "This customer already has the products we can offer in this category";
        }
        if (belowBudget > 0 && alreadySold == 0) {
            return "Budget is below the minimum for the products in this category";
        }
        return "No eligible product for this budget";
    }
}
