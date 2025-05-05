package com.example.backendapp.controller;

import com.example.backendapp.exception.InvalidInputException;
import com.example.backendapp.service.exceljson.ExcelToJsonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.Objects;

@RestController
@RequestMapping("/excel-to-json")
public class ExcelToJsonController {

    private final ExcelToJsonService excelToJsonService;

    @Autowired
    public ExcelToJsonController(ExcelToJsonService excelToJsonService) {
        this.excelToJsonService = excelToJsonService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Object> convertExcelToJson(@RequestParam("file") MultipartFile file,
                                                     @RequestParam(name = "useAI", defaultValue = "false") boolean useAI) {

        if (file == null || file.isEmpty()) {
            return Mono.error(new InvalidInputException("Excel file is missing or empty."));
        }

        String originalFilename = Objects.requireNonNull(file.getOriginalFilename(), "File must have a name");
        String sanitizedFilename = sanitizeFilename(originalFilename).toLowerCase();

        if (!isFilenameSafe(sanitizedFilename)) {
            return Mono.error(new InvalidInputException("Invalid or unsafe file name detected."));
        }

        if (!(sanitizedFilename.endsWith(".xlsx") || sanitizedFilename.endsWith(".xls") || sanitizedFilename.endsWith(".xlsm"))) {
            return Mono.error(new InvalidInputException("Only .xlsx, .xls, and .xlsm Excel files are supported."));
        }

        return excelToJsonService.convert(file, useAI);
    }

    private static String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static boolean isFilenameSafe(String filename) {
        return !(filename.contains("..") || filename.contains("/") || filename.contains("\\"));
    }
}
