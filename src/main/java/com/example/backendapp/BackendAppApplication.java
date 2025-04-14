package com.example.backendapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class BackendAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendAppApplication.class, args);
    }
}
