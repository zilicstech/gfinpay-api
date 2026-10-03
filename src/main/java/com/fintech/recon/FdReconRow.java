package com.fintech.recon;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One uploaded MIS row, attributed to a sale network when we can match it. */
public record FdReconRow(
        String customerMobile,
        String customerName,
        UUID leadId,
        UUID retailerUserId,
        UUID distributorUserId,
        UUID hubId,
        String retailerLabel,
        String distributorLabel,
        String hubLabel,
        boolean identified,
        String currentStatus,
        Map<String, Object> payload) {

    public static final String UNIDENTIFIED = "UNIDENTIFIED";

    public static FdReconRow fromMatch(FdMisRow row, Map<String, Object> lead) {
        UUID leadId = uuid(lead.get("id"));
        UUID retailerId = uuid(lead.get("retailer_user_id"));
        UUID distributorId = uuid(lead.get("distributor_user_id"));
        UUID hubId = uuid(lead.get("hub_id"));
        String retailerLabel = label(str(lead.get("retailer_code")), str(lead.get("retailer_name")));
        String distributorLabel = label(str(lead.get("distributor_code")), str(lead.get("distributor_name")));
        String hubLabel = first(str(lead.get("hub_name")), UNIDENTIFIED);
        String customerName = first(str(lead.get("customer_name")), row.fullName());
        String status = currentStatus(row);
        Map<String, Object> payload = payload(row, true, leadId, retailerId, retailerLabel,
                distributorId, distributorLabel, hubId, hubLabel, customerName);
        Object channel = lead.get("sale_channel");
        if (channel != null) {
            payload.put("sale_channel", String.valueOf(channel));
        }
        return new FdReconRow(row.phone(), customerName, leadId, retailerId, distributorId, hubId,
                retailerLabel, distributorLabel, hubLabel, true, status, payload);
    }

    public static FdReconRow fromVendorLead(FdMisRow row, com.fintech.vendor.VendorService.OptionalVendorLead lead) {
        String status = currentStatus(row);
        String vendorLabel = lead.vendorCode() + " · " + lead.vendorName();
        String employeeLabel = lead.employeeCode() + " · " + lead.employeeName();
        String customerName = first(lead.customerName(), row.fullName());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "VENDOR_AFFILIATE");
        payload.put("sale_channel", "EXTERNAL");
        payload.put("vendor_code", lead.vendorCode());
        payload.put("employee_code", lead.employeeCode());
        if (lead.employeeGfinCode() != null) {
            payload.put("employee_gfin_code", lead.employeeGfinCode());
        }
        if (lead.trackingRef() != null) {
            payload.put("tracking_ref", lead.trackingRef().toString());
        }
        if (lead.customerId() != null) {
            payload.put("customer_id", lead.customerId().toString());
        }
        payload.put("partner_status", row.partnerStatus());
        if (row.raw() != null) {
            payload.put("mis", row.raw());
        }
        return new FdReconRow(row.phone(), customerName, null, lead.affiliateId(), lead.vendorId(), lead.hubId(),
                employeeLabel, vendorLabel, UNIDENTIFIED, true, status, payload);
    }

    public static FdReconRow unidentified(FdMisRow row) {
        String status = currentStatus(row);
        String name = first(row.fullName(), null);
        Map<String, Object> payload = payload(row, false, null, null, UNIDENTIFIED,
                null, UNIDENTIFIED, null, UNIDENTIFIED, name);
        return new FdReconRow(row.phone(), name, null, null, null, null,
                UNIDENTIFIED, UNIDENTIFIED, UNIDENTIFIED, false, status, payload);
    }

    private static Map<String, Object> payload(FdMisRow row, boolean identified, UUID leadId,
                                               UUID retailerId, String retailerLabel,
                                               UUID distributorId, String distributorLabel,
                                               UUID hubId, String hubLabel, String customerName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", row.provider());
        out.put("sheet", row.sheetName());
        out.put("product_key", row.productKey());
        out.put("partner_status", row.partnerStatus());
        out.put("partner_status_at", row.partnerStatusAt() == null ? null : row.partnerStatusAt().toString());
        out.put("source_column", row.sourceColumn());
        out.put("identified", identified);
        out.put("lead_id", leadId == null ? null : leadId.toString());
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("mobile", row.phone());
        customer.put("name", customerName);
        customer.put("partner_user_id", row.partnerUserId());
        out.put("customer", customer);
        out.put("retailer", party(retailerId, retailerLabel));
        out.put("distributor", party(distributorId, distributorLabel));
        out.put("hub", party(hubId, hubLabel));
        Map<String, String> mis = row.raw() == null ? Map.of() : new LinkedHashMap<>(row.raw());
        out.put("mis", mis);
        return out;
    }

    private static Map<String, Object> party(UUID id, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id == null ? null : id.toString());
        m.put("label", label);
        return m;
    }

    private static String currentStatus(FdMisRow row) {
        return first(row.partnerStatus(), "UNKNOWN");
    }

    private static String label(String code, String name) {
        if (code != null && name != null) {
            return code + " · " + name;
        }
        return first(code, name, UNIDENTIFIED);
    }

    private static String first(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static UUID uuid(Object v) {
        if (v instanceof UUID u) {
            return u;
        }
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        if (s.isBlank() || "null".equals(s)) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
