package com.fintech.recon.growmore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fintech.recon.FdMisRow;
import com.fintech.recon.FdPartnerLifecycle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class GrowmoreFdMisParserTest {

    private final GrowmoreFdMisParser parser = new GrowmoreFdMisParser();

    @Test
    void mapsProgressActivationAndRejectionByHeaderName() throws IOException {
        List<FdMisRow> rows = parser.parse(workbook());
        assertEquals(3, rows.size());

        FdMisRow progress = byCampaign(rows, "ref-progress");
        assertEquals("FD_PAYMENT", progress.partnerStatus());
        assertEquals(FdPartnerLifecycle.IN_PROGRESS, progress.lifecycle());
        assertEquals("Fd Payment", progress.partnerSnapshot().get("tech_status"));
        assertEquals("FD Payment failed. Customer to retry", progress.partnerSnapshot().get("current_sub_status"));
        assertEquals("Payment was unsuccessful as you could not complete it in time.",
                progress.partnerSnapshot().get("remark"));
        assertNull(progress.partnerSnapshot().get("fd_amount"));
        assertEquals("#1790793041472233", progress.partnerSnapshot().get("partner_lead_id"));
        assertEquals(DateUtil.getLocalDateTime(46296).toInstant(ZoneOffset.UTC), progress.partnerStatusAt());

        FdMisRow activated = byCampaign(rows, "ref-activated");
        assertEquals("CARD_SETUP", activated.partnerStatus());
        assertEquals(FdPartnerLifecycle.ACTIVATED, activated.lifecycle());
        assertEquals("2000.00", activated.partnerSnapshot().get("fd_amount"));

        FdMisRow failed = byCampaign(rows, "ref-failed");
        assertEquals("APPLICATION_REJECTED", failed.partnerStatus());
        assertEquals(FdPartnerLifecycle.FAILED, failed.lifecycle());
        assertEquals("Bank policy", failed.partnerSnapshot().get("current_sub_status"));
    }

    private static FdMisRow byCampaign(List<FdMisRow> rows, String campaign) {
        return rows.stream().filter(row -> campaign.equals(row.matchRef())).findFirst().orElseThrow();
    }

    private static MockMultipartFile workbook() throws IOException {
        String[] headers = {
                "Lead ID", "Customer Name", "Mobile No", "Display Status", "Tech Status",
                "Current Sub Status", "Submit Date", "Remark", "FD Amount", "Card Activation Date",
                "Campaign Name"
        };
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Report");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            write(sheet, 1, headers, "#1790793041472233", "Chandan", "78****8680",
                    "FD Payment", "Fd Payment", "FD Payment failed. Customer to retry",
                    46296, "Payment was unsuccessful as you could not complete it in time.", "", "", "ref-progress");
            write(sheet, 2, headers, "#2", "Issued", "9000000001",
                    "Card Setup", "Card Issued", "Card issued successfully",
                    46296, "", "2000.00", "", "ref-activated");
            write(sheet, 3, headers, "#3", "Rejected", "9000000002",
                    "Application Rejected", "Rejected By Bank", "Bank policy",
                    46296, "Application Rejected", "", "", "ref-failed");
            write(sheet, 4, headers, "#4", "Skipped", "9000000003",
                    "FD Payment", "Fd Payment", "FD payment pending",
                    46296, "", "", "", "");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile("file", "growmore.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private static void write(Sheet sheet, int index, String[] headers, String leadId, String name, String mobile,
                              String display, String tech, String sub, double submitSerial, String remark,
                              String amount, String activated, String campaign) {
        Row row = sheet.createRow(index);
        put(row, headers, "Lead ID", leadId);
        put(row, headers, "Customer Name", name);
        put(row, headers, "Mobile No", mobile);
        put(row, headers, "Display Status", display);
        put(row, headers, "Tech Status", tech);
        put(row, headers, "Current Sub Status", sub);
        putNumber(row, headers, "Submit Date", submitSerial);
        put(row, headers, "Remark", remark);
        put(row, headers, "FD Amount", amount);
        put(row, headers, "Card Activation Date", activated);
        put(row, headers, "Campaign Name", campaign);
    }

    private static void put(Row row, String[] headers, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        int col = column(headers, name);
        if (col >= 0) {
            row.createCell(col).setCellValue(value);
        }
    }

    private static void putNumber(Row row, String[] headers, String name, double value) {
        int col = column(headers, name);
        if (col >= 0) {
            row.createCell(col).setCellValue(value);
        }
    }

    private static int column(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
