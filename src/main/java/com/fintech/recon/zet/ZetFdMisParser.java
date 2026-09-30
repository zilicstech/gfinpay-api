package com.fintech.recon.zet;

import com.fintech.platform.web.ApiException;
import com.fintech.recon.FdMisRow;
import com.fintech.recon.FdPartnerLifecycle;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ZetFdMisParser {

    private static final String PROVIDER = "ZET";

    public List<FdMisRow> parse(MultipartFile file) {
        List<FdMisRow> rows = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook workbook = new XSSFWorkbook(in)) {
            for (ZetSheetFunnel def : ZetFunnelDefinitions.sheets()) {
                Sheet sheet = workbook.getSheet(def.sheetName());
                if (sheet == null) {
                    sheet = workbook.getSheet(def.sheetName().toLowerCase(Locale.ROOT));
                }
                if (sheet == null) {
                    continue;
                }
                validateHeaders(def, sheet);
                rows.addAll(parseSheet(def, sheet));
            }
        } catch (IOException e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", "Could not read Excel file");
        }
        if (rows.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_FILE", "No SBM or IOB rows found in workbook");
        }
        return rows;
    }

    private static void validateHeaders(ZetSheetFunnel def, Sheet sheet) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE", def.sheetName() + " sheet has no header row");
        }
        Map<String, Integer> idx = headerIndex(headerRow);
        boolean hasActivation = def.stages().stream()
                .filter(s -> "ACTIVATED".equals(s.partnerStatus()))
                .anyMatch(s -> idx.containsKey(s.header()));
        if (!hasActivation) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE",
                    def.sheetName() + " sheet is missing activation column");
        }
    }

    private static List<FdMisRow> parseSheet(ZetSheetFunnel def, Sheet sheet) {
        Row headerRow = sheet.getRow(0);
        Map<String, Integer> idx = headerIndex(headerRow);
        List<FdMisRow> out = new ArrayList<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String phone = ZetExcelCells.digits10(ZetExcelCells.text(row, idx.get("phone_number")));
            if (phone.length() != 10) {
                continue;
            }
            FdMisRow parsed = toRow(def, row, idx);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    private static FdMisRow toRow(ZetSheetFunnel def, Row row, Map<String, Integer> idx) {
        String phone = ZetExcelCells.digits10(ZetExcelCells.text(row, idx.get("phone_number")));
        String userId = ZetExcelCells.text(row, idx.get("user_id"));
        String name = ZetExcelCells.text(row, idx.get("full_name"));
        String install = ZetExcelCells.text(row, idx.get("install_source"));
        String matchRef = null;
        if (install != null && !install.isBlank()) {
            try {
                UUID.fromString(install.trim());
                matchRef = install.trim();
            } catch (IllegalArgumentException ignored) {
                // not our ref
            }
        }

        ZetFunnelStage winner = null;
        Instant winnerAt = null;
        for (ZetFunnelStage stage : def.stages()) {
            Integer col = idx.get(stage.header());
            if (col != null && ZetExcelCells.filled(row, col)) {
                winner = stage;
                winnerAt = ZetExcelCells.instant(row, col);
            }
        }

        FdPartnerLifecycle lifecycle = FdPartnerLifecycle.NONE;
        String partnerStatus = null;
        String sourceColumn = null;
        if (winner != null) {
            partnerStatus = winner.partnerStatus();
            sourceColumn = winner.header();
            lifecycle = winner.lifecycle();
            if ("PHYSICAL_CARD_ACTIVATED".equals(partnerStatus)) {
                lifecycle = FdPartnerLifecycle.ACTIVATED;
            }
        } else if (ZetExcelCells.filled(row, idx.get("signup_date"))) {
            partnerStatus = "SIGNED_UP";
            sourceColumn = "signup_date";
            lifecycle = FdPartnerLifecycle.OPENED;
            winnerAt = ZetExcelCells.instant(row, idx.get("signup_date"));
        }

        Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : idx.entrySet()) {
            String value = ZetExcelCells.text(row, e.getValue());
            if (!value.isBlank()) {
                raw.put(e.getKey(), value);
            }
        }
        if (partnerStatus != null) {
            raw.put("partner_status", partnerStatus);
        }

        return new FdMisRow(
                PROVIDER,
                def.productKey(),
                def.sheetName(),
                phone,
                name,
                userId,
                matchRef,
                partnerStatus,
                sourceColumn,
                winnerAt,
                lifecycle,
                raw);
    }

    private static Map<String, Integer> headerIndex(Row headerRow) {
        Map<String, Integer> out = new LinkedHashMap<>();
        short last = headerRow.getLastCellNum();
        for (int c = 0; c < last; c++) {
            String h = ZetExcelCells.text(headerRow, c);
            if (!h.isBlank()) {
                out.put(ZetExcelCells.headerKey(h), c);
            }
        }
        return out;
    }
}
