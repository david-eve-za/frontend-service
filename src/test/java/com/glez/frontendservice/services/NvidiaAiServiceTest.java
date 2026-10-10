package com.glez.frontendservice.services;

import com.openai.errors.OpenAIIoException;
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

    private final NvidiaRateLimiter rateLimiter = new NvidiaRateLimiter(0);

    @BeforeEach
    void setUp() {
        // We don't need to mock the ChatModel for testing private methods
        nvidiaAiService = new NvidiaAiService(null, "model1,model2", 3, rateLimiter);
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

        @Test
        @DisplayName("Should return true for SDK network errors (OpenAIIoException) even with an unhelpful message")
        void isRetryableError_withOpenAiIoException_returnsTrue() {
            Exception exception = new OpenAIIoException("Request failed");
            boolean result = invokeIsRetryableError(exception);
            assertTrue(result, "Transient network failures must rotate the model and retry");
        }
    }

    @Nested
    @DisplayName("buildTranslationPrompt method tests")
    class BuildTranslationPromptTests {

        @Test
        @DisplayName("Should build the multi-step protocol with the languages substituted")
        void buildTranslationPrompt_withParams_returnsFormattedPrompt() {
            String prompt = invokeBuildTranslationPrompt("en", "es", "Hello world");

            assertNotNull(prompt);
            assertTrue(prompt.contains("Translate the provided text from **en** to **es**"));
            assertTrue(prompt.contains("Step 1: Deep Content Analysis and Pre-Correction (en)"));
            assertTrue(prompt.contains("Step 3: High-Fidelity Translation (en to es)"));
            assertTrue(prompt.contains("Step 4: Post-Translation Refinement and Validation (es)"));
            assertTrue(prompt.contains("**Source Language:** en"));
            assertTrue(prompt.contains("**Target Language:** es"));
            assertTrue(prompt.contains("Hello world"), "El chunk viaja al final del prompt");
            assertTrue(prompt.contains("**ONLY** the fully translated, cleaned, and refined narrative text"));
            assertTrue(prompt.contains("Keep the original paragraph breaks EXACTLY"),
                    "Los párrafos alimentan la vista paralela y el TTS: deben preservarse");
        }

        @Test
        @DisplayName("Should not leave unsubstituted placeholders nor a hardcoded target language")
        void buildTranslationPrompt_noPlaceholderOrHardcodedLanguageLeftBehind() {
            String prompt = invokeBuildTranslationPrompt("en", "es", "Hello world");

            assertFalse(prompt.contains("{source_lang}"));
            assertFalse(prompt.contains("{target_lang}"));
            assertFalse(prompt.contains("{{text_chunk}}"));
            assertFalse(prompt.contains("idiomatic Spanish"),
                    "El idioma destino es un parámetro: nada de idiomas hardcodeados");
        }

        @Test
        @DisplayName("Should inject the series glossary into the translation step when present")
        void buildTranslationPrompt_withGlossary_injectsEstablishedTerms() {
            com.glez.frontendservice.model.TranslationTerm term =
                    new com.glez.frontendservice.model.TranslationTerm();
            term.setSourceTerm("Rimuru");
            term.setTargetTerm("Rimuru");

            String prompt = invokeBuildTranslationPrompt("en", "es", "Hello world", java.util.List.of(term));

            assertTrue(prompt.contains("Established series glossary"));
            assertTrue(prompt.contains("- \"Rimuru\" -> \"Rimuru\""));
            assertTrue(prompt.indexOf("Established series glossary") < prompt.indexOf("Cultural Nuances"),
                    "El glosario vive dentro del paso 3 de traducción");
        }

        @Test
        @DisplayName("Should omit the glossary block entirely without terms")
        void buildTranslationPrompt_withoutGlossary_omitsTheBlock() {
            String prompt = invokeBuildTranslationPrompt("en", "es", "Hello world");

            assertFalse(prompt.contains("Established series glossary"));
        }

        @Test
        @DisplayName("Should handle empty text")
        void buildTranslationPrompt_withEmptyText_includesEmptyText() {
            String prompt = invokeBuildTranslationPrompt("en", "es", "");

            assertNotNull(prompt);
            assertTrue(prompt.contains("Text for Processing"));
        }

        @Test
        @DisplayName("Should handle special characters in text")
        void buildTranslationPrompt_withSpecialChars_escapesCorrectly() {
            String prompt = invokeBuildTranslationPrompt("en", "es", "Text with \"quotes\" and 'apostrophes' and 100% sure");

            assertNotNull(prompt);
            assertTrue(prompt.contains("Text with \"quotes\" and 'apostrophes' and 100% sure"));
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
        return invokeBuildTranslationPrompt(sourceLang, targetLang, text, java.util.List.of());
    }

    private String invokeBuildTranslationPrompt(String sourceLang, String targetLang, String text,
                                                java.util.List<com.glez.frontendservice.model.TranslationTerm> glossary) {
        try {
            java.lang.reflect.Method method = NvidiaAiService.class.getDeclaredMethod(
                    "buildTranslationPrompt", String.class, String.class, String.class, java.util.List.class);
            method.setAccessible(true);
            return (String) method.invoke(nvidiaAiService, sourceLang, targetLang, text, glossary);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to invoke buildTranslationPrompt method", ex);
        }
    }

    @Nested
    @DisplayName("Glossary prompt tests")
    class GlossaryPromptTests {

        private com.glez.frontendservice.model.TranslationTerm term(String source, String target) {
            com.glez.frontendservice.model.TranslationTerm term =
                    new com.glez.frontendservice.model.TranslationTerm();
            term.setSourceTerm(source);
            term.setTargetTerm(target);
            return term;
        }

        @Test
        @DisplayName("The prompt injects the glossary terms with their established translations")
        void buildTranslationPrompt_withGlossary_injectsTerms() {
            String prompt = invokeBuildTranslationPrompt("en", "es", "Text",
                    java.util.List.of(term("the Angel Lord", "el Señor de los Ángeles")));

            assertTrue(prompt.contains("Established series glossary"));
            assertTrue(prompt.contains("use EXACTLY these translations"));
            assertTrue(prompt.contains("- \"the Angel Lord\" -> \"el Señor de los Ángeles\""));
        }

        @Test
        @DisplayName("Without glossary the prompt omits the glossary block but keeps the literary persona")
        void buildTranslationPrompt_withoutGlossary_keepsStyle() {
            String prompt = invokeBuildTranslationPrompt("en", "es", "Text");

            assertFalse(prompt.contains("Established series glossary"));
            assertTrue(prompt.contains("WORLD-CLASS TRANSLATOR and a hyper-meticulous LITERARY EDITOR"),
                    "The role/style guidance must always be present to avoid literal translations");
            assertTrue(prompt.contains("fluent and idiomatic in es"));
        }
    }
}