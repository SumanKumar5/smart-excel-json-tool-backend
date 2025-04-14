package com.example.backendapp.controller;

import com.example.backendapp.exception.InvalidInputException;
import com.example.backendapp.service.jsonexcel.JsonToExcelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/json-to-excel")
public class JsonToExcelController {

    private static final List<String> ALLOWED_JSON_TYPES = List.of(
            MediaType.APPLICATION_JSON_VALUE,
            MediaType.TEXT_PLAIN_VALUE
    );

    private final JsonToExcelService jsonToExcelService;

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
    public Mono<ResponseEntity<byte[]>> convertRawJsonToExcel(
            @RequestBody Map<String, List<Map<String, Object>>> rawJson,
            @RequestParam(name = "useAI", defaultValue = "false") boolean useAI,
            @RequestParam(name = "filename", defaultValue = "converted.xlsx") String filename) {

        if (rawJson == null || rawJson.isEmpty()) {
            return Mono.error(new InvalidInputException("JSON body is empty or invalid."));
        }

        return jsonToExcelService.convert(rawJson, useAI)
                .map(bytes -> createExcelResponse(filename, bytes));
    }

    private ResponseEntity<byte[]> createExcelResponse(String filename, byte[] excelBytes) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .header("X-Cache-Hit", "false")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelBytes);
    }
}
