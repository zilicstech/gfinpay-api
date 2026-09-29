package com.fintech.recon;

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
import org.springframework.dao.DuplicateKeyException;
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

    public FdSalesReconService(FdReconReportFactory factory, JdbcTemplate jdbc,
                               AdminAccess access, AdminPlatformService admin) {
        this.factory = factory;
        this.jdbc = jdbc;
        this.access = access;
        this.admin = admin;
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
        log.info("FD_RECON provider={} parser={} file={} eligible={} activated={}",
                provider, outcome.parser(), file.getOriginalFilename(), outcome.eligible(), outcome.activated());
        Map<String, Object> batch = admin.getRecon(batchId);
        batch.put("activated_count", outcome.activated());
        batch.put("eligible_count", outcome.eligible());
        batch.put("parser", outcome.parser());
        return batch;
    }

    private UUID persistBatch(String provider, LocalDate businessDate, String fileUri, FdReconOutcome outcome) {
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id FROM recon_batches WHERE provider = ? AND business_date = ?
                """, provider, java.sql.Date.valueOf(businessDate));
        int total = outcome.eligible();
        int matched = outcome.activated();
        if (!existing.isEmpty()) {
            UUID id = (UUID) existing.get(0).get("id");
            jdbc.update("""
                    UPDATE recon_batches
                       SET mis_file_uri = ?, total_rows = ?, matched_rows = ?, status = 'COMPLETED',
                           finished_at = now()
                     WHERE id = ?
                    """, fileUri, total, matched, id);
            return id;
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO recon_batches
                        (id, provider, business_date, mis_file_uri, total_rows, matched_rows, status, finished_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', now())
                    """, id, provider, java.sql.Date.valueOf(businessDate), fileUri, total, matched);
            return id;
        } catch (DuplicateKeyException e) {
            existing = jdbc.queryForList("""
                    SELECT id FROM recon_batches WHERE provider = ? AND business_date = ?
                    """, provider, java.sql.Date.valueOf(businessDate));
            if (existing.isEmpty()) {
                throw ApiException.of(HttpStatus.CONFLICT, "RECON_BATCH_CONFLICT",
                        "Could not store recon batch for this provider and date");
            }
            UUID existingId = (UUID) existing.get(0).get("id");
            jdbc.update("""
                    UPDATE recon_batches
                       SET mis_file_uri = ?, total_rows = ?, matched_rows = ?, status = 'COMPLETED',
                           finished_at = now()
                     WHERE id = ?
                    """, fileUri, total, matched, existingId);
            return existingId;
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
