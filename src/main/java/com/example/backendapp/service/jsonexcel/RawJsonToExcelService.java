package com.example.backendapp.service.jsonexcel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuples;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class RawJsonToExcelService {

    private static final Logger log = LoggerFactory.getLogger(RawJsonToExcelService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String STYLE_HEADER = "header";
    private static final String STYLE_DATE = "date";
    private static final String STYLE_DATETIME = "datetime";
    private static final String STYLE_PERCENT = "percent";
    private static final String STYLE_ERROR = "error";

    private static final String FORMAT_DATE = "yyyy-mm-dd";
    private static final String FORMAT_DATETIME = "yyyy-mm-dd hh:mm:ss";
    private static final String FORMAT_PERCENT = "0.00%";

    private static final Pattern PATTERN_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern PATTERN_DATETIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([zZ]|([+-])(\\d{2}):?(\\d{2}))?");
    private static final Set<String> KEYWORDS_PERCENT = Set.of("percent", "rate", "share", "percentage", "discount");

    private static final int ROW_WRITE_BATCH_SIZE = 1000;

    private record PreparedCellData(Object value, String styleHint) {}

    public Mono<Map<String, List<Map<String, Object>>>> parseJsonFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.warn("Attempted to parse a null or empty JSON file.");
            return Mono.error(new IllegalArgumentException("Input file must not be null or empty."));
        }
        return Mono.fromCallable(() -> {
                    log.info("Parsing JSON file: {}", file.getOriginalFilename());
                    return objectMapper.readValue(
                            file.getInputStream(),
                            new TypeReference<Map<String, List<Map<String, Object>>>>() {});
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<byte[]> generateExcel(Map<String, List<Map<String, Object>>> jsonData) {
        if (jsonData == null || jsonData.isEmpty()) {
            log.warn("Attempted to generate Excel from null or empty data.");
            return Mono.just(new byte[0]);
        }

        return Mono.fromCallable(() -> {
                    SXSSFWorkbook workbook = new SXSSFWorkbook(100);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    Map<String, CellStyle> styleCache = createStyleCache(workbook);
                    return Tuples.of(workbook, out, styleCache);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(tuple -> {
                    SXSSFWorkbook workbook = tuple.getT1();
                    ByteArrayOutputStream out = tuple.getT2();
                    Map<String, CellStyle> styleCache = tuple.getT3();

                    List<Mono<Void>> sheetMonos = new ArrayList<>();
                    int[] sheetIndex = {0};

                    for (Map.Entry<String, List<Map<String, Object>>> entry : jsonData.entrySet()) {
                        String sheetName = entry.getKey();
                        List<Map<String, Object>> rowsData = entry.getValue();

                        if (rowsData == null || rowsData.isEmpty()) {
                            log.debug("Skipping empty sheet: {}", sheetName);
                            continue;
                        }

                        String safeSheetName = WorkbookUtil.createSafeSheetName(sheetName);
                        SXSSFSheet sheet = workbook.createSheet(safeSheetName);
                        workbook.setSheetOrder(safeSheetName, sheetIndex[0]++);
                        sheet.trackAllColumnsForAutoSizing();

                        Map<String, Object> firstRowData = rowsData.getFirst();
                        List<String> headers = new ArrayList<>(new LinkedHashSet<>(firstRowData.keySet()));
                        writeHeaderRow(sheet, headers, styleCache.get(STYLE_HEADER));
                        AtomicInteger rowNum = new AtomicInteger(1);

                        Mono<Void> sheetMono = Flux.fromIterable(rowsData)
                                .flatMapSequential(rowData -> Mono.fromRunnable(() -> {
                                    List<PreparedCellData> rowCells = prepareRowData(rowData, headers);
                                    Row row = sheet.createRow(rowNum.getAndIncrement());

                                    for (int col = 0; col < rowCells.size(); col++) {
                                        Cell cell = row.createCell(col);
                                        PreparedCellData data = rowCells.get(col);
                                        try {
                                            applyPreparedCellValue(cell, data, styleCache);
                                        } catch (Exception e) {
                                            log.warn("Cell write error. Sheet: {}, Row: {}, Col: {}, Value: '{}'. Err: {}",
                                                    safeSheetName, row.getRowNum(), col, data.value(), e.getMessage());
                                            cell.setCellValue("WRITE_ERROR");
                                            CellStyle errStyle = styleCache.get(STYLE_ERROR);
                                            if (errStyle != null) cell.setCellStyle(errStyle);
                                        }
                                    }

                                    if (row.getRowNum() % ROW_WRITE_BATCH_SIZE == 0) {
                                        try {
                                            sheet.flushRows(ROW_WRITE_BATCH_SIZE);
                                        } catch (IOException e) {
                                            log.error("Flushing rows failed for {}", safeSheetName, e);
                                        }
                                    }
                                }), 1)
                                .doOnComplete(() -> {
                                    try {
                                        sheet.flushRows(0);
                                        sheet.createFreezePane(0, 1);
                                        for (int col = 0; col < headers.size(); col++) {
                                            sheet.autoSizeColumn(col);
                                        }
                                        log.debug("Finished writing sheet: {}", safeSheetName);
                                    } catch (IOException e) {
                                        log.error("Final flush failed for {}", safeSheetName, e);
                                    }
                                })
                                .then();

                        sheetMonos.add(sheetMono);
                    }

                    return Flux.concat(sheetMonos)
                            .then(Mono.fromCallable(() -> {
                                workbook.write(out);
                                workbook.close();
                                return out.toByteArray();
                            }).subscribeOn(Schedulers.boundedElastic()));
                });
    }

    private Map<String, CellStyle> createStyleCache(Workbook workbook) {
        Map<String, CellStyle> cache = new ConcurrentHashMap<>();
        DataFormat dataFormat = workbook.createDataFormat();

        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(boldFont);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        cache.put(STYLE_HEADER, headerStyle);

        CellStyle dateStyle = workbook.createCellStyle();
        dateStyle.setDataFormat(dataFormat.getFormat(FORMAT_DATE));
        cache.put(STYLE_DATE, dateStyle);

        CellStyle dateTimeStyle = workbook.createCellStyle();
        dateTimeStyle.setDataFormat(dataFormat.getFormat(FORMAT_DATETIME));
        cache.put(STYLE_DATETIME, dateTimeStyle);

        CellStyle percentStyle = workbook.createCellStyle();
        percentStyle.setDataFormat(dataFormat.getFormat(FORMAT_PERCENT));
        cache.put(STYLE_PERCENT, percentStyle);

        Font errorFont = workbook.createFont();
        errorFont.setColor(IndexedColors.RED.getIndex());
        CellStyle errorStyle = workbook.createCellStyle();
        errorStyle.setFont(errorFont);
        cache.put(STYLE_ERROR, errorStyle);

        return cache;
    }

    private void writeHeaderRow(Sheet sheet, List<String> headers, CellStyle headerStyle) {
        Row headerRow = sheet.createRow(0);
        for (int col = 0; col < headers.size(); col++) {
            Cell cell = headerRow.createCell(col);
            cell.setCellValue(headers.get(col));
            if (headerStyle != null) {
                cell.setCellStyle(headerStyle);
            }
        }
    }

    private List<PreparedCellData> prepareRowData(Map<String, Object> rowData, List<String> headers) {
        List<PreparedCellData> preparedCells = new ArrayList<>(headers.size());
        for (String header : headers) {
            Object value = rowData.get(header);
            preparedCells.add(prepareSingleCellValue(value, header));
        }
        return preparedCells;
    }

    private PreparedCellData prepareSingleCellValue(Object value, String header) {
        if (value == null) return new PreparedCellData(null, null);

        try {
            switch (value) {
                case String strVal -> {
                    String trimmedVal = strVal.trim();
                    if (PATTERN_DATE.matcher(trimmedVal).matches()) {
                        LocalDate date = LocalDate.parse(trimmedVal);
                        return new PreparedCellData(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()), STYLE_DATE);
                    }
                    if (PATTERN_DATETIME.matcher(trimmedVal).matches()) {
                        LocalDateTime dateTime = LocalDateTime.parse(trimmedVal);
                        return new PreparedCellData(Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant()), STYLE_DATETIME);
                    }
                    if (trimmedVal.endsWith("%")) {
                        double numericValue = Double.parseDouble(trimmedVal.replace("%", ""));
                        return new PreparedCellData(numericValue / 100.0, STYLE_PERCENT);
                    }
                    return new PreparedCellData(trimmedVal, null);
                }
                case Number num -> {
                    double numericValue = num.doubleValue();
                    String lowerHeader = header.toLowerCase();
                    if (KEYWORDS_PERCENT.stream().anyMatch(lowerHeader::contains)) {
                        return new PreparedCellData(numericValue / 100.0, STYLE_PERCENT);
                    }
                    return new PreparedCellData(numericValue, null);
                }
                case Boolean bool -> {
                    return new PreparedCellData(bool, null);
                }
                case Date date -> {
                    return new PreparedCellData(date, STYLE_DATE);
                }
                default -> {
                }
            }

            return new PreparedCellData(value.toString(), null);

        } catch (Exception e) {
            log.warn("Could not convert value of type {} to string during preparation: {}", value.getClass().getName(), e.getMessage());
            return new PreparedCellData("PREP_ERROR", STYLE_ERROR);
        }
    }

    private void applyPreparedCellValue(Cell cell, PreparedCellData preparedData, Map<String, CellStyle> styleCache) {
        Object value = preparedData.value();
        String styleHint = preparedData.styleHint();
        CellStyle style = (styleHint != null) ? styleCache.get(styleHint) : null;

        switch (value) {
            case null -> {
                cell.setBlank();
                return;
            }
            case String s -> cell.setCellValue(s);
            case Number n -> cell.setCellValue(n.doubleValue());
            case Boolean b -> cell.setCellValue(b);
            case Date d -> cell.setCellValue(d);
            default -> cell.setCellValue(value.toString());
        }

        if (style != null) cell.setCellStyle(style);
    }
}
