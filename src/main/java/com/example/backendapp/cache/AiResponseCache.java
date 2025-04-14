package com.example.backendapp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AiResponseCache {

    private final Cache<String, String> cache;

    public AiResponseCache() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(1000)
                .build();
    }

    public String getCachedResponse(String key) {
        return cache.getIfPresent(key);
    }

    public void cacheResponse(String key, String response) {
        cache.put(key, response);
    }
}
