package com.fintech.recon.zet;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;

final class ZetExcelCells {

    private static final DateTimeFormatter MIS_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.S][.SS][.SSS]");

    private ZetExcelCells() {}

    static String text(Row row, Integer col) {
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
                    yield cell.getLocalDateTimeCellValue().toString();
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

    static boolean filled(Row row, Integer col) {
        return !text(row, col).isBlank();
    }

    static Instant instant(Row row, Integer col) {
        String raw = text(row, col);
        if (raw.isBlank()) {
            return null;
        }
        try {
            if (raw.matches("\\d+(\\.\\d+)?")) {
                double serial = Double.parseDouble(raw);
                if (serial > 1000 && serial < 100000) {
                    LocalDateTime dt = DateUtil.getLocalDateTime(serial);
                    return dt.toInstant(ZoneOffset.UTC);
                }
            }
            String normalized = raw.replace(".0", "");
            if (normalized.length() >= 19) {
                LocalDateTime ldt = LocalDateTime.parse(normalized.substring(0, 19).replace('T', ' '), MIS_TS);
                return ldt.toInstant(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException | NumberFormatException ignored) {
            // fall through
        }
        try {
            return Instant.parse(raw.endsWith("Z") ? raw : raw + "Z");
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    static String digits10(String phone) {
        String d = phone.replaceAll("\\D", "");
        if (d.length() >= 10) {
            return d.substring(d.length() - 10);
        }
        return d;
    }

    static String headerKey(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }
}
