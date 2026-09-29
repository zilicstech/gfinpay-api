package com.fintech.recon;

import org.springframework.web.multipart.MultipartFile;

/**
 * One implementation per partner MIS workbook. Parsing stays inside the handler
 * so new report types do not change a shared service.
 */
public interface FdReconReportHandler {

    String reportType();

    FdReconOutcome reconcile(MultipartFile file);
}
