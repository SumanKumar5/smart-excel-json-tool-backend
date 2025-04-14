package com.example.backendapp.service.schemageneration;

import com.example.backendapp.cache.AiResponseCache;
import com.example.backendapp.config.GeminiConfig;
import com.example.backendapp.exception.AIProcessingException;
import com.example.backendapp.util.CacheKeyUtil;
import com.example.backendapp.util.ExcelPreviewUtil;
import com.example.backendapp.util.GeminiResponseUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@Service
public class SchemaGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(SchemaGenerationService.class);

    private final WebClient webClient;
    private final GeminiConfig geminiConfig;
    private final AiResponseCache aiResponseCache;
    private final ObjectMapper objectMapper;

    @Autowired
    public SchemaGenerationService(WebClient.Builder webClientBuilder,
                                   GeminiConfig geminiConfig,
                                   AiResponseCache aiResponseCache,
                                   ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.geminiConfig = geminiConfig;
        this.aiResponseCache = aiResponseCache;
        this.objectMapper = objectMapper;
    }

    public Mono<Object> generate(MultipartFile file) {
        return Mono.fromCallable(() -> {
                    if (file == null || file.isEmpty()) {
                        throw new IllegalArgumentException("Uploaded file is empty or missing.");
                    }

                    String fileKey = CacheKeyUtil.generateSchemaKey(file);
                    String cachedFromFileKey = aiResponseCache.getCachedResponse(fileKey);
                    if (cachedFromFileKey != null) {
                        logger.info("File-based cache HIT for key: {}", fileKey);
                        Object schema = objectMapper.readValue(cachedFromFileKey, Object.class);
                        return Map.of("cachedSchema", schema);
                    } else {
                        logger.info("File-based cache MISS for key: {}", fileKey);
                    }

                    Map<String, List<Map<String, Object>>> previewData = ExcelPreviewUtil.extractPreview(file);
                    String semanticKey = CacheKeyUtil.generateSchemaKey(previewData);
                    String cachedFromPreview = aiResponseCache.getCachedResponse(semanticKey);

                    if (cachedFromPreview != null) {
                        logger.info("Semantic preview-based cache HIT for key: {}", semanticKey);
                        Object schema = objectMapper.readValue(cachedFromPreview, Object.class);
                        return Map.of("cachedSchema", schema);
                    } else {
                        logger.info("Semantic preview-based cache MISS for key: {}", semanticKey);
                    }

                    String previewJson = objectMapper.writeValueAsString(previewData);
                    return Map.of("previewJson", previewJson, "fileKey", fileKey, "semanticKey", semanticKey);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dataMap = (Map<String, Object>) result;

                    if (dataMap.containsKey("cachedSchema")) {
                        return Mono.just(dataMap.get("cachedSchema"));
                    }

                    String previewJson = (String) dataMap.get("previewJson");
                    String fileKey = (String) dataMap.get("fileKey");
                    String semanticKey = (String) dataMap.get("semanticKey");

                    Map<String, Object> requestBody = buildRequestBody(previewJson);

                    logger.info("Calling Gemini API to generate schema...");

                    return webClient.post()
                            .uri(uriBuilder -> uriBuilder
                                    .path(geminiConfig.buildModelPath())
                                    .queryParam("key", geminiConfig.getApiKey())
                                    .build())
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(requestBody)
                            .retrieve()
                            .bodyToMono(String.class)
                            .map(GeminiResponseUtil::extractTextFromGeminiResponse)
                            .doOnNext(response -> {
                                logger.info("Caching Gemini response under both keys");
                                aiResponseCache.cacheResponse(fileKey, response);
                                aiResponseCache.cacheResponse(semanticKey, response);
                            })
                            .flatMap(response -> {
                                try {
                                    return Mono.just(objectMapper.readValue(response, Object.class));
                                } catch (Exception e) {
                                    return Mono.error(new AIProcessingException("Failed to parse AI schema response: " + e.getMessage()));
                                }
                            });
                })
                .onErrorMap(e -> new AIProcessingException(
                        "Schema generation failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e));
    }

    private Map<String, Object> buildRequestBody(String previewJson) {
        String prompt = """
                You are a JSON Schema generator.
                The following is a sample of Excel data (converted to JSON).
                Please infer and generate a JSON Schema based on this structure.

                Output only the JSON Schema (no explanation).

                Input:
                """ + previewJson;

        return Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                )
        );
    }
}
