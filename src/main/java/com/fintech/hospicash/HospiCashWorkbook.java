package com.fintech.hospicash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Attachment-form workbook. Header sits on row 2, matching the partner sheet. */
final class HospiCashWorkbook {

    static final String[] HEADERS = {
            "Gender",
            "Customer Name",
            "DOB",
            "Address -1",
            "Pin code",
            "Mobile no.",
            "E-mail ID",
            "Insured Relationship",
            "Nominee Name",
            "Nominee Relation",
            "Nominee DOB",
            "Good Health Declaration:-",
            "PED details if any",
            "PED Since",
            "PEP",
            "Plan",
            "Customer Name",
            "Mobile No",
            "Mail ID",
            "Date Of Birth",
            "Gender",
            "GFIN Sale Id",
            "Status"
    };

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private HospiCashWorkbook() {}

    static byte[] write(List<Map<String, Object>> sales) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Sheet1");
            sheet.createRow(0);
            Row header = sheet.createRow(1);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            int rowIndex = 2;
            for (Map<String, Object> sale : sales) {
                Row row = sheet.createRow(rowIndex++);
                String gender = text(sale.get("gender"));
                String name = text(sale.get("customer_name"));
                String dob = iso(sale.get("dob"));
                String mobile = text(sale.get("mobile"));
                String email = text(sale.get("email"));
                row.createCell(0).setCellValue(gender);
                row.createCell(1).setCellValue(name);
                row.createCell(2).setCellValue(dob);
                row.createCell(3).setCellValue(text(sale.get("address")));
                row.createCell(4).setCellValue(text(sale.get("pincode")));
                row.createCell(5).setCellValue(mobile);
                row.createCell(6).setCellValue(email);
                row.createCell(7).setCellValue(text(sale.get("insured_relationship")));
                row.createCell(8).setCellValue(text(sale.get("nominee_name")));
                row.createCell(9).setCellValue(text(sale.get("nominee_relation")));
                row.createCell(10).setCellValue(iso(sale.get("nominee_dob")));
                row.createCell(11).setCellValue(text(sale.get("good_health")));
                row.createCell(12).setCellValue(text(sale.get("ped_details")));
                row.createCell(13).setCellValue(text(sale.get("ped_since")));
                row.createCell(14).setCellValue(text(sale.get("pep")));
                row.createCell(15).setCellValue(text(sale.get("plan_name")));
                row.createCell(16).setCellValue(name);
                row.createCell(17).setCellValue(mobile);
                row.createCell(18).setCellValue(email);
                row.createCell(19).setCellValue(dob);
                row.createCell(20).setCellValue(gender);
                row.createCell(21).setCellValue(text(sale.get("gfin_sale_code")));
                row.createCell(22).setCellValue(statusLabel(sale.get("activation_status")));
            }
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the Hospi Cash workbook", e);
        }
    }

    record ReconRow(int rowNumber, String saleCode, String mobile, String email, String status, String policyRef) {}

    static List<ReconRow> read(InputStream in) throws IOException {
        try (Workbook book = new XSSFWorkbook(in)) {
            Sheet sheet = book.getNumberOfSheets() == 0 ? null : book.getSheetAt(0);
            if (sheet == null) {
                return List.of();
            }
            DataFormatter formatter = new DataFormatter();
            int headerRow = -1;
            Map<String, Integer> cols = Map.of();
            int last = Math.min(sheet.getLastRowNum(), 6);
            for (int i = 0; i <= last; i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Map<String, Integer> found = headers(row, formatter);
                if (found.containsKey("sale") || found.containsKey("mobile")) {
                    headerRow = i;
                    cols = found;
                    break;
                }
            }
            if (headerRow < 0) {
                return List.of();
            }
            List<ReconRow> rows = new ArrayList<>();
            for (int i = headerRow + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String sale = cell(row, cols.get("sale"), formatter);
                String mobile = digits(cell(row, cols.get("mobile"), formatter));
                String email = cell(row, cols.get("email"), formatter).toLowerCase(Locale.ROOT);
                String status = cell(row, cols.get("status"), formatter);
                String policy = cell(row, cols.get("policy"), formatter);
                if (sale.isBlank() && mobile.isBlank() && email.isBlank()) {
                    continue;
                }
                rows.add(new ReconRow(i + 1, sale, mobile, email, status, policy));
            }
            return rows;
        }
    }

    private static Map<String, Integer> headers(Row row, DataFormatter formatter) {
        Map<String, Integer> cols = new LinkedHashMap<>();
        for (Cell cell : row) {
            String key = norm(formatter.formatCellValue(cell));
            if (key.isBlank()) {
                continue;
            }
            int index = cell.getColumnIndex();
            if ((key.contains("gfin") && key.contains("sale")) || key.equals("gfin sale id")) {
                cols.putIfAbsent("sale", index);
            } else if (key.contains("policy") || key.contains("certificate")) {
                cols.putIfAbsent("policy", index);
            } else if (key.contains("status")) {
                cols.putIfAbsent("status", index);
            } else if (key.contains("mail")) {
                cols.putIfAbsent("email", index);
            } else if (key.startsWith("mobile")) {
                cols.putIfAbsent("mobile", index);
            }
        }
        return cols;
    }

    private static String cell(Row row, Integer index, DataFormatter formatter) {
        if (index == null) {
            return "";
        }
        Cell cell = row.getCell(index);
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            Date value = cell.getDateCellValue();
            if (value == null) {
                return "";
            }
            return value.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(ISO);
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static String iso(Object value) {
        LocalDate date = date(value);
        return date == null ? "" : date.format(ISO);
    }

    private static LocalDate date(Object value) {
        if (value instanceof LocalDate local) {
            return local;
        }
        if (value instanceof java.sql.Date sql) {
            return sql.toLocalDate();
        }
        if (value == null) {
            return null;
        }
        return LocalDate.parse(value.toString());
    }

    private static String statusLabel(Object value) {
        String status = text(value);
        if ("SUCCESS".equalsIgnoreCase(status)) {
            return "Success";
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            return "Failed";
        }
        return "Pending";
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String digits(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
            }
        }
        if (out.length() > 10) {
            return out.substring(out.length() - 10);
        }
        return out.toString();
    }

    private static String norm(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
