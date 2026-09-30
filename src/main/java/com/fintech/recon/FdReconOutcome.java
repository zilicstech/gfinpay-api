package com.fintech.recon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FdReconOutcome(
        int eligible,
        int matched,
        int activated,
        int inProgress,
        int unmatched,
        String parser,
        List<FdReconMismatch> mismatches,
        List<FdReconRow> rows) {

    public FdReconOutcome {
        mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static FdReconOutcome empty(String parser) {
        return new FdReconOutcome(0, 0, 0, 0, 0, parser, List.of(), List.of());
    }

    public int unidentified() {
        int n = 0;
        for (FdReconRow row : rows) {
            if (!row.identified()) {
                n++;
            }
        }
        return n;
    }

    public Map<String, Integer> statusCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (FdReconRow row : rows) {
            String status = row.currentStatus() == null ? "UNKNOWN" : row.currentStatus();
            counts.merge(status, 1, Integer::sum);
        }
        return counts;
    }
}
