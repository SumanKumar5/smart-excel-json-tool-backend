package com.example.backendapp.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<Map<String, String>> handleInvalidInput(InvalidInputException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid Input",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(AIProcessingException.class)
    public ResponseEntity<Map<String, String>> handleAIError(AIProcessingException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "AI Processing Error",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(ConversionException.class)
    public ResponseEntity<Map<String, String>> handleConversionError(ConversionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Conversion Error",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAll(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Error",
                "message", ex.getMessage()
        ));
    }
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "error", "Rate Limit Exceeded",
                "message", ex.getMessage()
        ));
    }
}
