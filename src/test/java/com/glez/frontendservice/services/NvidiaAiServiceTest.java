package com.glez.frontendservice.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NvidiaAiService Tests")
class NvidiaAiServiceTest {

    private NvidiaAiService nvidiaAiService;

    @BeforeEach
    void setUp() {
        // We don't need to mock the ChatModel for testing private methods
        nvidiaAiService = new NvidiaAiService(null, "model1,model2", 3);
    }

    @Nested
    @DisplayName("isRetryableError method tests")
    class IsRetryableErrorTests {

        @Test
        @DisplayName("Should return true for rate limit errors (429)")
        void isRetryableError_with429Error_returnsTrue() {
            Exception exception = new RuntimeException("Rate limit exceeded: 429");
            boolean result = invokeIsRetryableError(exception);
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true for rate limit text")
        void isRetryableError_withRateLimitText_returnsTrue() {
            Exception exception = new RuntimeException("rate limit exceeded");
            boolean result = invokeIsRetryableError(exception);
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true for quota errors")
        void isRetryableError_withQuotaError_returnsTrue() {
            Exception exception = new RuntimeException("quota exceeded");
            boolean result = invokeIsRetryableError(exception);
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true for exhausted errors")
        void isRetryableError_withExhaustedError_returnsTrue() {
            Exception exception = new RuntimeException("resources exhausted");
            boolean result = invokeIsRetryableError(exception);
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true for timeout errors")
        void isRetryableError_withTimeoutError_returnsTrue() {
            Exception exception = new RuntimeException("request timeout");
            boolean result = invokeIsRetryableError(exception);
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return true for 503 errors")
        void isRetryableError_with503Error_returnsTrue() {
            Exception exception = new RuntimeException("Service unavailable: 503");
            boolean result = invokeIsRetryableError(exception);
            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false for non-retryable errors")
        void isRetryableError_withNonRetryableError_returnsFalse() {
            Exception exception = new RuntimeException("Invalid request format");
            boolean result = invokeIsRetryableError(exception);
            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false for null message")
        void isRetryableError_withNullMessage_returnsFalse() {
            Exception exception = new RuntimeException((String) null);
            boolean result = invokeIsRetryableError(exception);
            assertFalse(result);
        }

        @Test
        @DisplayName("Should be case sensitive (current implementation)")
        void isRetryableError_caseSensitive_returnsFalseForUppercase() {
            Exception exception = new RuntimeException("RATE LIMIT EXCEEDED");
            boolean result = invokeIsRetryableError(exception);
            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("buildTranslationPrompt method tests")
    class BuildTranslationPromptTests {

        @Test
        @DisplayName("Should build correct prompt format")
        void buildTranslationPrompt_withParams_returnsFormattedPrompt() {
            String prompt = invokeBuildTranslationPrompt("en", "es", "Hello world");
            
            assertNotNull(prompt);
            assertTrue(prompt.contains("Translate the following text from en to es"));
            assertTrue(prompt.contains("Hello world"));
            assertTrue(prompt.contains("Maintain the original formatting"));
            assertTrue(prompt.contains("Do not add any explanations"));
        }

        @Test
        @DisplayName("Should handle empty text")
        void buildTranslationPrompt_withEmptyText_includesEmptyText() {
            String prompt = invokeBuildTranslationPrompt("en", "es", "");
            
            assertNotNull(prompt);
            assertTrue(prompt.contains("Text to translate:"));
        }

        @Test
        @DisplayName("Should handle special characters in text")
        void buildTranslationPrompt_withSpecialChars_escapesCorrectly() {
            String prompt = invokeBuildTranslationPrompt("en", "es", "Text with \"quotes\" and 'apostrophes'");
            
            assertNotNull(prompt);
            assertTrue(prompt.contains("Text with \"quotes\" and 'apostrophes'"));
        }
    }

    private boolean invokeIsRetryableError(Exception e) {
        try {
            java.lang.reflect.Method method = NvidiaAiService.class.getDeclaredMethod("isRetryableError", Exception.class);
            method.setAccessible(true);
            return (boolean) method.invoke(nvidiaAiService, e);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to invoke isRetryableError method", ex);
        }
    }

    private String invokeBuildTranslationPrompt(String sourceLang, String targetLang, String text) {
        try {
            java.lang.reflect.Method method = NvidiaAiService.class.getDeclaredMethod("buildTranslationPrompt", String.class, String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(nvidiaAiService, sourceLang, targetLang, text);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to invoke buildTranslationPrompt method", ex);
        }
    }
}