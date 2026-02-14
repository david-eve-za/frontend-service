package com.glez.frontendservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TranslationException.class)
    public ResponseEntity<Object> handleTranslationException(TranslationException ex, WebRequest request) {
        // Log the exception details for debugging
        // For example: log.error("Translation failed: {}", ex.getMessage(), ex);

        // Return a user-friendly error response
        Map<String, Object> body = Map.of(
                "message", "An error occurred during translation.",
                "details", ex.getMessage()
        );
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGlobalException(Exception ex, WebRequest request) {
        // Log the exception details for debugging
        // For example: log.error("An unexpected error occurred: {}", ex.getMessage(), ex);

        Map<String, Object> body = Map.of(
                "message", "An unexpected error occurred.",
                "details", ex.getMessage()
        );
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
