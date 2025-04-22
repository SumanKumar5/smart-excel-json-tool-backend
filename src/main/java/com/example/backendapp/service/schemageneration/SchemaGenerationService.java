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

    private record SchemaRequest(String previewJson, String fileKey, String semanticKey) {}

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
                        return Mono.fromCallable(() ->
                                objectMapper.readValue(cachedFromFileKey, Object.class)
                        );
                    }

                    Map<String, List<Map<String, Object>>> previewData = ExcelPreviewUtil.extractPreview(file);
                    String semanticKey = CacheKeyUtil.generateSchemaKey(previewData);
                    String cachedFromPreview = aiResponseCache.getCachedResponse(semanticKey);

                    if (cachedFromPreview != null) {
                        logger.info("Semantic preview-based cache HIT for key: {}", semanticKey);
                        return Mono.fromCallable(() ->
                                objectMapper.readValue(cachedFromPreview, Object.class)
                        );
                    }

                    logger.info("Cache MISS. Calling Gemini API for schema generation...");
                    String previewJson = objectMapper.writeValueAsString(previewData);
                    return generateSchemaFromGemini(new SchemaRequest(previewJson, fileKey, semanticKey));
                })
                .flatMap(mono -> mono)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> new AIProcessingException(
                        "Schema generation failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e));
    }

    private Mono<Object> generateSchemaFromGemini(SchemaRequest request) {
        Map<String, Object> requestBody = buildRequestBody(request.previewJson());

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
                    logger.info("Caching Gemini response under both keys.");
                    aiResponseCache.cacheResponse(request.fileKey(), response);
                    aiResponseCache.cacheResponse(request.semanticKey(), response);
                })
                .flatMap(response -> Mono.fromCallable(() -> objectMapper.readValue(response, Object.class)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> buildRequestBody(String previewJson) {
        String prompt = """
                Infer JSON Schema from this Excel data (in JSON). Output only the schema:
                """ + previewJson;

        return Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                )
        );
    }
}
