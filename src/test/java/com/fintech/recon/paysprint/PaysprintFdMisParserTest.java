package com.fintech.recon.paysprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fintech.recon.FdMisRow;
import com.fintech.recon.FdPartnerLifecycle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class PaysprintFdMisParserTest {

    private final PaysprintFdMisParser parser = new PaysprintFdMisParser();

    @Test
    void mapsEachStatusAndSnapshot() throws IOException {
        List<FdMisRow> rows = parser.parse(workbook());
        assertEquals(5, rows.size());

        FdMisRow generated = byRef(rows, "ref-generated");
        assertEquals("LINK_GENERATED", generated.partnerStatus());
        assertEquals(FdPartnerLifecycle.NONE, generated.lifecycle());
        assertEquals(Instant.parse("2026-09-23T20:36:50Z"), generated.partnerStatusAt());

        FdMisRow created = byRef(rows, "ref-created");
        assertEquals("LINK_CREATED", created.partnerStatus());
        assertEquals(FdPartnerLifecycle.IN_PROGRESS, created.lifecycle());

        FdMisRow pending = byRef(rows, "ref-pending");
        assertEquals("PENDING", pending.partnerStatus());
        assertEquals(FdPartnerLifecycle.IN_PROGRESS, pending.lifecycle());
        assertEquals("KYC Stage - PAN", pending.partnerSnapshot().get("ex_status"));
        assertEquals("PAN Verification Done", pending.partnerSnapshot().get("ex_sub_status"));
        assertEquals("0.00", pending.partnerSnapshot().get("fd_amount"));
        assertNull(pending.partnerSnapshot().get("ex_remarks"));

        FdMisRow success = byRef(rows, "ref-success");
        assertEquals("SUCCESS", success.partnerStatus());
        assertEquals(FdPartnerLifecycle.ACTIVATED, success.lifecycle());
        assertEquals("PAYSPRINT", success.partnerSnapshot().get("provider"));
        assertEquals("Card Setup", success.partnerSnapshot().get("ex_status"));
        assertEquals("Card issued successfully", success.partnerSnapshot().get("ex_sub_status"));
        assertEquals("2000.00", success.partnerSnapshot().get("fd_amount"));
        assertEquals("Created from broadcast link", success.partnerSnapshot().get("ex_remarks"));

        FdMisRow expired = byRef(rows, "ref-expired");
        assertEquals("EXPIRED", expired.partnerStatus());
        assertEquals(FdPartnerLifecycle.FAILED, expired.lifecycle());
        assertEquals("Application Rejected", expired.partnerSnapshot().get("ex_status"));
    }

    private static FdMisRow byRef(List<FdMisRow> rows, String ref) {
        return rows.stream().filter(row -> ref.equals(row.matchRef())).findFirst().orElseThrow();
    }

    private static MockMultipartFile workbook() throws IOException {
        String[] headers = {
                "refid", "mobile", "name", "product", "STATUS",
                "ex_status", "ex_sub_status", "fd_amount", "ex_remarks", "dateadded"
        };
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Report");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            write(sheet, 1, headers, "ref-generated", "", "", "", "Link-Generated",
                    "", "", "", "", "2026-09-23 20:36:50");
            write(sheet, 2, headers, "ref-created", "", "Tapas", "CREDIT_CARD", "Link-Created",
                    "", "", "0.00", "", "2026-09-23 22:48:37");
            write(sheet, 3, headers, "ref-pending", "", "Uttam", "CREDIT_CARD", "Pending",
                    "KYC Stage - PAN", "PAN Verification Done", "0.00", "", "2026-09-20 10:59:34");
            write(sheet, 4, headers, "ref-success", "", "Dipak", "CREDIT_CARD", "Success",
                    "Card Setup", "Card issued successfully", "2000.00", "Created from broadcast link",
                    "2026-09-20 15:41:05");
            write(sheet, 5, headers, "ref-expired", "", "Nirnendu", "CREDIT_CARD", "Expired",
                    "Application Rejected", "FD payment refund completed", "0.00", "Created from broadcast link",
                    "2026-09-19 15:16:12");
            write(sheet, 6, headers, "", "", "Skipped", "CREDIT_CARD", "Pending",
                    "", "", "", "", "2026-09-19 15:16:12");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile("file", "paysprint.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private static void write(Sheet sheet, int index, String[] headers, String ref, String mobile, String name,
                              String product, String status, String exStatus, String exSub, String amount,
                              String remarks, String added) {
        Row row = sheet.createRow(index);
        put(row, headers, "refid", ref);
        put(row, headers, "mobile", mobile);
        put(row, headers, "name", name);
        put(row, headers, "product", product);
        put(row, headers, "STATUS", status);
        put(row, headers, "ex_status", exStatus);
        put(row, headers, "ex_sub_status", exSub);
        put(row, headers, "fd_amount", amount);
        put(row, headers, "ex_remarks", remarks);
        put(row, headers, "dateadded", added);
    }

    private static void put(Row row, String[] headers, String name, String value) {
        if (value == null || value.isBlank()) {
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
