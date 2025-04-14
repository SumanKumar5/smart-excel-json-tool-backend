package com.example.backendapp.service.exceljson;

import com.example.backendapp.cache.ExcelToJsonCache;
import com.example.backendapp.exception.ConversionException;
import com.example.backendapp.util.CacheKeyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class ExcelToJsonService {

    private final RawExcelToJsonService rawService;
    private final AiExcelToJsonService aiService;
    private final ExcelToJsonCache excelToJsonCache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ExcelToJsonService(RawExcelToJsonService rawService,
                              AiExcelToJsonService aiService,
                              ExcelToJsonCache excelToJsonCache) {
        this.rawService = rawService;
        this.aiService = aiService;
        this.excelToJsonCache = excelToJsonCache;
    }

    public Mono<Object> convert(MultipartFile file, boolean useAI) {
        if (file == null || file.isEmpty()) {
            return Mono.error(new ConversionException("Uploaded Excel file is missing or empty."));
        }

        if (file.getSize() > 10 * 1024 * 1024) {
            return Mono.error(new ConversionException("File is too large. Max allowed is 10 MB."));
        }

        String name = file.getOriginalFilename();
        String type = file.getContentType();
        if (name == null || type == null ||
                !(name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".xlsm")) ||
                !(type.equals("application/vnd.ms-excel") ||
                        type.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") ||
                        type.equals("application/vnd.ms-excel.sheet.macroEnabled.12"))) {
            return Mono.error(new ConversionException("Only .xls, .xlsx, or .xlsm Excel files are allowed."));
        }

        try {
            String cacheKey = CacheKeyUtil.generateExcelJsonKey(file, useAI);
            String cachedJson = excelToJsonCache.get(cacheKey);
            if (cachedJson != null) {
                System.out.println("Cache HIT for full Excel-to-JSON");
                try {
                    Object cachedObject = objectMapper.readValue(cachedJson, Object.class);
                    return Mono.just(cachedObject);
                } catch (Exception e) {
                    System.err.println("Failed to parse cached JSON. Reprocessing: " + e.getMessage());
                }
            }
            return rawService.convertAsync(file)
                    .flatMap(rawData -> {
                        Mono<Object> resultMono = useAI
                                ? Mono.fromCallable(() ->
                                objectMapper.readValue(rawData, new com.fasterxml.jackson.core.type.TypeReference<Map<String, List<Map<String, Object>>>>() {
                                })
                        ).flatMap(aiService::enhance)
                                : Mono.fromCallable(() ->
                                objectMapper.readValue(rawData, new com.fasterxml.jackson.core.type.TypeReference<>() {
                                })
                        );

                        return resultMono.doOnNext(result -> {
                            try {
                                String json = objectMapper.writeValueAsString(result);
                                excelToJsonCache.put(cacheKey, json);
                                System.out.println("Cached Excel-to-JSON result");
                            } catch (Exception e) {
                                System.err.println("Failed to cache result: " + e.getMessage());
                            }
                        });
                    });


        } catch (ConversionException ce) {
            return Mono.error(ce);
        } catch (Exception e) {
            return Mono.error(new ConversionException("Excel-to-JSON conversion failed: " + e.getMessage(), e));
        }
    }
}
