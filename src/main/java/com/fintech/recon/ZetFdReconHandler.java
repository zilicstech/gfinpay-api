package com.fintech.recon;

import com.fintech.recon.zet.ZetFdMisParser;
import java.util.ArrayList;
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

    public ZetFdReconHandler(ZetFdMisParser parser, FdMisMatcher matcher, FdLeadReconApplier applier) {
        this.parser = parser;
        this.matcher = matcher;
        this.applier = applier;
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
            if (lead.isEmpty()) {
                snapshots.add(FdReconRow.unidentified(row));
                mismatches.add(matcher.missingInternal(row));
                continue;
            }
            FdLeadReconApplier.ApplyResult result = applier.apply(lead.get(), row);
            snapshots.add(FdReconRow.fromMatch(row, lead.get()));
            matched++;
            if (result.newlyActivated()) {
                activated++;
            } else if (result.lifecycleTouched()) {
                inProgress++;
            }
        }
        mismatches.addAll(matcher.missingAtPartner(rows));
        int unmatched = mismatches.size();
        return new FdReconOutcome(rows.size(), matched, activated, inProgress, unmatched, PARSER, mismatches, snapshots);
    }
}
