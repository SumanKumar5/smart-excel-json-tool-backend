package com.example.backendapp.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class GeminiResponseUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String extractTextFromGeminiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            String text = root.path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text")
                    .asText();

            if (text.startsWith("```json")) {
                return text.replaceFirst("(?s)```json\\s*", "")
                        .replaceFirst("(?s)```\\s*$", "")
                        .trim();
            }

            return text.trim();

        } catch (Exception e) {
            throw new RuntimeException("Failed to extract Gemini response: " + e.getMessage());
        }
    }
}
