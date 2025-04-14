package com.example.backendapp.service.exceljson;

import com.example.backendapp.exception.ConversionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.*;

@Service
public class RawExcelToJsonService {

    private final ObjectMapper objectMapper;

    public RawExcelToJsonService() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Mono<String> convertAsync(MultipartFile file) {
        if (file.isEmpty()) {
            return Mono.error(new ConversionException("Uploaded file is empty."));
        }

        return Mono.using(
                () -> {
                    try {
                        return file.getInputStream();
                    } catch (IOException e) {
                        throw new ConversionException("Failed to open uploaded file stream", e);
                    }
                },
                inputStream -> Mono.using(
                        () -> {
                            try {
                                return WorkbookFactory.create(inputStream);
                            } catch (IOException e) {
                                throw new ConversionException("Failed to create workbook from input stream", e);
                            }
                        },
                        workbook -> {
                            if (workbook.getNumberOfSheets() == 0) {
                                return Mono.error(new ConversionException("Excel file contains no sheets."));
                            }

                            List<String> sheetOrder = new ArrayList<>();
                            workbook.sheetIterator().forEachRemaining(sheet -> sheetOrder.add(sheet.getSheetName()));

                            return Flux.fromIterable(workbook::sheetIterator)
                                    .parallel()
                                    .runOn(Schedulers.parallel())
                                    .map(this::processSheet)
                                    .sequential()
                                    .collectList()
                                    .flatMap(sheetMaps -> {
                                        Map<String, List<Map<String, Object>>> workbookData = new LinkedHashMap<>();

                                        for (String sheetName : sheetOrder) {
                                            for (Map<String, List<Map<String, Object>>> sheetMap : sheetMaps) {
                                                if (sheetMap.containsKey(sheetName)) {
                                                    workbookData.put(sheetName, sheetMap.get(sheetName));
                                                    break;
                                                }
                                            }
                                        }

                                        if (workbookData.isEmpty()) {
                                            return Mono.error(new ConversionException("Excel file contains no usable data."));
                                        }

                                        try {
                                            return Mono.just(objectMapper.writeValueAsString(workbookData));
                                        } catch (Exception e) {
                                            return Mono.error(new ConversionException("Failed to serialize JSON", e));
                                        }
                                    });
                        },
                        workbook -> {
                            try {
                                workbook.close();
                            } catch (IOException ignored) {}
                        }
                ),
                inputStream -> {
                    try {
                        inputStream.close();
                    } catch (IOException ignored) {}
                }
        ).subscribeOn(Schedulers.boundedElastic());
    }


    private Map<String, List<Map<String, Object>>> processSheet(Sheet sheet) {
        List<Map<String, Object>> sheetData = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();

        if (sheet.getPhysicalNumberOfRows() == 0) return Map.of();

        int headerRowIndex = 0;
        while (headerRowIndex <= sheet.getLastRowNum()) {
            Row potentialHeader = sheet.getRow(headerRowIndex);
            if (potentialHeader != null && potentialHeader.getPhysicalNumberOfCells() > 0) break;
            headerRowIndex++;
        }

        if (headerRowIndex > sheet.getLastRowNum()) return Map.of();
        Row headerRow = sheet.getRow(headerRowIndex);

        List<String> headers = new ArrayList<>();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            headers.add(formatter.formatCellValue(cell).trim());
        }

        if (headers.stream().allMatch(String::isBlank)) return Map.of();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            Map<String, Object> rowData = new LinkedHashMap<>();
            boolean nonEmpty = false;

            for (int j = 0; j < headers.size(); j++) {
                Cell cell = row.getCell(j, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                Object value = getCellValue(cell);
                if (value != null && !(value instanceof String && ((String) value).isBlank())) {
                    nonEmpty = true;
                }
                rowData.put(headers.get(j), value);
            }

            if (nonEmpty) sheetData.add(rowData);
        }

        return sheetData.isEmpty() ? Map.of() : Map.of(sheet.getSheetName(), sheetData);
    }

    public Object getCellValue(Cell cell) {
        if (cell == null) return null;

        try {
            switch (cell.getCellType()) {
                case STRING:
                    return cell.getStringCellValue();

                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        try {
                            return cell.getLocalDateTimeCellValue().toString();
                        } catch (Exception e) {
                            Date javaDate = cell.getDateCellValue();
                            return javaDate != null ? javaDate.toInstant().toString() : null;
                        }
                    } else {
                        double numValue = cell.getNumericCellValue();
                        if (numValue == Math.floor(numValue) && !Double.isInfinite(numValue)) {
                            if (numValue >= Long.MIN_VALUE && numValue <= Long.MAX_VALUE) {
                                return (long) numValue;
                            }
                        }
                        return numValue;
                    }

                case BOOLEAN:
                    return cell.getBooleanCellValue();

                case FORMULA:
                    FormulaEvaluator evaluator = cell.getSheet().getWorkbook()
                            .getCreationHelper().createFormulaEvaluator();
                    CellValue cellValue = evaluator.evaluate(cell);

                    switch (cellValue.getCellType()) {
                        case STRING:
                            return cellValue.getStringValue();
                        case NUMERIC:
                            if (DateUtil.isCellDateFormatted(cell)) {
                                Date javaDate = cell.getDateCellValue();
                                return javaDate != null ? javaDate.toInstant().toString() : null;
                            }
                            double numVal = cellValue.getNumberValue();
                            if (numVal == Math.floor(numVal)) {
                                if (numVal >= Long.MIN_VALUE && numVal <= Long.MAX_VALUE) {
                                    return (long) numVal;
                                }
                            }
                            return numVal;
                        case BOOLEAN:
                            return cellValue.getBooleanValue();
                        case ERROR:
                            return "#ERROR_" + FormulaError.forInt(cellValue.getErrorValue()).getString();
                        default:
                            return null;
                    }

                case ERROR:
                    return "#CELL_ERROR_" + FormulaError.forInt(cell.getErrorCellValue()).getString();

                default:
                    return null;
            }
        } catch (Exception e) {
            return "#EVAL_ERROR!";
        }
    }
}