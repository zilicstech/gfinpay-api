package com.fintech.recon.zet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Builds ZET MIS from JSON: { "rows": [ { "mobile", "leadId", "profile", "sheet"?: "SBM"|"IOB" } ] } */
public final class GenerateE2eReconXlsx {

    private GenerateE2eReconXlsx() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: GenerateE2eReconXlsx <leads.json> <output.xlsx>");
            System.exit(1);
        }
        JsonNode root = new ObjectMapper().readTree(Path.of(args[0]).toFile());
        write(Path.of(args[1]), root.get("rows"));
    }

    static void write(Path out, JsonNode rows) throws IOException {
        String[] sbmHeaders = {
                "user_id", "phone_number", "full_name", "signup_date", "install_source",
                "ph_no_verified_time", "pan_verified_time", "fd_payment_success_time",
                "vkyc_started_time", "virtual_card_activated_time"
        };
        String[] iobHeaders = {
                "user_id", "phone_number", "full_name", "signup_date", "install_source",
                "iob_pan_verified_time", "iob_fd_payment_successful_time", "iob_card_activated_time"
        };
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sbm = wb.createSheet("SBM");
            headerRow(sbm, sbmHeaders);
            int sbmRow = 1;
            Sheet iob = wb.createSheet("IOB");
            headerRow(iob, iobHeaders);
            int iobRow = 1;
            for (JsonNode row : rows) {
                String sheet = row.has("sheet") ? row.get("sheet").asText("SBM") : "SBM";
                if ("IOB".equalsIgnoreCase(sheet)) {
                    iobRow = writeIobRow(iob, iobHeaders, iobRow, row);
                } else {
                    sbmRow = writeSbmRow(sbm, sbmHeaders, sbmRow, row);
                }
            }
            java.nio.file.Files.createDirectories(out.getParent());
            try (var os = java.nio.file.Files.newOutputStream(out)) {
                wb.write(os);
            }
        }
    }

    private static void headerRow(Sheet sheet, String[] headers) {
        Row h = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            h.createCell(i).setCellValue(headers[i]);
        }
    }

    private static int writeSbmRow(Sheet sheet, String[] headers, int r, JsonNode row) {
        String phone = row.get("mobile").asText();
        String leadId = row.get("leadId").asText();
        String profile = row.get("profile").asText();
        Row data = sheet.createRow(r++);
        put(data, headers, "user_id", "u-" + phone);
        put(data, headers, "phone_number", phone);
        put(data, headers, "full_name", phone);
        put(data, headers, "signup_date", "2026-03-10 09:00:00");
        put(data, headers, "install_source", leadId);
        stamp(data, headers, "ph_no_verified_time", profile, "all");
        stamp(data, headers, "pan_verified_time", profile, "all");
        stamp(data, headers, "fd_payment_success_time", profile, "ext_early");
        stamp(data, headers, "vkyc_started_time", profile, "int_mid");
        stamp(data, headers, "virtual_card_activated_time", profile, "int_act");
        return r;
    }

    private static int writeIobRow(Sheet sheet, String[] headers, int r, JsonNode row) {
        String phone = row.get("mobile").asText();
        String leadId = row.get("leadId").asText();
        String profile = row.get("profile").asText();
        Row data = sheet.createRow(r++);
        put(data, headers, "user_id", "u-" + phone);
        put(data, headers, "phone_number", phone);
        put(data, headers, "full_name", phone);
        put(data, headers, "signup_date", "2026-03-10 09:00:00");
        put(data, headers, "install_source", leadId);
        stamp(data, headers, "iob_pan_verified_time", profile, "all");
        stamp(data, headers, "iob_fd_payment_successful_time", profile, "ext_early");
        stamp(data, headers, "iob_card_activated_time", profile, "int_act");
        return r;
    }

    private static void stamp(Row row, String[] headers, String col, String profile, String rule) {
        if ("all".equals(rule) || rule.equals(profile)) {
            put(row, headers, col, "2026-03-10 10:00:00");
        }
    }

    private static void put(Row row, String[] headers, String name, String value) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].equals(name)) {
                row.createCell(i).setCellValue(value);
                return;
            }
        }
    }
}
