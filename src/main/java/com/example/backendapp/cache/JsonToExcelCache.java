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
                .maximumSize(100)
                .expireAfterAccess(30, TimeUnit.MINUTES)
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
