package com.example.backendapp.service.jsonexcel;

import com.example.backendapp.cache.JsonToExcelCache;
import com.example.backendapp.exception.ConversionException;
import com.example.backendapp.util.CacheKeyUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class JsonToExcelService {

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
            System.out.println("Cache HIT for JSON-to-Excel");
            return Mono.just(cached);
        }

        return rawService.parseJsonFile(file)
                .flatMap(data -> convertInternal(data, cacheKey, useAI))
                .onErrorMap(e -> new ConversionException("Failed to parse uploaded JSON file: " + e.getMessage(), e));
    }


    public Mono<byte[]> convert(Map<String, List<Map<String, Object>>> rawJson, boolean useAI) {
        String cacheKey = CacheKeyUtil.generateJsonToExcelKey(rawJson, useAI);
        byte[] cached = jsonToExcelCache.get(cacheKey);
        if (cached != null) {
            System.out.println("Cache HIT for raw JSON-to-Excel");
            return Mono.just(cached);
        }

        return convertInternal(rawJson, cacheKey, useAI);
    }

    private Mono<byte[]> convertInternal(Map<String, List<Map<String, Object>>> data, String cacheKey, boolean useAI) {
        Mono<byte[]> resultMono = useAI
                ? aiService.enhance(data)
                : rawService.generateExcel(data);

        return resultMono.doOnNext(result -> {
            try {
                jsonToExcelCache.put(cacheKey, result);
                System.out.println("Cached JSON-to-Excel result");
            } catch (Exception e) {
                System.err.println("Failed to cache result: " + e.getMessage());
            }
        });
    }
}
