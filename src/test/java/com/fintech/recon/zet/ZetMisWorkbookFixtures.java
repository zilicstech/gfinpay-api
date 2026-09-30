package com.fintech.recon.zet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Synthetic ZET MIS workbooks for tests (no real customer PII). */
public final class ZetMisWorkbookFixtures {

    private ZetMisWorkbookFixtures() {}

    public static byte[] minimalWorkbook() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeSbm(wb);
            writeIob(wb);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    public static void writeResourceFixture(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, minimalWorkbook());
    }

    private static void writeSbm(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("SBM");
        String[] headers = {
                "user_id", "phone_number", "full_name", "signup_date", "install_source",
                "fd_payment_failed_time", "fd_payment_success_time", "virtual_card_activated_time",
                "physical_card_activated_time", "pan_verified_time"
        };
        Row h = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            h.createCell(i).setCellValue(headers[i]);
        }
        Row paymentRetry = sheet.createRow(1);
        setRow(paymentRetry, headers, "u-pay", "9876500001", "Test Pay", "2026-01-01",
                null, "2026-01-02 10:00:00", "2026-01-03 11:00:00", null, null, null);

        Row activated = sheet.createRow(2);
        setRow(activated, headers, "u-act", "9876500002", "Test Act", "2026-01-01",
                null, null, null, "2026-01-05 09:00:00", null, null);

        Row physical = sheet.createRow(3);
        setRow(physical, headers, "u-phy", "9876500003", "Test Phy", "2026-01-01",
                null, null, null, "2026-01-06 08:00:00", "2026-01-07 08:00:00", null);
    }

    private static void writeIob(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("IOB");
        String[] headers = {
                "user_id", "phone_number", "full_name", "signup_date",
                "iob_pan_verified_time", "iob_card_activated_time"
        };
        Row h = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            h.createCell(i).setCellValue(headers[i]);
        }
        Row dual = sheet.createRow(1);
        setRow(dual, headers, "u-iob", "9876500004", "Test Iob", "2026-01-01",
                "2026-01-04 12:00:00", null);
    }

    private static void setRow(Row row, String[] headers, String userId, String phone, String name,
                               String signup, String install, String failPay, String successPay,
                               String virtualAct, String physicalAct, String panVerified) {
        put(row, headers, "user_id", userId);
        put(row, headers, "phone_number", phone);
        put(row, headers, "full_name", name);
        put(row, headers, "signup_date", signup);
        put(row, headers, "install_source", install);
        put(row, headers, "fd_payment_failed_time", failPay);
        put(row, headers, "fd_payment_success_time", successPay);
        put(row, headers, "virtual_card_activated_time", virtualAct);
        put(row, headers, "physical_card_activated_time", physicalAct);
        put(row, headers, "pan_verified_time", panVerified);
    }

    private static void setRow(Row row, String[] headers, String userId, String phone, String name,
                               String signup, String panVerified, String iobActivated) {
        put(row, headers, "user_id", userId);
        put(row, headers, "phone_number", phone);
        put(row, headers, "full_name", name);
        put(row, headers, "signup_date", signup);
        put(row, headers, "iob_pan_verified_time", panVerified);
        put(row, headers, "iob_card_activated_time", iobActivated);
    }

    private static void put(Row row, String[] headers, String name, String value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].equals(name)) {
                row.createCell(i).setCellValue(value);
                return;
            }
        }
    }
}
