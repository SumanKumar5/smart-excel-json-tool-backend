package com.example.backendapp.controller;

import com.example.backendapp.exception.InvalidInputException;
import com.example.backendapp.model.ResponseModel;
import com.example.backendapp.service.schemageneration.SchemaGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/generate-schema")
public class SchemaGenerationController {

    private static final Logger log = LoggerFactory.getLogger(SchemaGenerationController.class);

    private static final List<String> ALLOWED_EXCEL_TYPES = List.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.ms-excel.sheet.macroEnabled.12"
    );

    private final SchemaGenerationService schemaGenerationService;

    @Autowired
    public SchemaGenerationController(SchemaGenerationService schemaGenerationService) {
        this.schemaGenerationService = schemaGenerationService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseModel<?>> generateSchemaFromExcel(@RequestParam("file") MultipartFile file) {

        log.info("Received request to /generate-schema. File name: '{}', Size: {} bytes",
                file != null ? file.getOriginalFilename() : "null",
                file != null ? file.getSize() : 0);

        if (file == null || file.isEmpty()) {
            log.warn("Validation failed: Uploaded file is missing or empty.");
            return Mono.error(new InvalidInputException("Please upload a non-empty Excel file."));
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_EXCEL_TYPES.contains(contentType)) {
            log.warn("Validation failed: Unsupported content type '{}'. Allowed types: {}", contentType, ALLOWED_EXCEL_TYPES);
            return Mono.error(new InvalidInputException("Unsupported file type. Please upload an Excel file (.xlsx, .xls, .xlsm)."));
        }

        return schemaGenerationService.generate(file)
                .map(result -> {
                    log.info("Schema generation successful for file '{}'", file.getOriginalFilename());
                    return new ResponseModel<>(result);
                });
    }
}
