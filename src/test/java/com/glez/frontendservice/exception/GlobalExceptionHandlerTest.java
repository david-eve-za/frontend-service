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
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
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

    @Nested
    @DisplayName("handleNoResourceFound method tests (SPA fallback)")
    class HandleNoResourceFoundTests {

        private HttpServletRequest request(String uri) {
            jakarta.servlet.http.HttpServletRequest servlet = mock(jakarta.servlet.http.HttpServletRequest.class);
            Mockito.when(servlet.getRequestURI()).thenReturn(uri);
            return servlet;
        }

        @Test
        @DisplayName("Client-route deep links forward to the SPA shell (index.html)")
        void handleNoResourceFound_clientRoute_forwardsToSpa() {
            NoResourceFoundException exception =
                    new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/pipeline/translate-book", "pipeline/translate-book");

            Object result = exceptionHandler.handleNoResourceFound(exception,
                    request("/pipeline/translate-book"));

            assertInstanceOf(ModelAndView.class, result, "Deep links must render the app shell");
            assertEquals("forward:/index.html", ((ModelAndView) result).getViewName());
        }

        @Test
        @DisplayName("The logs viewer route is also a client route")
        void handleNoResourceFound_logsRoute_forwardsToSpa() {
            NoResourceFoundException exception =
                    new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/logs", "logs");

            Object result = exceptionHandler.handleNoResourceFound(exception, request("/logs"));

            assertInstanceOf(ModelAndView.class, result);
            assertEquals("forward:/index.html", ((ModelAndView) result).getViewName());
        }

        @Test
        @DisplayName("Unknown API endpoints get a proper 404 JSON (not the SPA shell)")
        void handleNoResourceFound_apiPath_returns404Json() {
            NoResourceFoundException exception =
                    new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/unknown", "api/unknown");

            ResponseEntity<Object> response = (ResponseEntity<Object>) exceptionHandler
                    .handleNoResourceFound(exception, request("/api/unknown"));

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertEquals("Not found.", body.get("message"));
        }

        @Test
        @DisplayName("Asset-like paths (file extension) 404 instead of masking broken links")
        void handleNoResourceFound_assetPath_returns404Json() {
            NoResourceFoundException exception =
                    new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/favicon.ico", "favicon.ico");

            ResponseEntity<Object> response = (ResponseEntity<Object>) exceptionHandler
                    .handleNoResourceFound(exception, request("/favicon.ico"));

            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        }

        @Test
        @DisplayName("isClientRoute: backend-owned prefixes are never client routes")
        void isClientRoute_ownedPrefixes_areFalse() {
            assertFalse(GlobalExceptionHandler.isClientRoute("/api/novels-manager"));
            assertFalse(GlobalExceptionHandler.isClientRoute("/api-docs"));
            assertFalse(GlobalExceptionHandler.isClientRoute("/swagger-ui/index.html"));
            assertFalse(GlobalExceptionHandler.isClientRoute("/h2-console"));
            assertFalse(GlobalExceptionHandler.isClientRoute("/error"));
            assertFalse(GlobalExceptionHandler.isClientRoute(null));
        }

        @Test
        @DisplayName("isClientRoute: app sections are client routes")
        void isClientRoute_appPaths_areTrue() {
            assertTrue(GlobalExceptionHandler.isClientRoute("/pipeline/translate-book"));
            assertTrue(GlobalExceptionHandler.isClientRoute("/pipeline/split-text"));
            assertTrue(GlobalExceptionHandler.isClientRoute("/logs"));
            assertTrue(GlobalExceptionHandler.isClientRoute("/book-translator/glossary"));
        }
    }
}