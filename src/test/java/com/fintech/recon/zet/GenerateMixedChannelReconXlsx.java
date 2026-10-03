package com.fintech.recon.zet;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Synthetic ZET MIS with both network (INTERNAL) and vendor field (EXTERNAL) rows.
 * {@code install_source} must be the sales lead id so {@link com.fintech.recon.FdMisMatcher} can match.
 */
public final class GenerateMixedChannelReconXlsx {

    public static final String OUTPUT = "../docs/zet-recon-demo/ZetCard_MIS_mixed-internal-vendor.xlsx";

    private GenerateMixedChannelReconXlsx() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : OUTPUT);
        write(out);
        System.out.println("Wrote " + out.toAbsolutePath().normalize());
    }

    public static void write(Path out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeSbm(wb);
            writeIobShell(wb);
            java.nio.file.Files.createDirectories(out.getParent());
            try (var os = java.nio.file.Files.newOutputStream(out)) {
                wb.write(os);
            }
        }
    }

    /** Rows aligned with typical local DB leads — regenerate ids in seed-mixed-channel.sql if you reset. */
    private static void writeSbm(XSSFWorkbook wb) {
        String[] headers = {
                "user_id", "phone_number", "full_name", "signup_date", "install_source",
                "ph_no_verified_time", "pan_verified_time", "fd_payment_success_time",
                "vkyc_started_time", "virtual_card_activated_time", "physical_card_activated_time"
        };
        Sheet sheet = wb.createSheet("SBM");
        Row h = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            h.createCell(i).setCellValue(headers[i]);
        }

        // INTERNAL — in progress (VKYC)
        addRow(sheet, headers, 1, "int-vkyc", "7899274489", "Nitin (network)",
                "6a1d4b7b-3fb1-443e-b130-4e310a539cec", "2026-03-01 09:00:00",
                "2026-03-01 09:05:00", null, null, "2026-03-02 14:00:00", null, null);

        // INTERNAL — activated
        addRow(sheet, headers, 2, "int-act", "8971126362", "Annu (network)",
                "86a06a1b-33df-4de5-b43d-b39113a2ad8e", "2026-03-01 10:00:00",
                "2026-03-01 10:05:00", "2026-03-01 11:00:00", "2026-03-02 09:00:00", null,
                "2026-03-03 08:00:00", null);

        // INTERNAL — early funnel
        addRow(sheet, headers, 3, "int-pan", "6565656565", "Annu B (network)",
                "cb598104-4523-4893-8a8f-a19e868f12d1", "2026-03-02 08:00:00",
                "2026-03-02 08:10:00", "2026-03-02 09:00:00", null, null, null, null);

        // EXTERNAL (vendor) — FD paid
        addRow(sheet, headers, 4, "ext-paid", "7090674753", "Annu (vendor)",
                "d2675019-2738-4ea5-b11b-9dd32b86e856", "2026-03-02 12:00:00",
                "2026-03-02 12:05:00", null, "2026-03-03 10:00:00", null, null, null);

        // EXTERNAL (vendor) — card activated
        addRow(sheet, headers, 5, "ext-act", "7899078990", "Rohit (vendor)",
                "27edb71d-72a2-4a3f-b5ec-81a001045b63", "2026-02-28 15:00:00",
                "2026-02-28 15:05:00", "2026-02-28 16:00:00", "2026-03-01 11:00:00", null,
                "2026-03-01 16:00:00", null);

        // EXTERNAL (vendor) — phone verified only
        addRow(sheet, headers, 6, "ext-phone", "7090675752", "Ann (vendor)",
                "66a273a1-e2de-4ebb-926f-fe0734edafca", "2026-03-03 07:00:00",
                "2026-03-03 07:05:00", null, null, null, null, null);
    }

    private static void addRow(Sheet sheet, String[] headers, int rowIdx, String userId, String phone, String name,
                               String leadId, String signup, String phoneVerified, String panVerified,
                               String fdPaid, String vkyc, String virtualAct, String physicalAct) {
        Row row = sheet.createRow(rowIdx);
        put(row, headers, "user_id", userId);
        put(row, headers, "phone_number", phone);
        put(row, headers, "full_name", name);
        put(row, headers, "signup_date", signup);
        put(row, headers, "install_source", leadId);
        put(row, headers, "ph_no_verified_time", phoneVerified);
        put(row, headers, "pan_verified_time", panVerified);
        put(row, headers, "fd_payment_success_time", fdPaid);
        put(row, headers, "vkyc_started_time", vkyc);
        put(row, headers, "virtual_card_activated_time", virtualAct);
        put(row, headers, "physical_card_activated_time", physicalAct);
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
