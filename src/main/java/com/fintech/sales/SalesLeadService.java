package com.fintech.sales;

import com.fintech.fdcards.FdConversionCommission;
import com.fintech.fdcards.FdJourneyResult;
import com.fintech.fdcards.FdLinkRouter;
import com.fintech.fdcards.FdProviderGate;
import com.fintech.identity.AdminAccess;
import com.fintech.paysprint.PaysprintFdClient;
import com.fintech.paysprint.PaysprintLeadClient;
import com.fintech.paysprint.PaysprintUtmOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fintech.platform.PlatformServiceGate;
import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesLeadService {

    private static final String SELECT = """
            SELECT l.id, l.customer_id, l.catalog_item_id, l.retailer_user_id, l.state, l.budget,
                   l.provider_refid, l.link_token, l.payment_link_url, l.link_opened_at,
                   l.created_at, l.updated_at, l.sale_type, l.sale_provider,
                   l.distributor_user_id, l.hub_id,
                   c.full_name AS customer_name, c.mobile AS customer_mobile, c.email AS customer_email,
                   i.name AS item_name, i.code AS item_code, i.provider, i.product_key, i.rail, i.external_product, i.apply_url,
                   cat.code AS category_code, cat.name AS category_name,
                   r.full_name AS retailer_name, r.code AS retailer_code,
                   d.full_name AS distributor_name, d.code AS distributor_code,
                   h.name AS hub_name
              FROM sales_leads l
              JOIN customers c ON c.id = l.customer_id
              JOIN catalog_items i ON i.id = l.catalog_item_id
              JOIN catalog_categories cat ON cat.code = i.category_code
              JOIN users r ON r.id = l.retailer_user_id
              LEFT JOIN users d ON d.id = COALESCE(l.distributor_user_id, r.parent_id)
              LEFT JOIN hubs h ON h.id = COALESCE(l.hub_id, r.hub_id)
            """;

    private final JdbcTemplate jdbc;
    private final PaysprintLeadClient leadClient;
    private final PaysprintFdClient fdClient;
    private final ProductEligibilityService eligibility;
    private final PlatformServiceGate services;
    private final FdLinkRouter fdLinkRouter;
    private final FdProviderGate fdProviders;
    private final FdConversionCommission fdCommission;
    private final SaleNetworkSnapshot snapshot;
    private final AdminAccess access;
    private final CustomerService customers;
    private final String publicAppUrl;

    public SalesLeadService(JdbcTemplate jdbc,
                            PaysprintLeadClient leadClient,
                            PaysprintFdClient fdClient,
                            ProductEligibilityService eligibility,
                            PlatformServiceGate services,
                            FdLinkRouter fdLinkRouter,
                            FdProviderGate fdProviders,
                            FdConversionCommission fdCommission,
                            SaleNetworkSnapshot snapshot,
                            AdminAccess access,
                            CustomerService customers,
                            @Value("${app.public-app-url}") String publicAppUrl) {
        this.jdbc = jdbc;
        this.leadClient = leadClient;
        this.fdClient = fdClient;
        this.eligibility = eligibility;
        this.services = services;
        this.fdLinkRouter = fdLinkRouter;
        this.fdProviders = fdProviders;
        this.fdCommission = fdCommission;
        this.snapshot = snapshot;
        this.access = access;
        this.customers = customers;
        this.publicAppUrl = publicAppUrl.replaceAll("/$", "");
    }

    public List<Map<String, Object>> list(AuthPrincipal me, UUID retailerUserId) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        scope(me, sql, args);
        if (retailerUserId != null) {
            if (!me.platformStaff() && !"MASTER_DISTRIBUTOR".equals(me.userType())) {
                throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
            }
            access.assertNetworkUser(retailerUserId);
            sql.append(" AND l.retailer_user_id = ? ");
            args.add(retailerUserId);
        }
        sql.append(" ORDER BY l.created_at DESC LIMIT 300 ");
        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .filter(row -> services.isCatalogCategoryAvailable(String.valueOf(row.get("category_code"))))
                .toList();
    }

    public Map<String, Object> get(AuthPrincipal me, UUID id) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE l.id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(id);
        scope(me, sql, args);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "LEAD_NOT_FOUND", "Sales lead not found");
        }
        Map<String, Object> row = rows.get(0);
        if (!services.isCatalogCategoryAvailable(String.valueOf(row.get("category_code")))) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "LEAD_NOT_FOUND", "Sales lead not found");
        }
        return row;
    }

    @Transactional
    public Map<String, Object> create(AuthPrincipal me, UUID customerId, UUID catalogItemId, BigDecimal budget) {
        CustomerService.requireSalesDesk(me);
        requireCatalogItemService(catalogItemId);
        eligibility.requireEligible(me, customerId, catalogItemId, budget);
        Map<String, Object> customer = customers.get(me, customerId);
        UUID outletUserId = (UUID) customer.get("retailer_user_id");
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT id FROM sales_leads
                 WHERE customer_id = ? AND catalog_item_id = ?
                   AND state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS')
                """, customerId, catalogItemId);
        if (!open.isEmpty()) {
            return get(me, (UUID) open.get(0).get("id"));
        }

        UUID id = UUID.randomUUID();
        String token = UUID.randomUUID().toString().replace("-", "");
        String refid = id.toString();
        String link = publicAppUrl + "/apply/" + token;
        SaleNetworkSnapshot.Attribution attr = snapshot.forLead(catalogItemId, outletUserId);
        try {
            jdbc.update("""
                    INSERT INTO sales_leads
                        (id, customer_id, catalog_item_id, retailer_user_id, state, provider_refid,
                         link_token, payment_link_url, budget, sale_type, sale_provider,
                         distributor_user_id, hub_id, state_history)
                    VALUES (?, ?, ?, ?, 'LINK_CREATED', ?, ?, ?, ?, ?, ?, ?, ?,
                            jsonb_build_array(jsonb_build_object('state','LINK_CREATED','at', now())))
                    """, id, customerId, catalogItemId, outletUserId, refid, token, link, budget,
                    attr.saleType(), attr.saleProvider(), attr.distributorUserId(), attr.hubId());
        } catch (DataIntegrityViolationException e) {
            List<Map<String, Object>> again = jdbc.queryForList("""
                    SELECT id FROM sales_leads
                     WHERE customer_id = ? AND catalog_item_id = ?
                       AND state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS')
                    """, customerId, catalogItemId);
            if (!again.isEmpty()) {
                return get(me, (UUID) again.get(0).get("id"));
            }
            throw e;
        }
        return get(me, id);
    }

    public Map<String, Object> publicStatus(String linkToken) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT state FROM sales_leads WHERE link_token = ?", linkToken);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", "This link is not valid");
        }
        String state = String.valueOf(rows.get(0).get("state"));
        boolean closed = "REJECTED".equals(state) || "EXPIRED".equals(state)
                || "CONVERTED".equals(state) || "ACTIVATED".equals(state);
        return Map.of("state", state, "active", !closed);
    }

    @Transactional
    public Map<String, String> start(String linkToken) {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " WHERE l.link_token = ?", linkToken);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", "This link is not valid");
        }
        Map<String, Object> lead = rows.get(0);
        services.requireCatalogCategory(String.valueOf(lead.get("category_code")));
        String state = String.valueOf(lead.get("state"));
        if ("REJECTED".equals(state) || "EXPIRED".equals(state) || "CONVERTED".equals(state)
                || "ACTIVATED".equals(state)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "LEAD_CLOSED", "This link is no longer active");
        }
        String refid = String.valueOf(lead.get("provider_refid"));
        String redirect = publicAppUrl + "/apply/" + linkToken + "/done";
        String url;
        String encdata;
        String method = "POST";
        var fdJourney = fdLinkRouter.tryStart(lead, redirect);
        if (fdJourney.isPresent()) {
            FdJourneyResult journey = fdJourney.get();
            url = journey.url();
            encdata = journey.encdata();
            method = journey.method();
        } else {
            PaysprintLeadClient.Journey journey = leadClient.generate(
                    refid,
                    String.valueOf(lead.get("customer_name")),
                    String.valueOf(lead.get("customer_mobile")),
                    lead.get("customer_email") == null ? "" : String.valueOf(lead.get("customer_email")),
                    String.valueOf(lead.get("external_product")),
                    redirect);
            url = journey.url();
            encdata = journey.encdata();
        }
        jdbc.update("""
                UPDATE sales_leads
                   SET state = CASE WHEN state = 'LINK_CREATED' THEN 'OPENED' ELSE state END,
                       link_opened_at = COALESCE(link_opened_at, now()),
                       encdata = ?,
                       updated_at = now(),
                       state_history = CASE WHEN state = 'LINK_CREATED'
                           THEN state_history || jsonb_build_array(jsonb_build_object('state','OPENED','at', now()))
                           ELSE state_history END
                 WHERE id = ?
                """, encdata, lead.get("id"));
        return Map.of("url", url, "encdata", encdata, "method", method);
    }

    public Map<String, Object> checkPaysprintUtmStatus(AuthPrincipal me, UUID id) {
        if (me == null || !me.platformStaff()) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        Map<String, Object> lead = get(me, id);
        if (!isPaysprintFd(lead)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_PAYSPRINT_FD",
                    "Status check is only for PaySprint FD cards");
        }
        String refid = String.valueOf(lead.get("provider_refid"));
        JsonNode data = fdClient.statusCheck(refid);
        String note = PaysprintUtmOutcome.summary(data);
        if (PaysprintUtmOutcome.converted(data)) {
            applyOutcome(refid, true, "STATUS_CHECK: " + note);
        } else if (PaysprintUtmOutcome.rejected(data)) {
            applyOutcome(refid, false, "STATUS_CHECK: " + note);
        } else {
            markInProgress(refid, "STATUS_CHECK: " + note);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lead", get(me, id));
        out.put("partner", mapperToMap(data));
        return out;
    }

    public void markInProgress(String refid, String message) {
        jdbc.update("""
                UPDATE sales_leads
                   SET state = CASE WHEN state IN ('LINK_CREATED', 'OPENED') THEN 'IN_PROGRESS' ELSE state END,
                       updated_at = now(),
                       state_history = state_history || jsonb_build_array(
                           jsonb_build_object('state', 'IN_PROGRESS', 'at', now(), 'message', ?))
                 WHERE provider_refid = ?
                   AND state IN ('LINK_CREATED', 'OPENED', 'IN_PROGRESS')
                """, message, refid);
    }

    public void applyOutcome(String refid, boolean converted, String message) {
        String next = converted ? "CONVERTED" : "REJECTED";
        List<Map<String, Object>> leads = jdbc.queryForList("""
                SELECT l.id, l.retailer_user_id, l.budget, i.provider, i.category_code
                  FROM sales_leads l
                  JOIN catalog_items i ON i.id = l.catalog_item_id
                 WHERE l.provider_refid = ?
                   AND l.state NOT IN ('CONVERTED', 'ACTIVATED', 'REJECTED', 'EXPIRED')
                """, refid);
        jdbc.update("""
                UPDATE sales_leads
                   SET state = ?,
                       updated_at = now(),
                       state_history = state_history || jsonb_build_array(
                           jsonb_build_object('state', ?, 'at', now(), 'message', ?))
                 WHERE provider_refid = ?
                   AND state NOT IN ('CONVERTED', 'ACTIVATED', 'REJECTED', 'EXPIRED')
                """, next, next, message, refid);
        if (converted && !leads.isEmpty() && "FD_CARD".equals(String.valueOf(leads.get(0).get("category_code")))) {
            fdCommission.payOnConversion(leads.get(0));
        }
    }

    private static boolean isPaysprintFd(Map<String, Object> lead) {
        return "FD_CARD".equals(String.valueOf(lead.get("category_code")))
                && ("PAYSPRINT".equals(String.valueOf(lead.get("provider")))
                || "PAYSPRINT_FD".equals(String.valueOf(lead.get("rail"))));
    }

    private static Map<String, Object> mapperToMap(JsonNode data) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (data == null || !data.isObject()) {
            return out;
        }
        data.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asText("")));
        return out;
    }

    private void requireCatalogItemService(UUID catalogItemId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category_code, provider FROM catalog_items WHERE id = ?", catalogItemId);
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Catalog item not found");
        }
        String category = String.valueOf(rows.get(0).get("category_code"));
        services.requireCatalogCategory(category);
        if ("FD_CARD".equals(category)) {
            fdProviders.requireProvider(String.valueOf(rows.get(0).get("provider")));
        }
    }

    private void scope(AuthPrincipal me, StringBuilder sql, List<Object> args) {
        if (me == null) {
            throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
        switch (me.userType()) {
            case "RETAILER" -> {
                sql.append(" AND l.retailer_user_id = ? ");
                args.add(me.userId());
            }
            case "MASTER_DISTRIBUTOR" -> {
                sql.append(" AND (r.parent_id = ? OR l.retailer_user_id = ?) ");
                args.add(me.userId());
                args.add(me.userId());
            }
            case "SUPER_ADMIN" -> {
                // network-wide
            }
            case "ADMIN" -> access.appendHubFilter(sql, args, "r.hub_id");
            default -> throw ApiException.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");
        }
    }
}
