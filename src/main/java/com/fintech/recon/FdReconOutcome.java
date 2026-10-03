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

    public Map<String, Object> statusCounts() {
        Map<String, int[]> tallies = new LinkedHashMap<>();
        for (FdReconRow row : rows) {
            String status = row.currentStatus() == null ? "UNKNOWN" : row.currentStatus();
            int[] bucket = tallies.computeIfAbsent(status, k -> new int[3]);
            bucket[0]++;
            String channel = saleChannel(row);
            if ("INTERNAL".equals(channel)) {
                bucket[1]++;
            } else if ("EXTERNAL".equals(channel)) {
                bucket[2]++;
            }
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : tallies.entrySet()) {
            int[] b = e.getValue();
            counts.put(e.getKey(), Map.of(
                    "total", b[0],
                    "internal", b[1],
                    "vendor", b[2]));
        }
        return counts;
    }

    private static String saleChannel(FdReconRow row) {
        if (!row.identified()) {
            return null;
        }
        Map<String, Object> payload = row.payload();
        if (payload == null) {
            return null;
        }
        Object channel = payload.get("sale_channel");
        if (channel != null && !String.valueOf(channel).isBlank()) {
            return String.valueOf(channel);
        }
        if ("VENDOR_AFFILIATE".equals(String.valueOf(payload.get("source")))) {
            return "EXTERNAL";
        }
        return null;
    }
}
