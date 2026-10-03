package com.fintech.recon;

import com.fintech.recon.zet.ZetFdMisParser;
import com.fintech.vendor.VendorService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ZetFdReconHandler implements FdReconReportHandler {

    private static final String PARSER = "ZET_EXCEL";

    private final ZetFdMisParser parser;
    private final FdMisMatcher matcher;
    private final FdLeadReconApplier applier;
    private final VendorService vendors;

    public ZetFdReconHandler(ZetFdMisParser parser, FdMisMatcher matcher, FdLeadReconApplier applier,
                             VendorService vendors) {
        this.parser = parser;
        this.matcher = matcher;
        this.applier = applier;
        this.vendors = vendors;
    }

    @Override
    public String reportType() {
        return "ZET";
    }

    @Override
    public FdReconOutcome reconcile(MultipartFile file) {
        List<FdMisRow> rows = parser.parse(file);
        List<FdReconMismatch> mismatches = new ArrayList<>();
        List<FdReconRow> snapshots = new ArrayList<>();
        int matched = 0;
        int activated = 0;
        int inProgress = 0;
        for (FdMisRow row : rows) {
            Optional<Map<String, Object>> lead = matcher.matchLead(row);
            if (lead.isPresent()) {
                FdLeadReconApplier.ApplyResult result = applier.apply(lead.get(), row);
                cacheVendorSaleIfExternal(row, lead.get());
                snapshots.add(FdReconRow.fromMatch(row, lead.get()));
                matched++;
                if (result.newlyActivated()) {
                    activated++;
                } else if (result.lifecycleTouched()) {
                    inProgress++;
                }
                continue;
            }
            VendorService.OptionalVendorLead vendorLead = vendors.findVendorLeadByRef(row.matchRef());
            if (vendorLead != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("source", "VENDOR_AFFILIATE");
                payload.put("vendor_code", vendorLead.vendorCode());
                payload.put("employee_code", vendorLead.employeeCode());
                if (vendorLead.employeeGfinCode() != null) {
                    payload.put("employee_gfin_code", vendorLead.employeeGfinCode());
                }
                if (vendorLead.customerId() != null) {
                    payload.put("customer_id", vendorLead.customerId().toString());
                }
                if (vendorLead.trackingRef() != null) {
                    payload.put("tracking_ref", vendorLead.trackingRef().toString());
                }
                if (row.raw() != null) {
                    payload.put("mis", row.raw());
                }
                vendors.upsertVendorSale(
                        vendorLead,
                        row.phone(),
                        row.fullName(),
                        row.productKey(),
                        row.partnerStatus(),
                        row.partnerStatusAt(),
                        row.partnerUserId(),
                        payload);
                snapshots.add(FdReconRow.fromVendorLead(row, vendorLead));
                matched++;
                continue;
            }
            snapshots.add(FdReconRow.unidentified(row));
            mismatches.add(matcher.missingInternal(row));
        }
        mismatches.addAll(matcher.missingAtPartner(rows));
        int unmatched = mismatches.size();
        return new FdReconOutcome(rows.size(), matched, activated, inProgress, unmatched, PARSER, mismatches, snapshots);
    }

    private void cacheVendorSaleIfExternal(FdMisRow row, Map<String, Object> lead) {
        if (!"EXTERNAL".equals(String.valueOf(lead.get("sale_channel")))) {
            return;
        }
        VendorService.OptionalVendorLead vendorLead = vendors.findVendorLeadByRef(row.matchRef());
        if (vendorLead == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "VENDOR_AFFILIATE");
        payload.put("sales_lead_id", String.valueOf(lead.get("id")));
        if (row.raw() != null) {
            payload.put("mis", row.raw());
        }
        vendors.upsertVendorSale(
                vendorLead,
                row.phone(),
                row.fullName(),
                row.productKey(),
                row.partnerStatus(),
                row.partnerStatusAt(),
                row.partnerUserId(),
                payload);
    }
}
