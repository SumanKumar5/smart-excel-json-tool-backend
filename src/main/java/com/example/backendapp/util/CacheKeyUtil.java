package com.example.backendapp.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Formatter;
import java.util.List;
import java.util.Map;

public class CacheKeyUtil {

    public static String generateExcelJsonKey(MultipartFile file, boolean useAI) {
        String filename = file.getOriginalFilename();
        long size = file.getSize();
        String key = String.format("excel-to-json:%s:%d:%b", filename, size, useAI);
        System.out.println("Excel-to-JSON Cache Key = " + key);
        return key;
    }

    public static String generateJsonToExcelKey(MultipartFile file, boolean useAI) {
        String filename = file.getOriginalFilename();
        long size = file.getSize();
        String key = String.format("json-to-excel:file:%s:%d:%b", filename, size, useAI);
        System.out.println("JSON-to-Excel Cache Key (file) = " + key);
        return key;
    }

    public static String generateJsonToExcelKey(Map<String, List<Map<String, Object>>> jsonData, boolean useAI) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String rawJson = mapper.writeValueAsString(jsonData);
            String hash = sha256(rawJson);
            String key = String.format("json-to-excel:raw:%s:%b", hash, useAI);
            System.out.println("JSON-to-Excel Cache Key (raw) = " + key);
            return key;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate cache key from raw JSON", e);
        }
    }

    public static String generateSchemaKey(Map<String, List<Map<String, Object>>> previewData) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String previewJson = mapper.writeValueAsString(previewData);
            String hash = sha256(previewJson);
            String key = "schema:" + hash;
            System.out.println("Schema Cache Key = " + key);
            return key;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate schema cache key", e);
        }
    }

    public static String generateSchemaKey(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            Formatter formatter = new Formatter();
            for (byte b : digest) {
                formatter.format("%02x", b);
            }
            String key = "schema-file:" + formatter;
            System.out.println("Schema Cache Key (file) = " + key);
            return key;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate schema cache key from file", e);
        }
    }


    private static String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        Formatter formatter = new Formatter();
        for (byte b : digest) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }
}
