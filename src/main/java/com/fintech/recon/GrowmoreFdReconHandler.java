package com.fintech.recon;

import com.fintech.platform.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class GrowmoreFdReconHandler implements FdReconReportHandler {

    @Override
    public String reportType() {
        return "GROWMORE";
    }

    @Override
    public FdReconOutcome reconcile(MultipartFile file) {
        throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "PARSER_NOT_IMPLEMENTED",
                "GrowMore Excel reconciliation is not available yet");
    }
}
