package com.example.backendapp.controller;

import com.example.backendapp.exception.InvalidInputException;
import com.example.backendapp.service.jsonexcel.JsonToExcelService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.*;

@RestController
@RequestMapping("/json-to-excel")
public class JsonToExcelController {

    private static final List<String> ALLOWED_JSON_TYPES = List.of(
            MediaType.APPLICATION_JSON_VALUE,
            MediaType.TEXT_PLAIN_VALUE
    );

    private final JsonToExcelService jsonToExcelService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public JsonToExcelController(JsonToExcelService jsonToExcelService) {
        this.jsonToExcelService = jsonToExcelService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<byte[]>> convertJsonFileToExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "useAI", defaultValue = "false") boolean useAI,
            @RequestParam(name = "filename", required = false) String filename) {

        if (file == null || file.isEmpty()) {
            return Mono.error(new InvalidInputException("JSON file is missing or empty."));
        }

        String originalFilename = Objects.requireNonNull(file.getOriginalFilename(), "File must have a name");
        if (!originalFilename.toLowerCase().endsWith(".json")) {
            return Mono.error(new InvalidInputException("Only .json files are supported."));
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_JSON_TYPES.contains(contentType)) {
            return Mono.error(new InvalidInputException("Invalid file type. Allowed: " + String.join(", ", ALLOWED_JSON_TYPES)));
        }

        String finalFilename = (filename == null || filename.isBlank())
                ? originalFilename.replaceAll("(?i)\\.json$", "") + ".xlsx"
                : filename;

        return jsonToExcelService.convert(file, useAI)
                .map(bytes -> createExcelResponse(finalFilename, bytes));
    }

    @PostMapping(value = "/raw", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<byte[]>> convertFlexibleJsonToExcel(
            @RequestBody JsonNode jsonNode,
            @RequestParam(name = "useAI", defaultValue = "false") boolean useAI,
            @RequestParam(name = "filename", defaultValue = "converted.xlsx") String filename) {

        try {
            Map<String, List<Map<String, Object>>> normalized;

            // Case 1: Empty object
            if (jsonNode.isObject() && !jsonNode.fields().hasNext()) {
                throw new InvalidInputException("Empty JSON object is not valid.");
            }

            // Case 2: Flat object → wrap into an array under Sheet1
            else if (jsonNode.isObject() && jsonNode.elements().hasNext() && !jsonNode.elements().next().isContainerNode()) {
                Map<String, Object> row = objectMapper.convertValue(jsonNode, new TypeReference<>() {});
                normalized = Map.of("Sheet1", List.of(row));
            }

            // Case 3: [ {...}, {...} ]
            else if (jsonNode.isArray()) {
                List<Map<String, Object>> rows = objectMapper.convertValue(jsonNode, new TypeReference<>() {});
                normalized = Map.of("Sheet1", rows);
            }

            // Case 4: { "data": { "ID": 1 } } → unwrap nested object
            else if (jsonNode.isObject() && jsonNode.size() == 1) {
                Map.Entry<String, JsonNode> entry = jsonNode.fields().next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();

                if (value.isObject()) {
                    Map<String, Object> row = objectMapper.convertValue(value, new TypeReference<>() {});
                    normalized = Map.of("Sheet1", List.of(row));

                } else if (value.isArray()) {
                    if (value.isEmpty()) {
                        normalized = Map.of(key, List.of());
                    } else if (value.get(0).isObject()) {
                        List<Map<String, Object>> rows = objectMapper.convertValue(value, new TypeReference<>() {});
                        normalized = Map.of(key, rows);
                    } else {
                        // Case: { "data": [123, 456] }
                        List<Map<String, Object>> wrapped = new ArrayList<>();
                        for (JsonNode item : value) {
                            Map<String, Object> row = Map.of(key, objectMapper.convertValue(item, Object.class));
                            wrapped.add(row);
                        }
                        normalized = Map.of("Sheet1", wrapped);
                    }
                } else {
                    throw new InvalidInputException("Unsupported nested structure inside key: " + key);
                }
            }

            // Case 5: Map of sheets
            else if (jsonNode.isObject()) {
                normalized = objectMapper.convertValue(jsonNode, new TypeReference<>() {});
            }

            else {
                return Mono.error(new InvalidInputException("Unsupported JSON structure. Must be an object, array of objects, or a map of arrays."));
            }

            return jsonToExcelService.convert(normalized, useAI)
                    .map(bytes -> createExcelResponse(filename, bytes));

        } catch (Exception e) {
            return Mono.error(new InvalidInputException("Failed to parse input JSON: " + e.getMessage()));
        }
    }

    private ResponseEntity<byte[]> createExcelResponse(String filename, byte[] excelBytes) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .header("X-Cache-Hit", "false")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }
}
