package com.example.backendapp.util;

import com.example.backendapp.exception.ConversionException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;

public class ExcelPreviewUtil {

    private static final int MAX_ROWS = 3;

    public static Map<String, List<Map<String, Object>>> extractPreview(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {

            Map<String, List<Map<String, Object>>> preview = new LinkedHashMap<>();
            DataFormatter formatter = new DataFormatter();

            for (Sheet sheet : workbook) {
                if (sheet.getPhysicalNumberOfRows() == 0) continue;

                Row headerRow = sheet.getRow(0);
                if (headerRow == null) continue;

                List<String> headers = new ArrayList<>();
                for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                    Cell cell = headerRow.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    headers.add(formatter.formatCellValue(cell).trim());
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 1; i <= Math.min(sheet.getLastRowNum(), MAX_ROWS); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;

                    Map<String, Object> rowData = new LinkedHashMap<>();
                    for (int j = 0; j < headers.size(); j++) {
                        Cell cell = row.getCell(j, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        String value = formatter.formatCellValue(cell).trim();
                        rowData.put(headers.get(j), value);
                    }

                    if (!rowData.isEmpty()) {
                        rows.add(rowData);
                    }
                }

                if (!rows.isEmpty()) {
                    preview.put(sheet.getSheetName(), rows);
                }
            }

            if (preview.isEmpty()) {
                throw new ConversionException("Excel file has no usable data for schema preview.");
            }

            return preview;

        } catch (Exception e) {
            throw new ConversionException("Failed to extract preview: " + e.getMessage(), e);
        }
    }
}
