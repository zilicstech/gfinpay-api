package com.fintech.recon.growmore;

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
public class GrowmoreFdMisParser {

    private static final String PROVIDER = "GROWMORE";
    private static final DateTimeFormatter MIS_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<FdMisRow> parse(MultipartFile file) {
        try (InputStream in = file.getInputStream(); Workbook workbook = new XSSFWorkbook(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_FILE", "No GrowMore rows found in workbook");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", "GrowMore sheet has no header row");
            }
            Map<String, Integer> idx = headerIndex(headerRow);
            if (!idx.containsKey("campaign name") || !idx.containsKey("display status")
                    || !idx.containsKey("current sub status")) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE",
                        "GrowMore sheet must include Campaign Name, Display Status, and Current Sub Status");
            }
            List<FdMisRow> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String campaign = text(row, idx.get("campaign name"));
                if (campaign.isBlank()) {
                    continue;
                }
                rows.add(toRow(sheet.getSheetName(), row, idx, campaign));
            }
            if (rows.isEmpty()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_FILE", "No GrowMore rows found in workbook");
            }
            return rows;
        } catch (IOException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", "Could not read Excel file");
        }
    }

    private static FdMisRow toRow(String sheetName, Row row, Map<String, Integer> idx, String campaign) {
        String display = text(row, idx.get("display status"));
        String sub = text(row, idx.get("current sub status"));
        String tech = text(row, idx.get("tech status"));
        FdPartnerLifecycle lifecycle = GrowmoreStatusMap.lifecycle(display, sub);
        String partnerStatus = GrowmoreStatusMap.partnerStatus(display);
        Instant statusAt = firstInstant(row, idx, "card activation date", "submit date", "lead date");
        String name = text(row, idx.get("customer name"));
        String phone = text(row, idx.get("mobile no"));
        String leadId = text(row, idx.get("lead id"));

        Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : idx.entrySet()) {
            String value = text(row, e.getValue());
            if (!value.isBlank()) {
                raw.put(e.getKey(), value);
            }
        }
        if (partnerStatus != null) {
            raw.put("partner_status", partnerStatus);
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("provider", PROVIDER);
        snapshot.put("display_status", blankToNull(display));
        snapshot.put("tech_status", blankToNull(tech));
        snapshot.put("current_sub_status", blankToNull(sub));
        snapshot.put("remark", blankToNull(text(row, idx.get("remark"))));
        snapshot.put("fd_amount", blankToNull(text(row, idx.get("fd amount"))));
        snapshot.put("card_activation_date", instantText(text(row, idx.get("card activation date"))));
        snapshot.put("partner_lead_id", blankToNull(leadId));

        return new FdMisRow(
                PROVIDER,
                "DCB",
                sheetName,
                phone,
                name.isBlank() ? null : name,
                null,
                campaign,
                partnerStatus,
                "Display Status",
                statusAt,
                lifecycle,
                raw,
                snapshot);
    }

    private static Instant firstInstant(Row row, Map<String, Integer> idx, String... headers) {
        for (String header : headers) {
            Instant at = instant(text(row, idx.get(header)));
            if (at != null) {
                return at;
            }
        }
        return null;
    }

    private static String instantText(String raw) {
        Instant at = instant(raw);
        return at == null ? null : at.toString();
    }

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
        try {
            if (raw.matches("\\d+(\\.\\d+)?")) {
                double serial = Double.parseDouble(raw);
                if (serial > 1000 && serial < 100000) {
                    return DateUtil.getLocalDateTime(serial).toInstant(ZoneOffset.UTC);
                }
            }
            String normalized = raw.replace('T', ' ');
            if (normalized.length() >= 19) {
                return LocalDateTime.parse(normalized.substring(0, 19), MIS_TS).toInstant(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException | NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
