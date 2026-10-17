package com.glez.frontendservice.services;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NvidiaAiService Tests")
class NvidiaAiServiceTest {

    private NvidiaAiService nvidiaAiService;

    private final NvidiaRateLimiter rateLimiter = new NvidiaRateLimiter(0);

    @Mock
    private ModelInspectorService inspector;

    @Mock
    private AiProviderSettingsService providerSettings;

    @BeforeEach
    void setUp() {
        // We don't need to mock the ChatModel for testing private methods.
        // Presupuesto de salida por defecto: (8192-1024)*3.5/1.15 ≈ 21.8K chars.
        // El inspector reporta NVIDIA online por defecto (los casos de offline
        // lo cambian con when(...)).
        org.mockito.Mockito.lenient().when(inspector.isNvidiaOnline()).thenReturn(true);
        org.mockito.Mockito.lenient().when(providerSettings.translationProvider()).thenReturn("nvidia");
        org.mockito.Mockito.lenient().when(providerSettings.storyProvider()).thenReturn("nvidia");
        nvidiaAiService = new NvidiaAiService(null, "model1,model2", 3, rateLimiter,
                inspector, 8192, 1024, 3.5, 1.15, providerSettings, "", 240, 2);
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

        @Test
        @DisplayName("Should return true for the REAL JDK timeout message 'request timed out'")
        void isRetryableError_withJdkTimedOutMessage_returnsTrue() {
            // HttpTimeoutException.getMessage() es "request timed out" (con 'd'):
            // NO contiene el substring "timeout" — con la keyword antigua, un
            // timeout del JDK mataba el chunk como no-reintentable.
            Exception exception = new RuntimeException("Error calling Ollama model gpt-oss:20b: request timed out");
            boolean result = invokeIsRetryableError(exception);
            assertTrue(result, "The JDK's real timeout wording must be retryable");
        }

        @Test
        @DisplayName("Should return true for transient Ollama failures (5xx/429/connection reset) by type")
        void isRetryableError_withOllamaTransientException_returnsTrue() {
            // El 500 del servidor local (tareas canceladas bajo cola) debe
            // rotar y reintentar: el modelo local es gratis y el reintento
            // casi siempre completa la pieza.
            Exception exception = new NvidiaAiService.OllamaTransientException(
                    "Ollama HTTP 500 for model gpt-oss:20b");
            boolean result = invokeIsRetryableError(exception);
            assertTrue(result, "Ollama 5xx/429/connection failures must rotate the model and retry");
        }

        @Test
        @DisplayName("Should return false for permanent Ollama failures (404: model not pulled)")
        void isRetryableError_withOllamaPermanent404_returnsFalse() {
            Exception exception = new RuntimeException(
                    "Error calling Ollama model gpt-oss:20b: Ollama HTTP 404 for model gpt-oss:20b");
            boolean result = invokeIsRetryableError(exception);
            assertFalse(result, "A missing local model is permanent: retrying only enqueues hours");
        }

        @Test
        @DisplayName("A model EOL (410) is NOT retryable-in-place: it is a gone-model, it must ROTATE")
        void isRetryableError_withModelEol410_returnsFalse() {
            Exception exception = new RuntimeException(
                    "410: {\"status\":410,\"detail\":\"The model 'moonshotai/kimi-k3' has reached its end of life\"}");
            boolean result = invokeIsRetryableError(exception);
            assertFalse(result, "410 belongs to isModelGone (rotate, never retry the same dead model)");
        }
    }

    @Nested
    @DisplayName("isModelGone method tests")
    class IsModelGoneTests {

        @Test
        @DisplayName("410 EOL — the REAL message of the kimi-k3 incident — must rotate instead of killing the piece")
        void isModelGone_withKimiEol410_returnsTrue() {
            Exception exception = new RuntimeException("410: {\"type\":\"about:blank\",\"title\":\"Gone\",\"status\":410,"
                    + "\"detail\":\"The model 'moonshotai/kimi-k3' has reached its end of life on 2026-10-15T08:00:00Z "
                    + "and is no longer available.\"}");
            boolean result = invokeIsModelGone(exception);
            assertTrue(result, "A model EOL must rotate to the next model, never kill the piece");
        }

        @Test
        @DisplayName("SDK-precise detection: OpenAIServiceException statusCode 410/404 are gone, 500 is not")
        void isModelGone_withSdkStatusCode_classifiesCorrectly() {
            OpenAIServiceException exception = org.mockito.Mockito.mock(OpenAIServiceException.class);
            org.mockito.Mockito.lenient().when(exception.statusCode()).thenReturn(410);
            assertTrue(invokeIsModelGone(exception), "410 (EOL) is gone");

            org.mockito.Mockito.lenient().when(exception.statusCode()).thenReturn(404);
            assertTrue(invokeIsModelGone(exception), "404 (not deployed) is gone");

            org.mockito.Mockito.lenient().when(exception.statusCode()).thenReturn(500);
            assertFalse(invokeIsModelGone(exception), "5xx is transient/retryable, not gone");
        }

        @Test
        @DisplayName("Wrapped SDK exception (cause chain) is detected through the generic wrap")
        void isModelGone_withWrappedCause_returnsTrue() {
            OpenAIServiceException gone = org.mockito.Mockito.mock(OpenAIServiceException.class);
            org.mockito.Mockito.lenient().when(gone.statusCode()).thenReturn(410);
            Exception wrapped = new RuntimeException("Error calling model: Gone", gone);
            assertTrue(invokeIsModelGone(wrapped), "The classifier must walk the cause chain (Spring AI wraps SDK errors)");
        }

        @Test
        @DisplayName("Ollama 404 (model not pulled) also rotates: a missing model must not kill the piece")
        void isModelGone_withOllama404_returnsTrue() {
            Exception exception = new RuntimeException(
                    "Error calling Ollama model gpt-oss:20b: Ollama HTTP 404 for model gpt-oss:20b");
            boolean result = invokeIsModelGone(exception);
            assertTrue(result, "A missing local model rotates to the next slot instead of failing the piece");
        }

        @Test
        @DisplayName("Plain request errors (400-style) are neither gone nor retryable")
        void isModelGone_withBadRequest_returnsFalse() {
            Exception exception = new RuntimeException("Invalid request format");
            boolean result = invokeIsModelGone(exception);
            assertFalse(result);
        }

        @Test
        @DisplayName("Null message with no SDK cause is not gone")
        void isModelGone_withNullMessage_returnsFalse() {
            Exception exception = new RuntimeException((String) null);
            boolean result = invokeIsModelGone(exception);
            assertFalse(result);
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

    @Nested
    @DisplayName("Piece time-ceiling self-healing tests")
    class PieceTimeSelfHealingTests {

        @Test
        @DisplayName("countsAsSlowFailure: retryable + at/over the server ceiling; blips and fatals never count")
        void countsAsSlowFailure_classifiesCorrectly() {
            assertTrue(invokeCountsAsSlowFailure(new OpenAIIoException("Request failed"), 243),
                    "The REAL incident signature: an SDK IO failure at the observed ~243s");
            assertTrue(invokeCountsAsSlowFailure(new OpenAIIoException("Request failed"), 240),
                    "Exactly at the ceiling counts");
            assertTrue(invokeCountsAsSlowFailure(new RuntimeException("request timed out"), 250),
                    "Any retryable failure (e.g. a JDK timeout) at the ceiling is slow");
            assertFalse(invokeCountsAsSlowFailure(new OpenAIIoException("Request failed"), 100),
                    "A network blip that fails fast is a normal retryable rotation, never a slow timeout");
            assertFalse(invokeCountsAsSlowFailure(new RuntimeException("Invalid request format"), 300),
                    "Non-retryable failures are never slow timeouts");
        }

        @Test
        @DisplayName("A piece that exceeds the time ceiling is halved and both halves are reassembled")
        void translateWithBudget_withSlowPiece_halvesAndReassembles() {
            NvidiaAiService spy = org.mockito.Mockito.spy(nvidiaAiService);
            String paragraph = "x".repeat(600);
            String bigText = paragraph + "\n\n" + paragraph; // > MIN_SELF_HEAL_PIECE_CHARS (1000)
            org.mockito.Mockito.doThrow(new NvidiaAiService.PieceGenerationTooSlowException("server ceiling"))
                    .doReturn("traducción A")
                    .doReturn("traducción B")
                    .when(spy).callModelWithRotation(org.mockito.ArgumentMatchers.anyString());

            String result = invokeTranslateWithBudget(spy, bigText);

            assertEquals("traducción A\n\ntraducción B", result);
            verify(spy, org.mockito.Mockito.times(3)).callModelWithRotation(
                    org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("A piece at the self-heal floor that is too slow propagates (no infinite halving)")
        void translateWithBudget_withSlowFloorPiece_propagates() {
            NvidiaAiService spy = org.mockito.Mockito.spy(nvidiaAiService);
            org.mockito.Mockito.doThrow(new NvidiaAiService.PieceGenerationTooSlowException("server ceiling"))
                    .when(spy).callModelWithRotation(org.mockito.ArgumentMatchers.anyString());

            assertThrows(NvidiaAiService.PieceGenerationTooSlowException.class,
                    () -> invokeTranslateWithBudget(spy, "texto corto"));
        }

        @Test
        @DisplayName("Regression: the truncated path keeps halving and reassembling (shared translateHalves)")
        void translateWithBudget_withTruncatedPiece_halvesAndReassembles() {
            NvidiaAiService spy = org.mockito.Mockito.spy(nvidiaAiService);
            String paragraph = "y".repeat(600);
            String bigText = paragraph + "\n\n" + paragraph;
            org.mockito.Mockito.doThrow(new NvidiaAiService.TranslationTruncatedException("finish_reason=length"))
                    .doReturn("traducción A")
                    .doReturn("traducción B")
                    .when(spy).callModelWithRotation(org.mockito.ArgumentMatchers.anyString());

            String result = invokeTranslateWithBudget(spy, bigText);

            assertEquals("traducción A\n\ntraducción B", result);
            verify(spy, org.mockito.Mockito.times(3)).callModelWithRotation(
                    org.mockito.ArgumentMatchers.anyString());
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

    private boolean invokeIsModelGone(Exception e) {
        try {
            java.lang.reflect.Method method = NvidiaAiService.class.getDeclaredMethod("isModelGone", Exception.class);
            method.setAccessible(true);
            return (boolean) method.invoke(nvidiaAiService, e);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to invoke isModelGone method", ex);
        }
    }

    private boolean invokeCountsAsSlowFailure(Exception e, long elapsedSeconds) {
        try {
            java.lang.reflect.Method method = NvidiaAiService.class.getDeclaredMethod(
                    "countsAsSlowFailure", Exception.class, long.class);
            method.setAccessible(true);
            return (boolean) method.invoke(nvidiaAiService, e, elapsedSeconds);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to invoke countsAsSlowFailure method", ex);
        }
    }

    /**
     * Invoca el translateWithBudget privado SOBRE UN SPY: las llamadas
     * internas al método público virtual callModelWithRotation pasan por
     * el proxy de Mockito (stubbeables con doThrow/doReturn). El helper
     * DESENVUELVE la InvocationTargetException para que assertThrows vea
     * el tipo original.
     */
    private String invokeTranslateWithBudget(NvidiaAiService target, String text) {
        try {
            java.lang.reflect.Method method = NvidiaAiService.class.getDeclaredMethod(
                    "translateWithBudget", String.class, String.class, String.class, java.util.List.class);
            method.setAccessible(true);
            return (String) method.invoke(target, text, "en", "es", java.util.List.of());
        } catch (java.lang.reflect.InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("Failed to invoke translateWithBudget", ex);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to invoke translateWithBudget", ex);
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

    @Nested
    @DisplayName("Model pool rotation tests")
    class ModelPoolRotationTests {

        @Test
        @DisplayName("rotateModel cycles through every configured model and wraps around")
        void rotateModel_cyclesThroughTheWholePool() throws Exception {
            // Pool de 7 modelos como en app.nvidia.models (glm-5.3 primario
            // + 5 NVIDIA + ollama:gpt-oss:20b como último recurso local).
            String pool = "z-ai/glm-5.3,openai/gpt-oss-20b,nvidia/nemotron-3-super-120b-a12b,"
                    + "z-ai/glm-5.3-flash,deepseek-ai/deepseek-v4.1-flash,moonshotai/kimi-k3,ollama:gpt-oss:20b";
            NvidiaAiService poolService = new NvidiaAiService(null, pool, 3, rateLimiter,
                    inspector, 8192, 1024, 3.5, 1.15, providerSettings, "", 240, 2);

            java.lang.reflect.Field index = NvidiaAiService.class
                    .getDeclaredField("currentModelIndex");
            index.setAccessible(true);

            // Siete rotaciones recorren el pool completo y la octava vuelve al primario.
            for (int i = 1; i <= 7; i++) {
                invokeRotateModel(poolService, 7);
                int current = ((java.util.concurrent.atomic.AtomicInteger) index.get(poolService)).get();
                assertEquals(i % 7, current, "La rotación " + i + " debe aterrizar en " + (i % 7));
            }
        }

        private void invokeRotateModel(NvidiaAiService service, int poolSize) throws Exception {
            java.lang.reflect.Method method = NvidiaAiService.class
                    .getDeclaredMethod("rotateModel", int.class);
            method.setAccessible(true);
            method.invoke(service, poolSize);
        }
    }

    @Nested
    @DisplayName("Provider availability pre-flight tests")
    class ProviderPreFlightTests {

        @Test
        @DisplayName("Provider=ollama (asistente): usa SOLO el pool local, nunca el de NVIDIA")
        void callModelWithRotation_ollamaProvider_usesLocalPool() {
            // Selección del asistente = ollama → la llamada (2 args, tope
            // ampliado = asistente) debe enrutar al pool LOCAL.
            when(providerSettings.storyProvider()).thenReturn("ollama");
            org.mockito.Mockito.lenient().when(inspector.isOllamaOnline()).thenReturn(true);
            // Con el pool local NUNCA se sondea a NVIDIA; lenient porque el
            // estricto lo marcaría como stub no usado.
            org.mockito.Mockito.lenient().when(inspector.isNvidiaOnline()).thenReturn(false);
            String nvidiaPool = "z-ai/glm-5.3,m2,m3";
            String localPool = "ollama:maternion/hy-mt2:30b,ollama:gemma4:26b";
            NvidiaAiService service = new NvidiaAiService(null, nvidiaPool, 1, rateLimiter,
                    inspector, 8192, 1024, 3.5, 1.15, providerSettings, localPool, 240, 2) {
                @Override
                String callOllamaModel(String modelName, String prompt, int numPredict) {
                    return "local:" + modelName;
                }
            };

            String result = service.callModelWithRotation("prompt", 8192);

            // El PRIMER modelo del pool local (hy-mt2) atendió: NVIDIA ni se probó.
            assertEquals("local:maternion/hy-mt2:30b", result);
        }

        @Test
        @DisplayName("Provider=ollama con pool local VACÍO cae al pool de NVIDIA (guard)")
        void callModelWithRotation_ollamaProvider_emptyLocalPool_fallsBackToNvidiaPool() {
            when(providerSettings.translationProvider()).thenReturn("ollama");
            org.mockito.Mockito.lenient().when(inspector.isOllamaOnline()).thenReturn(true);
            org.mockito.Mockito.lenient().when(inspector.isNvidiaOnline()).thenReturn(true);
            NvidiaAiService service = new NvidiaAiService(null, "z-ai/glm-5.3", 1, rateLimiter,
                    inspector, 8192, 1024, 3.5, 1.15, providerSettings, "", 240, 2) {
                @Override
                String callOllamaModel(String modelName, String prompt, int numPredict) {
                    return "no-deberia-llamarse";
                }
            };

            // Sin pool local configurado, el proveedor ollama usa el de NVIDIA.
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> service.callModelWithRotation("prompt"));
            assertTrue(e.getMessage().contains("Non-retryable") || e.getMessage().contains("chatModel"),
                    "El pool NVIDIA (con chatModel null) es el que se intenta: " + e.getMessage());
        }

        @Test
        @DisplayName("An offline NVIDIA provider falls through to the local Ollama model (last resort)")
        void offlineNvidia_fallsThroughToOllama() {
            // NVIDIA offline, Ollama online (local): la rotación salta los 6
            // modelos NVIDIA y cae al 7º — el modelo local de Ollama.
            when(inspector.isNvidiaOnline()).thenReturn(false);
            when(inspector.isOllamaOnline()).thenReturn(true);
            String pool = "z-ai/glm-5.3,m2,m3,m4,m5,m6,ollama:gpt-oss:20b";
            NvidiaAiService service = new NvidiaAiService(null, pool, 3, rateLimiter,
                    inspector, 8192, 1024, 3.5, 1.15, providerSettings, "", 240, 2) {
                @Override
                String callOllamaModel(String modelName, String prompt, int numPredict) {
                    return "Traducción local de Ollama";
                }
            };

            String result = service.callModelWithRotation("test prompt");

            assertEquals("Traducción local de Ollama", result,
                    "El último recurso del pool es el modelo local cuando NVIDIA está offline");
            // 6 sondeos a NVIDIA + al menos 1 a Ollama (el pre-flight local).
            verify(inspector, org.mockito.Mockito.atLeast(6)).isNvidiaOnline();
            verify(inspector, org.mockito.Mockito.atLeast(1)).isOllamaOnline();
        }

        @Test
        @DisplayName("All providers offline fails fast without hanging")
        void allProvidersOffline_failsFast() {
            when(inspector.isNvidiaOnline()).thenReturn(false);
            when(inspector.isOllamaOnline()).thenReturn(false);
            String pool = "m1,m2,m3,m4,m5,m6,ollama:gpt-oss:20b";
            NvidiaAiService offline = new NvidiaAiService(null, pool, 3, rateLimiter,
                    inspector, 8192, 1024, 3.5, 1.15, providerSettings, "", 240, 2);

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> offline.callModelWithRotation("prompt"));

            assertTrue(failure.getMessage().contains("offline"),
                    "Falla rápido cuando TODOS los proveedores están offline: " + failure.getMessage());
        }

        @Test
        @DisplayName("poolModelNames exposes the rotation pool (glm-5.3 first, ollama last)")
        void poolModelNames_exposesThePool() {
            String pool = "z-ai/glm-5.3,openai/gpt-oss-20b,moonshotai/kimi-k3,ollama:gpt-oss:20b";
            NvidiaAiService poolService = new NvidiaAiService(null, pool, 3, rateLimiter,
                    inspector, 8192, 1024, 3.5, 1.15, providerSettings, "", 240, 2);

            java.util.List<String> names = poolService.poolModelNames();
            assertEquals("z-ai/glm-5.3", names.get(0), "glm-5.3 es el PRIMARIO");
            assertEquals("ollama:gpt-oss:20b", names.get(names.size() - 1),
                    "El modelo local de Ollama es el ÚLTIMO recurso");
        }
    }

    @Nested
    @DisplayName("Output budget estimation tests")
    class OutputBudgetEstimationTests {

        @Test
        @DisplayName("maxPieceChars derives from the safe output budget and the heuristics")
        void estimateMaxPieceChars_appliesTheFormula() {
            // (8192 - 1024) * 3.5 / 1.15 = 21818 (con los valores por defecto)
            assertEquals(21_815, nvidiaAiService.estimateMaxPieceChars());
        }

        @Test
        @DisplayName("A tighter budget yields proportionally smaller pieces")
        void estimateMaxPieceChars_scalesWithTheBudget() {
            NvidiaAiService tiny = new NvidiaAiService(null, "m", 1, rateLimiter,
                    inspector, 2_048, 1_024, 3.5, 1.15, providerSettings, "", 240, 2);
            assertEquals(3_116, tiny.estimateMaxPieceChars());
        }

        @Test
        @DisplayName("Projected output tokens apply chars-per-token and expansion")
        void estimateProjectedOutputTokens_appliesTheHeuristics() {
            // 21818 chars / 3.5 * 1.15 = 7167.9 -> 7168 tokens
            assertEquals(7_169, nvidiaAiService.estimateProjectedOutputTokens(21_818));
        }
    }

    @Nested
    @DisplayName("Output budget splitting tests")
    class OutputBudgetSplittingTests {

        private static final int BUDGET = 100;

        @Test
        @DisplayName("A text that fits the budget returns a single paragraph piece")
        void split_smallText_singlePiece() {
            var pieces = nvidiaAiService.splitForOutputBudget("Hello\n\nworld", BUDGET);
            assertEquals(1, pieces.size());
            assertEquals("Hello\n\nworld", pieces.get(0).text());
            assertFalse(pieces.get(0).joinWithSpace());
        }

        @Test
        @DisplayName("Cuts happen ONLY at paragraph boundaries and content is preserved")
        void split_multiParagraph_cutAtParagraphs() {
            String text = "a".repeat(40) + "\n\n" + "b".repeat(40) + "\n\n" + "c".repeat(40);
            var pieces = nvidiaAiService.splitForOutputBudget(text, BUDGET);

            assertEquals(2, pieces.size());
            assertFalse(pieces.get(0).joinWithSpace());
            assertFalse(pieces.get(1).joinWithSpace());
            // Re-ensamblado fiel: las piezas reconstruyen el original.
            assertEquals(text, pieces.stream().map(p -> p.text())
                    .reduce((x, y) -> x + "\n\n" + y).orElseThrow());
            // Cada pieza respeta el presupuesto.
            for (var piece : pieces) {
                assertTrue(piece.text().length() <= BUDGET);
            }
        }

        @Test
        @DisplayName("A giant paragraph without paragraph breaks cuts at sentence boundaries")
        void split_giantParagraph_sentenceCutsMarkedWithSpaceJoin() {
            String text = ("Sentence one ends here. " + "And another one follows. ").repeat(10);

            var pieces = nvidiaAiService.splitForOutputBudget(text, BUDGET);

            assertTrue(pieces.size() > 1);
            for (var piece : pieces) {
                assertTrue(piece.text().length() <= BUDGET);
                assertTrue(piece.joinWithSpace(), "Los cortes intra-párrafo re-ensamblan con espacio");
            }
            // El re-ensamblado con espacios reconstruye el original sin perder contenido.
            String rejoined = pieces.stream().map(p -> p.text())
                    .reduce((x, y) -> x + " " + y).orElseThrow();
            assertEquals(text.strip().replaceAll("\\s+", " "),
                    rejoined.replaceAll("\\s+", " "));
        }

        @Test
        @DisplayName("Blank or empty text degrades to a single piece without breaking")
        void split_blankText_singleEmptyPiece() {
            var pieces = nvidiaAiService.splitForOutputBudget("   ", BUDGET);
            assertEquals(1, pieces.size());
        }
    }

    @Nested
    @DisplayName("Budgeted translation orchestration tests")
    class BudgetedTranslationOrchestrationTests {

        /** Seam: cuenta y sirve las llamadas al modelo sin tocar el HTTP real. */
        private java.util.List<String> servedPrompts = new java.util.ArrayList<>();

        private NvidiaAiService serviceWithCountingSeam(java.util.function.Function<String, String> responder) {
            servedPrompts = new java.util.ArrayList<>();
            return new NvidiaAiService(null, "m", 1, rateLimiter, inspector, 200, 50, 10.0, 1.0, providerSettings, "", 240, 2) {
                @Override
                public String callModelWithRotation(String prompt) {
                    servedPrompts.add(prompt);
                    return responder.apply(prompt);
                }
            };
        }

        @Test
        @DisplayName("A text within the budget translates in a single model call")
        void translate_smallText_singleCall() {
            NvidiaAiService service = serviceWithCountingSeam(p -> "TRAD");
            String out = service.translateChunk("Hello world", "en", "es", java.util.List.of());

            assertEquals("TRAD", out);
            assertEquals(1, servedPrompts.size());
        }

        @Test
        @DisplayName("An oversized multi-paragraph text splits and rejoins with paragraph breaks")
        void translate_oversizedText_splitsAndJoinsWithParagraphs() {
            // Presupuesto del seam: (200-50)*10/1.0 = 1500 chars.
            String text = "a".repeat(900) + "\n\n" + "b".repeat(900);
            NvidiaAiService service = serviceWithCountingSeam(p -> "T" + servedPrompts.size());

            String out = service.translateChunk(text, "en", "es", java.util.List.of());

            assertEquals(2, servedPrompts.size(), "Una llamada por pieza");
            assertEquals("T1\n\nT2", out);
            assertTrue(servedPrompts.get(0).contains("a".repeat(900)));
            assertTrue(servedPrompts.get(1).contains("b".repeat(900)));
        }

        @Test
        @DisplayName("A truncation self-heals: the piece is re-split and retried")
        void translate_truncatedPiece_selfHealsByReSplitting() {
            // Pieza de ~1400 chars (cabe en el presupuesto de 1500 y supera
            // el piso de auto-sanación de 1000), pero el modelo trunca la
            // primera respuesta: se re-divide y reintenta.
            String text = "x".repeat(600) + ". " + "y".repeat(600) + ". " + "z".repeat(200);
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            NvidiaAiService service = new NvidiaAiService(null, "m", 1, rateLimiter, inspector, 200, 50, 10.0, 1.0, providerSettings, "", 240, 2) {
                @Override
                public String callModelWithRotation(String prompt) {
                    if (calls.incrementAndGet() == 1) {
                        throw new NvidiaAiService.TranslationTruncatedException("length");
                    }
                    return "OK[" + calls.get() + "]";
                }
            };

            String out = service.translateChunk(text, "en", "es", java.util.List.of());

            assertTrue(calls.get() > 1, "La pieza truncada se re-dividió y reintentó");
            assertTrue(out.startsWith("OK["), "El reintentó produce traducción: " + out);
        }

        @Test
        @DisplayName("A truncation below the self-healing floor propagates visibly")
        void translate_truncatedTinyPiece_failsVisibly() {
            String text = "tiny text";
            NvidiaAiService service = new NvidiaAiService(null, "m", 1, rateLimiter, inspector, 200, 50, 10.0, 1.0, providerSettings, "", 240, 2) {
                @Override
                public String callModelWithRotation(String prompt) {
                    throw new NvidiaAiService.TranslationTruncatedException("length");
                }
            };

            assertThrows(NvidiaAiService.TranslationTruncatedException.class,
                    () -> service.translateChunk(text, "en", "es", java.util.List.of()));
        }

        @Test
        @DisplayName("A truncated response does not waste rotation retries")
        void truncatedException_isNotRotationRetryable() {
            Exception e = new NvidiaAiService.TranslationTruncatedException(
                    "Response truncated by the model output token limit");

            assertFalse(invokeIsRetryableError(e),
                    "Reintentar el mismo prompt sobredimensionado solo quemaría cuota");
        }
    }
}