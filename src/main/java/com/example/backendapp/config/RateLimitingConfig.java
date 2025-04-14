package com.example.backendapp.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.micrometer.common.lang.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
public class RateLimitingConfig {

    private static final int TOO_MANY_REQUESTS = 429;

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Bean
    public OncePerRequestFilter rateLimitingFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(@NonNull HttpServletRequest request,
                                            @NonNull HttpServletResponse response,
                                            @NonNull FilterChain filterChain)
                    throws ServletException, IOException {

                String ip = request.getRemoteAddr();
                Bucket bucket = buckets.computeIfAbsent(ip, this::newBucket);

                if (bucket.tryConsume(1)) {
                    filterChain.doFilter(request, response);
                } else {
                    response.setStatus(TOO_MANY_REQUESTS);
                    response.getWriter().write("Too many requests - try again later");
                }
            }

            private Bucket newBucket(String key) {
                Bandwidth limit = Bandwidth.classic(
                        100,
                        Refill.greedy(100, Duration.ofMinutes(1))
                );
                return Bucket.builder()
                        .addLimit(limit)
                        .build();
            }
        };
    }
}
