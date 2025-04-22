package com.example.backendapp.cache;

import com.github.benmanes.caffeine.cache.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AiResponseCache {

    private final Cache<String, String> cache;

    public AiResponseCache() {
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .maximumWeight(50 * 1024 * 1024)
                .weigher((String key, String value) -> value.getBytes().length)
                .removalListener((String key, String value, RemovalCause cause) ->
                        System.out.println("🗑️ Removed from AiResponseCache: " + key + " due to " + cause))
                .build();
    }

    public String getCachedResponse(String key) {
        return cache.getIfPresent(key);
    }

    public void cacheResponse(String key, String response) {
        cache.put(key, response);
    }
}
