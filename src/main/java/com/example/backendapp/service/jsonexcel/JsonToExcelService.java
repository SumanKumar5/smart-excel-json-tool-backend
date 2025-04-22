package com.example.backendapp.service.jsonexcel;

import com.example.backendapp.cache.JsonToExcelCache;
import com.example.backendapp.exception.ConversionException;
import com.example.backendapp.util.CacheKeyUtil;
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
public class JsonToExcelService {

    private static final Logger log = LoggerFactory.getLogger(JsonToExcelService.class);

    private final RawJsonToExcelService rawService;
    private final AiJsonToExcelService aiService;
    private final JsonToExcelCache jsonToExcelCache;

    @Autowired
    public JsonToExcelService(RawJsonToExcelService rawService,
                              AiJsonToExcelService aiService,
                              JsonToExcelCache jsonToExcelCache) {
        this.rawService = rawService;
        this.aiService = aiService;
        this.jsonToExcelCache = jsonToExcelCache;
    }

    public Mono<byte[]> convert(MultipartFile file, boolean useAI) {
        String cacheKey = CacheKeyUtil.generateJsonToExcelKey(file, useAI);
        byte[] cached = jsonToExcelCache.get(cacheKey);
        if (cached != null) {
            log.info("Cache HIT for JSON-to-Excel (file input)");
            return Mono.just(cached);
        }

        return rawService.parseJsonFile(file)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(data -> convertInternal(data, cacheKey, useAI))
                .onErrorMap(e -> new ConversionException("Failed to parse uploaded JSON file: " + e.getMessage(), e));
    }

    public Mono<byte[]> convert(Map<String, List<Map<String, Object>>> rawJson, boolean useAI) {
        String cacheKey = CacheKeyUtil.generateJsonToExcelKey(rawJson, useAI);
        byte[] cached = jsonToExcelCache.get(cacheKey);
        if (cached != null) {
            log.info("Cache HIT for raw JSON-to-Excel (JSON input)");
            return Mono.just(cached);
        }

        return convertInternal(rawJson, cacheKey, useAI);
    }

    private Mono<byte[]> convertInternal(Map<String, List<Map<String, Object>>> data, String cacheKey, boolean useAI) {
        Mono<byte[]> resultMono = useAI
                ? aiService.enhance(data)
                : rawService.generateExcel(data);

        return resultMono
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result ->
                        Mono.fromCallable(() -> {
                            jsonToExcelCache.put(cacheKey, result);
                            log.info("Cached JSON-to-Excel result");
                            return result;
                        }).subscribeOn(Schedulers.boundedElastic())
                );
    }
}
