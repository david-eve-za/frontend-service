package com.glez.frontendservice.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalExceptionHandler Tests")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;
    private WebRequest webRequest;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        webRequest = mock(WebRequest.class);
    }

    @Nested
    @DisplayName("handleTranslationException method tests")
    class HandleTranslationExceptionTests {

        @Test
        @DisplayName("Should return 500 with error message for TranslationException")
        void handleTranslationException_returnsInternalServerError() {
            String errorMessage = "Translation service unavailable";
            TranslationException exception = new TranslationException(errorMessage);

            ResponseEntity<Object> response = exceptionHandler.handleTranslationException(exception, webRequest);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertEquals("An error occurred during translation.", body.get("message"));
            assertEquals(errorMessage, body.get("details"));
        }

        @Test
        @DisplayName("Should throw NPE for TranslationException with null message (Map.of doesn't allow null)")
        void handleTranslationException_withNullMessage_throwsNPE() {
            TranslationException exception = new TranslationException((String) null);

            assertThrows(NullPointerException.class, () -> 
                exceptionHandler.handleTranslationException(exception, webRequest));
        }
    }

    @Nested
    @DisplayName("handleGlobalException method tests")
    class HandleGlobalExceptionTests {

        @Test
        @DisplayName("Should return 500 for generic exceptions")
        void handleGlobalException_returnsInternalServerError() {
            String errorMessage = "Unexpected error occurred";
            Exception exception = new RuntimeException(errorMessage);

            ResponseEntity<Object> response = exceptionHandler.handleGlobalException(exception, webRequest);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertNotNull(response.getBody());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertEquals("An unexpected error occurred.", body.get("message"));
            assertEquals(errorMessage, body.get("details"));
        }

        @Test
        @DisplayName("Should throw NPE for exception with null message (Map.of doesn't allow null)")
        void handleGlobalException_withNullMessage_throwsNPE() {
            Exception exception = new RuntimeException((String) null);

            assertThrows(NullPointerException.class, () -> 
                exceptionHandler.handleGlobalException(exception, webRequest));
        }

        @Test
        @DisplayName("Should handle different exception types")
        void handleGlobalException_differentExceptionTypes_handlesAll() {
            Exception[] exceptions = {
                new IllegalArgumentException("Invalid argument"),
                new IllegalStateException("Illegal state"),
                new NullPointerException("Null pointer")
            };

            for (Exception exception : exceptions) {
                ResponseEntity<Object> response = exceptionHandler.handleGlobalException(exception, webRequest);
                assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
                assertNotNull(response.getBody());
            }
        }
    }
}