package com.fintech.recon.paysprint;

import com.fintech.platform.web.ApiException;
import com.fintech.recon.FdMisRow;
import com.fintech.recon.FdPartnerLifecycle;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PaysprintFdMisParser {

    private static final String PROVIDER = "PAYSPRINT";
    private static final DateTimeFormatter MIS_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<FdMisRow> parse(MultipartFile file) {
        try (InputStream in = file.getInputStream(); Workbook workbook = new XSSFWorkbook(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_FILE", "No PaySprint rows found in workbook");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", "PaySprint sheet has no header row");
            }
            Map<String, Integer> idx = headerIndex(headerRow);
            if (!idx.containsKey("refid") || !idx.containsKey("status")) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE",
                        "PaySprint sheet must include refid and STATUS");
            }
            List<FdMisRow> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String refid = text(row, idx.get("refid"));
                if (refid.isBlank()) {
                    continue;
                }
                rows.add(toRow(sheet.getSheetName(), row, idx, refid));
            }
            if (rows.isEmpty()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_FILE", "No PaySprint rows found in workbook");
            }
            return rows;
        } catch (IOException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", "Could not read Excel file");
        }
    }

    private static FdMisRow toRow(String sheetName, Row row, Map<String, Integer> idx, String refid) {
        String statusCell = text(row, idx.get("status"));
        StatusMapping mapped = mapStatus(statusCell);
        String name = text(row, idx.get("name"));
        String phone = digits10(text(row, idx.get("mobile")));
        String product = text(row, idx.get("product"));
        Instant statusAt = instant(text(row, idx.get("dateadded")));

        Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : idx.entrySet()) {
            String value = text(row, e.getValue());
            if (!value.isBlank()) {
                raw.put(e.getKey(), value);
            }
        }
        if (mapped.partnerStatus() != null) {
            raw.put("partner_status", mapped.partnerStatus());
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("provider", PROVIDER);
        snapshot.put("ex_status", blankToNull(text(row, idx.get("ex_status"))));
        snapshot.put("ex_sub_status", blankToNull(text(row, idx.get("ex_sub_status"))));
        snapshot.put("fd_amount", blankToNull(text(row, idx.get("fd_amount"))));
        snapshot.put("ex_remarks", blankToNull(text(row, idx.get("ex_remarks"))));

        return new FdMisRow(
                PROVIDER,
                product.isBlank() ? PROVIDER : product,
                sheetName,
                phone,
                name.isBlank() ? null : name,
                null,
                refid,
                mapped.partnerStatus(),
                "STATUS",
                statusAt,
                mapped.lifecycle(),
                raw,
                snapshot);
    }

    private static StatusMapping mapStatus(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "link-generated" -> new StatusMapping("LINK_GENERATED", FdPartnerLifecycle.NONE);
            case "link-created" -> new StatusMapping("LINK_CREATED", FdPartnerLifecycle.IN_PROGRESS);
            case "pending" -> new StatusMapping("PENDING", FdPartnerLifecycle.IN_PROGRESS);
            case "success" -> new StatusMapping("SUCCESS", FdPartnerLifecycle.ACTIVATED);
            case "expired" -> new StatusMapping("EXPIRED", FdPartnerLifecycle.FAILED);
            default -> new StatusMapping(raw == null || raw.isBlank() ? null : raw.trim(), FdPartnerLifecycle.NONE);
        };
    }

    private record StatusMapping(String partnerStatus, FdPartnerLifecycle lifecycle) {}

    private static Map<String, Integer> headerIndex(Row headerRow) {
        Map<String, Integer> out = new LinkedHashMap<>();
        short last = headerRow.getLastCellNum();
        for (int c = 0; c < last; c++) {
            String h = text(headerRow, c);
            if (!h.isBlank()) {
                out.put(h.trim().toLowerCase(Locale.ROOT), c);
            }
        }
        return out;
    }

    private static String text(Row row, Integer col) {
        if (col == null || row == null) {
            return "";
        }
        Cell cell = row.getCell(col);
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString().replace('T', ' ');
                }
                double n = cell.getNumericCellValue();
                if (n == Math.floor(n) && !Double.isInfinite(n)) {
                    yield String.valueOf((long) n);
                }
                yield String.valueOf(n);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue().trim();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> "";
        };
    }

    private static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.replace('T', ' ');
        if (normalized.length() >= 19) {
            try {
                return LocalDateTime.parse(normalized.substring(0, 19), MIS_TS).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String digits10(String phone) {
        String d = phone.replaceAll("\\D", "");
        if (d.length() >= 10) {
            return d.substring(d.length() - 10);
        }
        return d;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
