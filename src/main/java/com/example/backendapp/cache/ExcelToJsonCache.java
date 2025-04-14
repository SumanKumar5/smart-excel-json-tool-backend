package com.example.backendapp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ExcelToJsonCache {

    private final Cache<String, String> cache;

    public ExcelToJsonCache() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .maximumSize(500)
                .build();
    }

    public String get(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, String json) {
        cache.put(key, json);
    }
}
