package com.example.backendapp.service.jsonexcel;

import com.example.backendapp.cache.AiResponseCache;
import com.example.backendapp.config.GeminiConfig;
import com.example.backendapp.exception.AIProcessingException;
import com.example.backendapp.util.GeminiResponseUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@Service
public class AiJsonToExcelService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiResponseCache aiResponseCache;
    private final GeminiConfig geminiConfig;

    @Autowired
    public AiJsonToExcelService(WebClient.Builder webClientBuilder,
                                AiResponseCache aiResponseCache,
                                GeminiConfig geminiConfig) {
        this.webClient = webClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.aiResponseCache = aiResponseCache;
        this.geminiConfig = geminiConfig;
    }

    public Mono<byte[]> enhance(Map<String, List<Map<String, Object>>> originalJson) {
        int totalSheets = originalJson.size();
        int dynamicConcurrency = Math.min(5, Math.max(1, totalSheets / 2));

        return Flux.fromIterable(originalJson.entrySet())
                .flatMapSequential(entry -> enhanceSheet(entry.getKey(), entry.getValue()), dynamicConcurrency)
                .collectMap(Tuple2::getT1, Tuple2::getT2, LinkedHashMap::new)
                .flatMap(enhancedMap -> generateExcelAsync(originalJson, enhancedMap));
    }

    private Mono<Tuple2<String, List<Map<String, Object>>>> enhanceSheet(String sheetName, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Mono.just(Tuples.of(sheetName, Collections.emptyList()));
        }

        List<List<Map<String, Object>>> chunks = splitIntoChunksAdaptive(rows);

        return Flux.fromIterable(chunks)
                .flatMap(chunk -> enhanceChunk(sheetName, chunk), 3)
                .collectList()
                .flatMap(chunksList -> Mono.fromCallable(() -> {
                    List<Map<String, Object>> merged = new ArrayList<>();
                    for (List<Map<String, Object>> chunk : chunksList) merged.addAll(chunk);
                    return Tuples.of(sheetName, merged);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<List<Map<String, Object>>> enhanceChunk(String sheetName, List<Map<String, Object>> chunk) {
        try {
            String chunkJson = objectMapper.writeValueAsString(Map.of(sheetName, chunk));
            String sheetCacheKey = generateHash(sheetName + chunkJson);
            String cached = aiResponseCache.getCachedResponse(sheetCacheKey);
            if (cached != null) {
                return Mono.fromCallable(() -> objectMapper.readValue(
                        cached, new TypeReference<List<Map<String, Object>>>() {})
                ).subscribeOn(Schedulers.boundedElastic());
            }

            Map<String, Object> requestBody = buildGeminiRequestBody(sheetName, chunk);

            return webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(geminiConfig.buildModelPath())
                            .queryParam("key", geminiConfig.getApiKey())
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(90))
                    .map(GeminiResponseUtil::extractTextFromGeminiResponse)
                    .flatMap(response -> Mono.fromCallable(() -> {
                        try {
                            Map<String, List<Map<String, Object>>> parsed =
                                    objectMapper.readValue(response, new TypeReference<>() {});
                            List<Map<String, Object>> result = parsed.getOrDefault(sheetName, Collections.emptyList());
                            aiResponseCache.cacheResponse(sheetCacheKey, objectMapper.writeValueAsString(result));
                            return result;
                        } catch (Exception ex1) {
                            List<Map<String, Object>> parsedArray =
                                    objectMapper.readValue(response, new TypeReference<>() {});
                            aiResponseCache.cacheResponse(sheetCacheKey, objectMapper.writeValueAsString(parsedArray));
                            return parsedArray;
                        }
                    }).subscribeOn(Schedulers.boundedElastic()));

        } catch (Exception e) {
            return Mono.error(new AIProcessingException("Failed to enhance sheet chunk: " + e.getMessage()));
        }
    }

    private Map<String, Object> buildGeminiRequestBody(String sheetName, List<Map<String, Object>> chunk) throws Exception {
        String chunkJson = objectMapper.writeValueAsString(Map.of(sheetName, chunk));

        String prompt = """
            You are an AI assistant. Clean and standardize the following sheet's JSON.
            Fix typos, inconsistent formatting, and ensure data consistency.
            Wrap the cleaned result using the original sheet name as key. Return ONLY valid JSON.

            Input:
            """ + chunkJson;

        return Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json")
        );
    }

    private Mono<byte[]> generateExcelAsync(Map<String, List<Map<String, Object>>> original,
                                            Map<String, List<Map<String, Object>>> enhanced) {
        return Mono.fromCallable(() -> generateHighlightedExcel(original, enhanced))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private byte[] generateHighlightedExcel(Map<String, List<Map<String, Object>>> original,
                                            Map<String, List<Map<String, Object>>> enhanced) throws Exception {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            CreationHelper factory = workbook.getCreationHelper();
            CellStyle headerStyle = createBoldStyle(workbook);
            CellStyle highlightStyle = createHighlightStyle(workbook);
            CellStyle legendStyle = createLegendStyle(workbook);

            for (Map.Entry<String, List<Map<String, Object>>> entry : enhanced.entrySet()) {
                String originalSheetName = entry.getKey();
                String sheetName = WorkbookUtil.createSafeSheetName(originalSheetName);
                SXSSFSheet sheet = workbook.createSheet(sheetName);
                sheet.trackAllColumnsForAutoSizing();

                List<Map<String, Object>> enhancedRows = entry.getValue();
                List<Map<String, Object>> originalRows = original.getOrDefault(originalSheetName, List.of());
                if (enhancedRows.isEmpty()) continue;

                List<String> headers = new ArrayList<>(enhancedRows.getFirst().keySet());

                Row headerRow = sheet.createRow(0);
                for (int col = 0; col < headers.size(); col++) {
                    Cell headerCell = headerRow.createCell(col);
                    headerCell.setCellValue(headers.get(col));
                    headerCell.setCellStyle(headerStyle);
                }

                Drawing<?> drawing = sheet.createDrawingPatriarch();
                boolean hasChanges = false;

                for (int rowIdx = 0; rowIdx < enhancedRows.size(); rowIdx++) {
                    Map<String, Object> enhancedRow = enhancedRows.get(rowIdx);
                    Map<String, Object> originalRow = rowIdx < originalRows.size() ? originalRows.get(rowIdx) : Map.of();

                    Row dataRow = sheet.createRow(rowIdx + 1);
                    for (int col = 0; col < headers.size(); col++) {
                        String key = headers.get(col);
                        Object newValue = enhancedRow.get(key);
                        Object oldValue = originalRow.get(key);
                        Cell cell = dataRow.createCell(col);

                        boolean isChanged = !Objects.equals(
                                newValue != null ? newValue.toString() : "",
                                oldValue != null ? oldValue.toString() : ""
                        );

                        applyCellValue(cell, newValue);
                        if (isChanged) {
                            hasChanges = true;
                            cell.setCellStyle(highlightStyle);
                            addComment(factory, drawing, cell, oldValue);
                        }
                    }

                    if ((rowIdx + 1) % 1000 == 0) {
                        sheet.flushRows(1000);
                    }
                }

                sheet.createFreezePane(0, 1);
                for (int col = 0; col < headers.size(); col++) {
                    sheet.autoSizeColumn(col);
                }

                if (hasChanges) {
                    int legendRowNum = sheet.getLastRowNum() + 2;
                    Row legendRow = sheet.createRow(legendRowNum);
                    Cell legendCell = legendRow.createCell(0);
                    legendCell.setCellValue("AI Modified: Hover over cell for original value");
                    legendCell.setCellStyle(legendStyle);
                    legendRow.setHeightInPoints(sheet.getDefaultRowHeightInPoints() * 3f);

                    if (headers.size() > 1) {
                        sheet.addMergedRegion(new CellRangeAddress(legendRowNum, legendRowNum, 0, headers.size() - 1));
                    }
                }
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private List<List<Map<String, Object>>> splitIntoChunksAdaptive(List<Map<String, Object>> data) {
        int rowCount = data.size();
        int adaptiveChunkSize = rowCount <= 200 ? rowCount : (rowCount <= 1000 ? 200 : 500);

        List<List<Map<String, Object>>> chunks = new ArrayList<>();
        for (int i = 0; i < data.size(); i += adaptiveChunkSize) {
            chunks.add(data.subList(i, Math.min(data.size(), i + adaptiveChunkSize)));
        }
        return chunks;
    }

    private void applyCellValue(Cell cell, Object value) {
        switch (value) {
            case null -> cell.setBlank();
            case Number n -> cell.setCellValue(n.doubleValue());
            case Boolean b -> cell.setCellValue(b);
            default -> cell.setCellValue(value.toString());
        }
    }

    private void addComment(CreationHelper factory, Drawing<?> drawing, Cell cell, Object originalValue) {
        try {
            ClientAnchor anchor = factory.createClientAnchor();
            anchor.setCol1(cell.getColumnIndex());
            anchor.setCol2(cell.getColumnIndex() + 2);
            anchor.setRow1(cell.getRowIndex());
            anchor.setRow2(cell.getRowIndex() + 3);

            Comment comment = drawing.createCellComment(anchor);
            String text = "AI Modified.\nOriginal: " +
                    (originalValue == null ? "null" : originalValue.toString().substring(0, Math.min(250, originalValue.toString().length())));
            comment.setString(factory.createRichTextString(text));
            cell.setCellComment(comment);
        } catch (Exception ignored) {}
    }

    private CellStyle createBoldStyle(Workbook wb) {
        Font boldFont = wb.createFont();
        boldFont.setBold(true);
        CellStyle style = wb.createCellStyle();
        style.setFont(boldFont);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle createHighlightStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle createLegendStyle(Workbook wb) {
        Font font = wb.createFont();
        font.setBold(true);
        font.setItalic(true);
        font.setColor(IndexedColors.PALE_BLUE.getIndex());

        CellStyle style = wb.createCellStyle();
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.WHITE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        return style;
    }

    private String generateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : encodedHash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
