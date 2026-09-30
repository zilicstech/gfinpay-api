package com.fintech.recon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.AdminAccess;
import com.fintech.identity.AdminPlatformService;
import com.fintech.platform.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates weekly FD MIS upload: factory picks the report handler, then a recon batch is stored.
 */
@Service
public class FdSalesReconService {

    private static final Logger log = LoggerFactory.getLogger(FdSalesReconService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final FdReconReportFactory factory;
    private final JdbcTemplate jdbc;
    private final AdminAccess access;
    private final AdminPlatformService admin;
    private final ObjectMapper objectMapper;

    public FdSalesReconService(FdReconReportFactory factory, JdbcTemplate jdbc,
                               AdminAccess access, AdminPlatformService admin,
                               ObjectMapper objectMapper) {
        this.factory = factory;
        this.jdbc = jdbc;
        this.access = access;
        this.admin = admin;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> submitWeeklyReport(String reportType, MultipartFile file) {
        access.requireSuperAdmin();
        validateExcel(file);
        FdReconReportHandler handler = factory.forType(reportType);
        String provider = handler.reportType();
        FdReconOutcome outcome = handler.reconcile(file);
        LocalDate businessDate = LocalDate.now(IST);
        String fileUri = "upload://" + provider + "/" + safeName(file.getOriginalFilename());
        UUID batchId = persistBatch(provider, businessDate, fileUri, outcome);
        log.info("FD_RECON provider={} parser={} file={} rows={} matched={} activated={} mismatches={}",
                provider, outcome.parser(), file.getOriginalFilename(), outcome.eligible(), outcome.matched(),
                outcome.activated(), outcome.unmatched());
        Map<String, Object> batch = admin.getRecon(batchId);
        batch.put("activated_count", outcome.activated());
        batch.put("matched_count", outcome.matched());
        batch.put("eligible_count", outcome.eligible());
        batch.put("parser", outcome.parser());
        return batch;
    }

    @Transactional
    public void deleteBatch(UUID id) {
        access.requireSuperAdmin();
        int n = jdbc.update("DELETE FROM recon_batches WHERE id = ?", id);
        if (n == 0) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "RECON_NOT_FOUND", "Recon batch not found");
        }
    }

    private UUID persistBatch(String provider, LocalDate businessDate, String fileUri, FdReconOutcome outcome) {
        int total = outcome.eligible();
        int matched = outcome.matched();
        int unidentified = outcome.unidentified();
        String status = outcome.mismatches().isEmpty() ? "COMPLETED" : "COMPLETED_WITH_MISMATCHES";
        String statusCountsJson = toJson(outcome.statusCounts());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recon_batches
                    (id, provider, business_date, mis_file_uri, total_rows, matched_rows, status,
                     unidentified_rows, status_counts, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
                """, id, provider, java.sql.Date.valueOf(businessDate), fileUri, total, matched, status,
                unidentified, statusCountsJson);
        persistRows(id, outcome);
        persistMismatches(id, outcome);
        return id;
    }

    private void persistRows(UUID batchId, FdReconOutcome outcome) {
        jdbc.update("DELETE FROM recon_rows WHERE batch_id = ?", batchId);
        for (FdReconRow row : outcome.rows()) {
            jdbc.update("""
                    INSERT INTO recon_rows
                        (batch_id, customer_mobile, customer_name, lead_id,
                         retailer_user_id, distributor_user_id, hub_id,
                         retailer_label, distributor_label, hub_label,
                         identified, current_status, payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    batchId,
                    row.customerMobile(),
                    row.customerName(),
                    row.leadId(),
                    row.retailerUserId(),
                    row.distributorUserId(),
                    row.hubId(),
                    row.retailerLabel(),
                    row.distributorLabel(),
                    row.hubLabel(),
                    row.identified(),
                    row.currentStatus(),
                    toJson(row.payload()));
        }
    }

    private void persistMismatches(UUID batchId, FdReconOutcome outcome) {
        jdbc.update("DELETE FROM recon_mismatches WHERE batch_id = ?", batchId);
        for (FdReconMismatch mm : outcome.mismatches()) {
            jdbc.update("""
                    INSERT INTO recon_mismatches (batch_id, mismatch_type, partner_ref, details)
                    VALUES (?, ?, ?, ?::jsonb)
                    """, batchId, mm.type(), mm.partnerRef(), toJson(mm.details()));
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw ApiException.of(HttpStatus.INTERNAL_SERVER_ERROR, "RECON_JSON",
                    "Could not serialize recon payload");
        }
    }

    private static void validateExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_REQUIRED",
                    "Upload the weekly Excel report");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE",
                    "Upload an Excel file (.xlsx or .xls)");
        }
    }

    private static String safeName(String original) {
        if (original == null || original.isBlank()) {
            return "report.xlsx";
        }
        return original.replace('\\', '_').replace('/', '_');
    }
}
