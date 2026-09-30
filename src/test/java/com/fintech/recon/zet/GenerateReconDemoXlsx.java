package com.fintech.recon.zet;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Writes admin-upload demo MIS for local ZET recon (see docs/zet-recon-demo/README.md). */
public final class GenerateReconDemoXlsx {

    public static final String DEMO_PHONE = "9999999001";
    public static final String DEMO_LEAD_ID = "b2c3d4e5-f6a7-4890-b123-456789abcdef";

    private GenerateReconDemoXlsx() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0]
                : "../docs/zet-recon-demo/ZetCard_MIS_recon-demo.xlsx");
        write(out);
        System.out.println("Wrote " + out.toAbsolutePath().normalize());
    }

    public static void write(Path out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeSbmDemo(wb);
            writeIobShell(wb);
            java.nio.file.Files.createDirectories(out.getParent());
            try (var os = java.nio.file.Files.newOutputStream(out)) {
                wb.write(os);
            }
        }
    }

    private static void writeSbmDemo(XSSFWorkbook wb) {
        String[] headers = {
                "user_id", "phone_number", "full_name", "signup_date", "install_source",
                "ph_no_entered_time", "ph_no_verified_time", "pan_entered_time", "pan_verified_time",
                "bank_acc_verified_time", "fd_payment_initiated_time", "fd_payment_failed_time",
                "fd_payment_success_time", "email_entered_time", "email_verified_time",
                "aadhaar_entered_time", "aadhaar_verified_time", "application_submitted_time",
                "vkyc_skipped_time", "vkyc_started_time", "vkyc_location_mismatch_time", "vkyc_failed_time",
                "fd_booked_time", "virtual_card_activated_time", "physical_card_activated_time"
        };
        Sheet sheet = wb.createSheet("SBM");
        Row h = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            h.createCell(i).setCellValue(headers[i]);
        }
        Row row = sheet.createRow(1);
        put(row, headers, "user_id", "recon-demo-zet-001");
        put(row, headers, "phone_number", DEMO_PHONE);
        put(row, headers, "full_name", "Recon Demo User");
        put(row, headers, "signup_date", "2026-09-20 09:00:00");
        put(row, headers, "install_source", DEMO_LEAD_ID);
        put(row, headers, "ph_no_entered_time", "2026-09-20 09:05:00");
        put(row, headers, "ph_no_verified_time", "2026-09-20 09:06:00");
        put(row, headers, "pan_entered_time", "2026-09-20 10:00:00");
        put(row, headers, "pan_verified_time", "2026-09-20 10:15:00");
        put(row, headers, "bank_acc_verified_time", "2026-09-20 11:00:00");
        put(row, headers, "fd_payment_initiated_time", "2026-09-21 08:00:00");
        put(row, headers, "fd_payment_failed_time", "2026-09-21 08:05:00");
        put(row, headers, "fd_payment_success_time", "2026-09-21 09:30:00");
        put(row, headers, "email_entered_time", "2026-09-21 10:00:00");
        put(row, headers, "email_verified_time", "2026-09-21 10:10:00");
        put(row, headers, "aadhaar_entered_time", "2026-09-22 12:00:00");
        put(row, headers, "aadhaar_verified_time", "2026-09-22 12:30:00");
        put(row, headers, "application_submitted_time", "2026-09-23 14:00:00");
        put(row, headers, "vkyc_started_time", "2026-09-24 16:00:00");
        // Latest filled column → partner_status VKYC_STARTED; lifecycle IN_PROGRESS
    }

    private static void writeIobShell(XSSFWorkbook wb) {
        String[] headers = {
                "user_id", "phone_number", "full_name", "signup_date",
                "iob_pan_verified_time", "iob_card_activated_time"
        };
        Sheet sheet = wb.createSheet("IOB");
        Row h = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            h.createCell(i).setCellValue(headers[i]);
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
