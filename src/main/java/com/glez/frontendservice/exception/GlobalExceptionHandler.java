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
        // Los streams SSE (visor de logs en vivo) fallan de forma natural
        // cuando el cliente se desconecta o la app se apaga: no hay convertidor
        // para serializar un Map como text/event-stream, así que intentar
        // responder aquí solo genera ruido. Se relanza para que el contenedor
        // cierre el stream.
        if (request.getHeader("Accept") != null
                && request.getHeader("Accept").contains("text/event-stream")) {
            throw new RuntimeException(ex);
        }

        Map<String, Object> body = Map.of(
                "message", "An unexpected error occurred.",
                "details", ex.getMessage()
        );
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
