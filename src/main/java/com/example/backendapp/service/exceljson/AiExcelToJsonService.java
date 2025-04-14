package com.example.backendapp.service.exceljson;

import com.example.backendapp.cache.AiResponseCache;
import com.example.backendapp.config.GeminiConfig;
import com.example.backendapp.exception.AIProcessingException;
import com.example.backendapp.util.GeminiResponseUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class AiExcelToJsonService {

    private static final Logger log = LoggerFactory.getLogger(AiExcelToJsonService.class);
    private static final int MAX_INPUT_LENGTH = 30000;
    private static final int CHUNK_SIZE = 100;

    private final WebClient webClient;
    private final AiResponseCache aiResponseCache;
    private final GeminiConfig geminiConfig;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiExcelToJsonService(WebClient.Builder webClientBuilder,
                                AiResponseCache aiResponseCache,
                                GeminiConfig geminiConfig) {
        this.webClient = webClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.aiResponseCache = aiResponseCache;
        this.geminiConfig = geminiConfig;
        this.objectMapper = new ObjectMapper();
    }

    public Mono<Object> enhance(Map<String, List<Map<String, Object>>> workbookData) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(workbookData))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(rawJson -> {
                    if (rawJson.length() > MAX_INPUT_LENGTH * 10) {
                        log.warn("Input JSON too large even for chunked Gemini processing.");
                        return Mono.error(new AIProcessingException("Excel data is too large for AI chunked processing."));
                    }

                    String cached = aiResponseCache.getCachedResponse(rawJson);
                    if (cached != null) {
                        return Mono.fromCallable(() -> objectMapper.readValue(cached, Object.class))
                                .subscribeOn(Schedulers.boundedElastic())
                                .onErrorResume(e -> {
                                    log.warn("Failed to deserialize cached AI response. Will reprocess.", e);
                                    return Mono.empty();
                                });
                    }

                    List<Mono<Tuple3<String, Integer, List<Map<String, Object>>>>> chunkMonos = new ArrayList<>();

                    for (Map.Entry<String, List<Map<String, Object>>> entry : workbookData.entrySet()) {
                        String sheetName = entry.getKey();
                        List<Map<String, Object>> rows = entry.getValue();

                        List<List<Map<String, Object>>> chunks = IntStream.range(0, (rows.size() + CHUNK_SIZE - 1) / CHUNK_SIZE)
                                .mapToObj(i -> rows.subList(i * CHUNK_SIZE, Math.min(rows.size(), (i + 1) * CHUNK_SIZE)))
                                .toList();

                        for (int i = 0; i < chunks.size(); i++) {
                            List<Map<String, Object>> chunk = chunks.get(i);
                            chunkMonos.add(enhanceChunk(sheetName, i, chunk));
                        }
                    }

                    return Flux.merge(chunkMonos)
                            .collectList()
                            .flatMap(results -> {
                                Map<String, List<Tuple3<String, Integer, List<Map<String, Object>>>>> chunkGroups = new HashMap<>();
                                for (Tuple3<String, Integer, List<Map<String, Object>>> tuple : results) {
                                    chunkGroups.computeIfAbsent(tuple.getT1(), k -> new ArrayList<>()).add(tuple);
                                }

                                Map<String, List<Map<String, Object>>> finalResult = new LinkedHashMap<>();
                                for (String sheetName : workbookData.keySet()) {
                                    List<Tuple3<String, Integer, List<Map<String, Object>>>> sheetChunks = chunkGroups.get(sheetName);
                                    if (sheetChunks == null) continue;

                                    List<Map<String, Object>> orderedRows = sheetChunks.stream()
                                            .sorted(Comparator.comparing(Tuple3::getT2))
                                            .flatMap(t -> t.getT3().stream())
                                            .collect(Collectors.toList());

                                    finalResult.put(sheetName, orderedRows);
                                }

                                return Mono.fromCallable(() -> objectMapper.writeValueAsString(finalResult))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .doOnNext(json -> aiResponseCache.cacheResponse(rawJson, json))
                                        .thenReturn(finalResult);
                            });
                })
                .onErrorResume(e -> {
                    log.error("Chunked AI enhancement failed", e);
                    return Mono.error(new AIProcessingException("Chunked AI enhancement failed: " + e.getMessage()));
                });
    }

    private Mono<Tuple3<String, Integer, List<Map<String, Object>>>> enhanceChunk(String sheetName, int chunkIndex, List<Map<String, Object>> chunk) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(Map.of(sheetName, chunk)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(chunkJson -> {
                    if (chunkJson.length() > MAX_INPUT_LENGTH) {
                        log.warn("Chunk too large for Gemini ({} chars)", chunkJson.length());
                        return Mono.error(new AIProcessingException("A chunk is too large for Gemini."));
                    }

                    String prompt = """
                            You are an AI assistant. The input is a part of an Excel workbook in JSON format.
                            Standardize data types, clean the content, and preserve the structure.
                            Return the result as pure JSON.

                            Input:
                            """ + chunkJson;

                    return webClient.post()
                            .uri(uriBuilder -> uriBuilder
                                    .path(geminiConfig.buildModelPath())
                                    .queryParam("key", geminiConfig.getApiKey())
                                    .build())
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(buildRequestBody(prompt))
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(90))
                            .map(GeminiResponseUtil::extractTextFromGeminiResponse)
                            .flatMap(text -> {
                                if (text == null || text.isBlank()) {
                                    return Mono.error(new AIProcessingException("Gemini returned empty response."));
                                }
                                return Mono.fromCallable(() -> {
                                            Map<String, List<Map<String, Object>>> parsed =
                                                    objectMapper.readValue(text, new TypeReference<>() {});
                                            List<Map<String, Object>> enhancedRows =
                                                    parsed.getOrDefault(sheetName, Collections.emptyList());
                                            return Tuples.of(sheetName, chunkIndex, enhancedRows);
                                        })
                                        .subscribeOn(Schedulers.boundedElastic());
                            })
                            .onErrorResume(error -> {
                                if (error instanceof WebClientResponseException ex) {
                                    log.error("Gemini API error ({}): {}", ex.getStatusCode().value(), ex.getResponseBodyAsString());
                                    return Mono.error(new AIProcessingException("Gemini API failed for chunk: " + ex.getMessage()));
                                }
                                return Mono.error(error);
                            });
                })
                .onErrorResume(e -> {
                    log.error("Failed to process chunk for sheet: {}", sheetName, e);
                    return Mono.error(new AIProcessingException("Chunk processing error: " + e.getMessage()));
                });
    }

    private Map<String, Object> buildRequestBody(String prompt) {
        return Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                )
        );
    }
}
