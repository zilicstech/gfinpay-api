package com.fintech.dmt;

import com.fintech.platform.web.ApiException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/** Reads a sheet whose header row has BankId and BankName. */
final class DmtBankWorkbook {

    record RowData(String bankId, String bankName) {}

    private DmtBankWorkbook() {}

    static List<RowData> read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_REQUIRED", "Choose the bank list spreadsheet.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_TYPE", "Upload an .xlsx file.");
        }
        DataFormatter formatter = new DataFormatter();
        try (InputStream in = file.getInputStream(); Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_SHEET", "The spreadsheet has no sheet.");
            }
            int idCol = -1;
            int nameCol = -1;
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header != null) {
                for (Cell cell : header) {
                    String label = formatter.formatCellValue(cell).replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
                    if ("bankid".equals(label)) {
                        idCol = cell.getColumnIndex();
                    } else if ("bankname".equals(label)) {
                        nameCol = cell.getColumnIndex();
                    }
                }
            }
            if (idCol < 0 || nameCol < 0) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "COLUMNS_REQUIRED",
                        "The sheet needs BankId and BankName columns.");
            }
            List<RowData> rows = new ArrayList<>();
            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String bankId = formatter.formatCellValue(row.getCell(idCol)).trim();
                String bankName = formatter.formatCellValue(row.getCell(nameCol)).trim();
                if (bankId.isBlank() && bankName.isBlank()) {
                    continue;
                }
                rows.add(new RowData(bankId, bankName));
            }
            if (rows.isEmpty()) {
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_SHEET", "The sheet has no banks.");
            }
            return rows;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_UNREADABLE", "Could not read that spreadsheet.");
        }
    }
}
