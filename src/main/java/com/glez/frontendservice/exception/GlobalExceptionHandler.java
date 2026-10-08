package com.glez.frontendservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * SPA fallback: the Angular router owns every non-API path
     * ({@code /pipeline/translate-book}, {@code /logs},
     * {@code /book-translator/glossary}, ...), so a reload or a deep link on
     * such a route must render the application shell — not an error. The
     * forward keeps the browser URL intact so the client router boots into
     * the right page.
     *
     * <p>Anything that is NOT a client route (unknown {@code /api/**}
     * endpoints, missing assets like {@code favicon.ico}) gets a proper
     * 404 JSON instead of the generic 500.</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        String path = request.getRequestURI();
        if (isClientRoute(path)) {
            return new ModelAndView("forward:/index.html");
        }
        Map<String, Object> body = Map.of(
                "message", "Not found.",
                "details", ex.getMessage()
        );
        return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
    }

    /**
     * A client route is any path this app's backend does not own: not an
     * API/Swagger/H2 path and not an asset-like path (a last segment with a
     * file extension, e.g. {@code main.js}, {@code favicon.ico}).
     */
    static boolean isClientRoute(String path) {
        if (path == null) {
            return false;
        }
        for (String owned : OWNED_PREFIXES) {
            if (path.startsWith(owned)) {
                return false;
            }
        }
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        return lastSegment.indexOf('.') < 0;
    }

    private static final String[] OWNED_PREFIXES = {
            "/api", "/api-docs", "/swagger-ui", "/h2-console", "/error"
    };

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
