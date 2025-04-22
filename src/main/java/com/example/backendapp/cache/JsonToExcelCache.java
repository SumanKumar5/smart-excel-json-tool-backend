package com.example.backendapp.cache;

import com.github.benmanes.caffeine.cache.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class JsonToExcelCache {

    private Cache<String, byte[]> cache;

    @PostConstruct
    public void init() {
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .maximumWeight(50 * 1024 * 1024)
                .weigher((String key, byte[] value) -> value.length)
                .removalListener((String key, byte[] value, RemovalCause cause) ->
                        System.out.println("🗑️ Removed from JsonToExcelCache: " + key + " due to " + cause))
                .build();
    }

    public void put(String key, byte[] excelBytes) {
        cache.put(key, excelBytes);
    }

    public byte[] get(String key) {
        return cache.getIfPresent(key);
    }
}
