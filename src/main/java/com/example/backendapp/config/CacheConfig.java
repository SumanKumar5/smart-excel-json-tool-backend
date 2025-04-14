package com.example.backendapp.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)  // Cache expires after 10 minutes if not accessed
                .expireAfterAccess(20, TimeUnit.MINUTES) // Cache expires after 20 minutes of inactivity
                .maximumSize(500)
                .removalListener((key, value, cause) ->
                        System.out.println("❌ Cache entry removed: " + key + " | Reason: " + cause)
                );

        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
                new CaffeineCache("excelToJsonCache", caffeine.build())
        ));

        return cacheManager;
    }
}
