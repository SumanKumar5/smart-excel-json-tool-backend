package com.example.backendapp.cache;

import com.github.benmanes.caffeine.cache.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ExcelToJsonCache {

    private final Cache<String, String> cache;

    public ExcelToJsonCache() {
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .maximumWeight(50 * 1024 * 1024)
                .weigher((String key, String value) -> value.getBytes().length)
                .removalListener((String key, String value, RemovalCause cause) ->
                        System.out.println("🗑️ Removed from ExcelToJsonCache: " + key + " due to " + cause))
                .build();
    }

    public String get(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, String json) {
        cache.put(key, json);
    }
}
