package com.example.backendapp.service.exceljson;

import com.example.backendapp.cache.ExcelToJsonCache;
import com.example.backendapp.exception.ConversionException;
import com.example.backendapp.util.CacheKeyUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@Service
public class ExcelToJsonService {

    private static final Logger log = LoggerFactory.getLogger(ExcelToJsonService.class);

    private final RawExcelToJsonService rawService;
    private final AiExcelToJsonService aiService;
    private final ExcelToJsonCache excelToJsonCache;
    private final ObjectMapper objectMapper;

    @Autowired
    public ExcelToJsonService(RawExcelToJsonService rawService,
                              AiExcelToJsonService aiService,
                              ExcelToJsonCache excelToJsonCache,
                              ObjectMapper objectMapper) {
        this.rawService = rawService;
        this.aiService = aiService;
        this.excelToJsonCache = excelToJsonCache;
        this.objectMapper = objectMapper;
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
                log.info("Cache HIT for full Excel-to-JSON");
                return Mono.fromCallable(() -> objectMapper.readValue(cachedJson, Object.class))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(e -> {
                            log.warn("Failed to parse cached JSON. Reprocessing: {}", e.getMessage());
                            return Mono.empty();
                        });
            }

            return rawService.convertAsync(file)
                    .flatMap(rawData -> {
                        Mono<Object> resultMono = useAI
                                ? Mono.fromCallable(() ->
                                        objectMapper.readValue(rawData, new TypeReference<Map<String, List<Map<String, Object>>>>() {}))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(aiService::enhance)
                                : Mono.fromCallable(() ->
                                        objectMapper.readValue(rawData, new TypeReference<>() {}))
                                .subscribeOn(Schedulers.boundedElastic());

                        return resultMono.flatMap(result ->
                                Mono.fromCallable(() -> {
                                    String json = objectMapper.writeValueAsString(result);
                                    excelToJsonCache.put(cacheKey, json);
                                    log.info("Cached Excel-to-JSON result");
                                    return result;
                                }).subscribeOn(Schedulers.boundedElastic())
                        );
                    });

        } catch (ConversionException ce) {
            return Mono.error(ce);
        } catch (Exception e) {
            return Mono.error(new ConversionException("Excel-to-JSON conversion failed: " + e.getMessage(), e));
        }
    }
}
