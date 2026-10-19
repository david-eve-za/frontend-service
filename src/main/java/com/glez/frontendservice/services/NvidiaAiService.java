package com.glez.frontendservice.services;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Traducción vía la API de NVIDIA con tres salvaguardas:
 *
 * <p><b>Presupuesto de salida</b> — el límite que corrompe una traducción
 * no es el input (contexto de 131K) sino los tokens que el modelo puede
 * <i>responder</i>: la traducción visible y el razonamiento interno del
 * modelo comparten el mismo cupo de salida. {@link #translateChunk} estima
 * la respuesta proyectada y divide el texto en piezas que caben
 * cómodamente en el presupuesto, cortando SIEMPRE en fronteras de párrafo
 * (los saltos alimentan la vista paralela, el TTS y el manuscrito);
 * cada pieza se traduce en su propia llamada y se re-ensambla fielmente.
 * Si a pesar de la estimación el modelo trunca la respuesta
 * ({@code finish_reason=length}), la pieza se re-divide a la mitad
 * automáticamente hasta el piso {@link #MIN_SELF_HEAL_PIECE_CHARS},
 * donde el fallo se propaga visiblemente en vez de persistir una
 * traducción corrupta.</p>
 */
@Service
public class NvidiaAiService {

    private static final Logger log = LoggerFactory.getLogger(NvidiaAiService.class);

    /**
     * Piso de la auto-sanación: por debajo de este tamaño una pieza que
     * aún se trunca indica un cupo de salida roto (o un razonamiento
     * desbocado) — re-dividir más no ayudaría, así que el fallo sube y el
     * chunk queda FAILED visiblemente.
     */
    static final int MIN_SELF_HEAL_PIECE_CHARS = 1_000;

    private final OpenAiChatModel chatModel;
    private final List<String> modelNames;
    /** Pool 100% local (app.ollama.models) para el proveedor "ollama". */
    private final List<String> ollamaModelNames;
    private final AtomicInteger currentModelIndex = new AtomicInteger(0);
    private final int maxRetries;
    private final NvidiaRateLimiter rateLimiter;
    private final ModelInspectorService inspector;
    /** Selección de proveedor por caso de uso (traducción / asistente). */
    private final AiProviderSettingsService providerSettings;
    private final int maxOutputTokens;
    private final int reasoningReserveTokens;
    private final double charsPerToken;
    private final double expansionFactor;
    /** Techo de tiempo del servidor: una generación mayor muere (~243 s como "Request failed"). */
    private final int serverCeilingSeconds;
    /** Fallos al techo tolerados antes de re-dividir la pieza (auto-sanado por tiempo). */
    private final int maxSlowFailures;

    public NvidiaAiService(
            OpenAiChatModel chatModel,
            @Value("${app.nvidia.models}") String models,
            @Value("${app.nvidia.max-retries:3}") int maxRetries,
            NvidiaRateLimiter rateLimiter,
            ModelInspectorService inspector,
            @Value("${app.nvidia.max-output-tokens:8192}") int maxOutputTokens,
            @Value("${app.nvidia.reasoning-reserve-tokens:1024}") int reasoningReserveTokens,
            @Value("${app.nvidia.chars-per-token:3.5}") double charsPerToken,
            @Value("${app.nvidia.translation-expansion-factor:1.15}") double expansionFactor,
            AiProviderSettingsService providerSettings,
            @Value("${app.ollama.models:}") String ollamaModels,
            @Value("${app.nvidia.slow-piece-threshold-seconds:240}") int serverCeilingSeconds,
            @Value("${app.nvidia.slow-piece-max-failures:2}") int maxSlowFailures) {
        this.chatModel = chatModel;
        this.modelNames = parsePool(models);
        this.ollamaModelNames = parsePool(ollamaModels);
        this.maxRetries = maxRetries;
        this.rateLimiter = rateLimiter;
        this.inspector = inspector;
        this.providerSettings = providerSettings;
        this.maxOutputTokens = maxOutputTokens;
        this.reasoningReserveTokens = reasoningReserveTokens;
        this.charsPerToken = charsPerToken;
        this.expansionFactor = expansionFactor;
        this.serverCeilingSeconds = serverCeilingSeconds;
        this.maxSlowFailures = Math.max(1, maxSlowFailures);
    }

    /** Parsea una lista de modelos separada por comas (pool de config). */
    private static List<String> parsePool(String models) {
        return models == null
                ? List.of()
                : Arrays.stream(models.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
    }

    /** Exposición del pool para el catálogo de modelos (ModelCatalogController). */
    public List<String> poolModelNames() {
        return List.copyOf(modelNames);
    }

    /** Exposición del pool LOCAL de Ollama (selector de proveedor en Configuración). */
    public List<String> ollamaPoolModelNames() {
        return List.copyOf(ollamaModelNames);
    }

    /**
     * Llama al modelo activo de la rotación con reintentos/rotación por
     * fallos transitorios. <b>Pre-flight</b>: antes de intentar un modelo se
     * consulta la sonda de disponibilidad del proveedor (cacheada con TTL) —
     * un proveedor caído se salta con WARN SIN quemar reintentos ni cuota;
     * si toda una vuelta al pool está offline, se falla rápido en vez de
     * colgarse en timeouts. Ciclo de vida observable en el visor/histórico
     * de logs: DEBUG antes de cada intento, INFO con el resultado (modelo,
     * caracteres de respuesta, duración, tope de salida), WARN en cada
     * fallo reintentable con rotación — y el truncate/length sube como
     * {@link TranslationTruncatedException} para que el llamador re-divida
     * la pieza.
     */
    /**
     * Caso de uso de una llamada a la rotación: cada uno puede usar un
     * proveedor distinto (nvidia | ollama) seleccionado en Configuración.
     */
    public enum AiUseCase {
        /** Pipeline de traducción de libros (chunks, glosario). */
        TRANSLATION,
        /** Asistente de Historias en Video (los 5 pasos). */
        STORY_WIZARD
    }

    /** Traducción: usa el proveedor elegido para TRADUCCIÓN (default NVIDIA). */
    public String callModelWithRotation(String prompt) {
        return callModelWithRotation(prompt, maxOutputTokens, AiUseCase.TRANSLATION);
    }

    /**
     * Asistente de Historias: usa el proveedor elegido para STORIES con el
     * tope de salida ampliado para los pasos de documento largo (assets y
     * escenas desbordan el tope global dimensionado para traducción).
     */
    public String callModelWithRotation(String prompt, int outputTokenLimit) {
        return callModelWithRotation(prompt, outputTokenLimit, AiUseCase.STORY_WIZARD);
    }

    /**
     * Núcleo de la rotación con POOL POR CASO DE USO: la selección
     * (nvidia | ollama) se lee EN CALIENTE de
     * {@link AiProviderSettingsService} — el pool NVIDIA
     * ({@code app.nvidia.models}, cloud con fallbacks locales) o el pool
     * 100% local ({@code app.ollama.models}). El resto del mecanismo
     * (rotación, reintentos, pre-flight, truncado) es idéntico.
     */
    public String callModelWithRotation(String prompt, int outputTokenLimit, AiUseCase useCase) {
        List<String> pool = poolFor(useCase);
        if (pool.isEmpty()) {
            throw new RuntimeException("No hay modelos configurados para el caso de uso " + useCase);
        }
        int attempts = 0;
        int totalModels = pool.size();
        int skippedOffline = 0;
        // Fallos que agotaron el techo de tiempo del servidor en ESTA
        // llamada: al acumular maxSlowFailures, la pieza se re-divide en
        // vez de quemar el pool completo.
        int slowTimeouts = 0;

        while (attempts < totalModels * maxRetries) {
            String currentModel = pool.get(currentModelIndex.get() % totalModels);
            // Pre-flight CONSCIENTE DEL PROVEEDOR: los modelos ollama: son
            // LOCALES (siempre disponibles, sin depender de la red); los
            // demás son de NVIDIA y solo se intentan si el proveedor
            // responde a la sonda corta (cacheada).
            boolean isOllama = currentModel.startsWith("ollama:");
            boolean providerOnline = isOllama
                    ? inspector.isOllamaOnline()
                    : inspector.isNvidiaOnline();
            if (!providerOnline) {
                skippedOffline++;
                if (skippedOffline >= totalModels) {
                    // Toda una vuelta al pool está offline: fallar rápido
                    // en vez de colgarse en timeouts agotando los reintentos.
                    throw new RuntimeException(
                            "All models are offline/unavailable (probe failed for "
                                    + skippedOffline + " rotation slots)");
                }
                log.warn("[llm] Modelo {} saltado: proveedor {} offline (sonda cacheada). Rotando al siguiente",
                        currentModel, isOllama ? "Ollama" : "NVIDIA");
                rotateModel(totalModels);
                continue;
            }
            skippedOffline = 0;
            // Cada intento es una solicitud a la API: respeta la cuota
            // global (RPM) antes de disparar la llamada.
            rateLimiter.acquire();
            long callStart = System.currentTimeMillis();
            // INFO (no DEBUG): es la señal de progreso que debe quedar en el
            // histórico de BD (min-level INFO) — con una llamada lenta o
            // colgada, esta línea es la prueba de que el intento empezó.
            log.info("[llm] Llamando al modelo {} (prompt de {} caracteres, intento {}/{})",
                    currentModel, prompt.length(), attempts + 1, totalModels * maxRetries);
            try {
                String text;
                if (isOllama) {
                    // ÚLTIMO RECURSO: modelo local de Ollama — llamada HTTP
                    // directa al servidor local (localhost:11434/api/chat),
                    // sin depender de la red ni de la API de NVIDIA. Ya
                    // detecta el truncado (done_reason="length") y devuelve
                    // el texto directamente.
                    text = callOllamaModel(currentModel.substring("ollama:".length()), prompt, outputTokenLimit);
                } else {
                    ChatResponse response = ChatClient.create(chatModel)
                            .prompt()
                            .user(prompt)
                            .options(OpenAiChatOptions.builder()
                                    .model(currentModel)
                                    .temperature(0.2)
                                    .topP(0.95)
                                    .timeout(Duration.ofMinutes(60)) // Timeout largo para piezas grandes (~8K tokens)
                                    .maxTokens(outputTokenLimit))
                            .call()
                            .chatResponse();
                    if (response != null && response.getResult() != null
                            && response.getResult().getOutput() != null) {
                        if (wasTruncated(response)) {
                            throw new TranslationTruncatedException(
                                    "Response truncated by the model output token limit (finish_reason="
                                            + finishReasonOf(response) + ") for model " + currentModel);
                        }
                        text = response.getResult().getOutput().getText();
                    } else {
                        throw new IllegalStateException("Empty response from model: " + currentModel);
                    }
                }

                if (text == null || text.isBlank()) {
                    throw new IllegalStateException("Empty response from model: " + currentModel);
                }
                log.info("[llm] Respuesta de {}: {} caracteres en {} ms (tope de salida: {} tokens, prompt de {} caracteres)",
                        currentModel, text.length(), System.currentTimeMillis() - callStart,
                        maxOutputTokens, prompt.length());
                return text;

            } catch (TranslationTruncatedException e) {
                throw e;
            } catch (Exception e) {
                long elapsedSeconds = (System.currentTimeMillis() - callStart) / 1000;
                if (isModelGone(e)) {
                    // 410/404: EL MODELO murió para siempre (EOL de NVIDIA
                    // o no desplegado) — la llamada es irrecuperable en
                    // ESTE modelo, pero la pieza NO debe morir con él.
                    // Rotar al siguiente Y avanzar el índice: clasificarlo
                    // como no-reintentable directo mataba el chunk y, al
                    // no avanzar el índice, la cascada mataba también
                    // todos los chunks siguientes (incidente kimi-k3 EOL:
                    // 10 chunks muertos sobre el mismo modelo 410).
                    log.warn("[llm] Modelo {} retirado de forma permanente (410/404: fin de vida o no desplegado). "
                            + "Rotando al siguiente sin reintentar este",
                            currentModel);
                    rotateModel(totalModels);
                    attempts++;
                } else if (countsAsSlowFailure(e, elapsedSeconds)) {
                    // La generación no llegó a tiempo: el servidor corta
                    // a ~240 s (los fallos llegan como "Request failed" a
                    // ~243 s). Una rotación puede salvarla (un modelo más
                    // rápido), pero si se repite, la pieza es demasiado
                    // grande para el techo de ESTE pool — re-dividirla en
                    // vez de quemar ~4 min + cuota RPM por cada modelo.
                    slowTimeouts++;
                    if (slowTimeouts >= maxSlowFailures) {
                        throw new PieceGenerationTooSlowException(
                                "La generación superó el techo de tiempo del servidor (~" + serverCeilingSeconds
                                        + " s) en " + slowTimeouts + " modelo(s) del pool");
                    }
                    log.warn("[llm] La generación con {} agotó el techo de tiempo del servidor tras {} s "
                                    + "(timeout lento {}/{}): rotando al siguiente modelo",
                            currentModel, elapsedSeconds, slowTimeouts, maxSlowFailures);
                    rotateModel(totalModels);
                    attempts++;
                } else if (isRetryableError(e)) {
                    log.warn("[llm] Fallo reintentable con el modelo {} tras {} ms: {}. Rotando al siguiente modelo",
                            currentModel, System.currentTimeMillis() - callStart, e.getMessage());
                    rotateModel(totalModels);
                    attempts++;
                } else {
                    throw new RuntimeException("Non-retryable error calling NVIDIA model: " + currentModel, e);
                }
            }
        }
        throw new RuntimeException("All models exhausted after " + attempts + " attempts");
    }

    /** {@code finish_reason=length}: la respuesta se cortó por el tope de salida. */
    private static boolean wasTruncated(ChatResponse response) {
        String finishReason = finishReasonOf(response);
        return finishReason != null && finishReason.toLowerCase().contains("length");
    }

    private static String finishReasonOf(ChatResponse response) {
        try {
            return response.getResult().getMetadata() != null
                    ? response.getResult().getMetadata().getFinishReason()
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * El MODELO murió para siempre (a diferencia de la llamada): 410 Gone —
     * NVIDIA retira los modelos al final de su vida ("end of life") — o
     * 404 (no desplegado / nunca existió; en Ollama: modelo sin descargar).
     * La rotación debe pasar al SIGUIENTE modelo sin reintentar este: la
     * pieza no puede morir con el modelo. Clasificar el 410 como
     * no-reintentable directo mataba el chunk Y dejaba el índice clavado
     * en el modelo muerto, de modo que la cascada mataba también todos
     * los chunks siguientes.
     */
    boolean isModelGone(Exception e) {
        // Detección tipada del SDK caminando la cadena de causas (el wrap
        // genérico de Spring AI oculta la excepción original): 410 llega
        // como UnexpectedStatusCodeException, 404 como NotFoundException,
        // ambos con statusCode() del contrato de OpenAIServiceException.
        Throwable current = e;
        int hops = 0;
        while (current != null && hops < 6) {
            if (current instanceof OpenAIServiceException apiException) {
                return apiException.statusCode() == 410 || apiException.statusCode() == 404;
            }
            current = current.getCause();
            hops++;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // Fallback por mensaje para excepciones envueltas cuyo texto
        // conserva el original (formato del SDK "<status>: <body>") y para
        // el 404 del modelo local de Ollama.
        return message.contains("end of life")
                || message.contains("no longer available")
                || message.contains("410:")
                || message.contains("Ollama HTTP 404");
    }

    /**
     * Cuenta como fallo lento un intento que era REINTENTABLE (la pieza
     * seguía generándose, no fue un error de petición) y cuya duración
     * alcanzó el techo de tiempo del servidor — la firma de una
     * generación asesinada por el límite de ~240 s, no un blip de red
     * (los blips fallan en segundos, sin acercarse al techo).
     */
    boolean countsAsSlowFailure(Exception e, long elapsedSeconds) {
        return elapsedSeconds >= serverCeilingSeconds && isRetryableError(e);
    }

    private boolean isRetryableError(Exception e) {
        // Network-level failures of the OpenAI SDK ("Request failed": reset
        // connections, DNS hiccups, socket errors mid-response) are
        // transient by nature: rotate the model and retry instead of
        // aborting a whole translation pass on a single blip.
        if (e instanceof OpenAIIoException) {
            return true;
        }
        // Fallos TRANSITORIOS del modelo LOCAL de Ollama (5xx/429, reset de
        // conexión a mitad de generación): el modelo es gratis y un
        // reintento casi siempre completa la pieza — clasificarlos como
        // no-reintentables mataba chunks enteros tras minutos de cola.
        if (e instanceof OllamaTransientException) {
            return true;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("429")
                || message.contains("rate limit")
                || message.contains("quota")
                || message.contains("exhausted")
                // "timed out" además de "timeout": el mensaje REAL de
                // HttpTimeoutException es "request timed out" (con 'd'),
                // que NO contiene el substring "timeout" — con la keyword
                // antigua, un timeout del JDK mataba el chunk como
                // no-reintentable.
                || message.contains("timeout")
                || message.contains("timed out")
                || message.contains("503");
    }

    private void rotateModel(int poolSize) {
        currentModelIndex.updateAndGet(i -> (i + 1) % poolSize);
    }

    /**
     * Pool activo para el caso de uso: lee la selección EN CALIENTE de
     * {@link AiProviderSettingsService}. "ollama" → pool 100% local
     * (app.ollama.models); "nvidia" (o pool local vacío) → pool de NVIDIA
     * (app.nvidia.models, que ya incluye sus fallbacks locales).
     */
    private List<String> poolFor(AiUseCase useCase) {
        String provider = useCase == AiUseCase.STORY_WIZARD
                ? providerSettings.storyProvider()
                : providerSettings.translationProvider();
        boolean useOllama = "ollama".equalsIgnoreCase(provider) && !ollamaModelNames.isEmpty();
        log.debug("[llm] Pool para {}: proveedor {} ({} modelos)",
                useCase, useOllama ? "ollama" : "nvidia",
                useOllama ? ollamaModelNames.size() : modelNames.size());
        return useOllama ? ollamaModelNames : modelNames;
    }

    public String translateChunk(String text, String sourceLang, String targetLang) {
        return translateChunk(text, sourceLang, targetLang, List.of());
    }

    /**
     * Traduce con el glosario de la serie/libro inyectado en el prompt:
     * los términos con traducción establecida deben respetarse y el
     * estilo prima la naturalidad sobre la literalidad.
     *
     * <p>Estrategia de presupuesto de salida: si la respuesta proyectada
     * del texto completo excede el cupo del modelo, el texto se divide
     * en piezas que caben cómodamente (ver {@link #splitForOutputBudget})
     * y cada pieza se traduce en su propia llamada con el MISMO glosario,
     * re-ensamblando después con los separadores originales.</p>
     */
    public String translateChunk(String text, String sourceLang, String targetLang,
                                  List<com.glez.frontendservice.model.TranslationTerm> glossary) {
        int maxPieceChars = estimateMaxPieceChars();
        if (text.length() <= maxPieceChars) {
            // Caso común: la respuesta proyectada cabe en una sola llamada.
            return translateWithBudget(text, sourceLang, targetLang, glossary);
        }
        int projectedTokens = estimateProjectedOutputTokens(text.length());
        List<Piece> pieces = splitForOutputBudget(text, maxPieceChars);
        log.info("[traducción] Texto de {} caracteres excede el presupuesto de salida "
                        + "(≈{} tokens proyectados > {} disponibles): dividiendo en {} piezas de ≤{} caracteres",
                text.length(), projectedTokens, maxOutputTokens - reasoningReserveTokens,
                pieces.size(), maxPieceChars);
        StringBuilder translated = new StringBuilder(Math.max(text.length() * 2, 64));
        for (int i = 0; i < pieces.size(); i++) {
            Piece piece = pieces.get(i);
            log.debug("[traducción] Pieza {}/{}: {} caracteres", i + 1, pieces.size(), piece.text().length());
            String pieceResult = translateWithBudget(piece.text(), sourceLang, targetLang, glossary);
            if (i > 0) {
                // Frontera real de párrafo => "\n\n"; corte forzado dentro
                // de un párrafo gigante => " " (no se inventan saltos).
                translated.append(piece.joinWithSpace() ? ' ' : "\n\n");
            }
            translated.append(pieceResult.strip());
        }
        return translated.toString();
    }

    /**
     * Traduce UNA pieza con auto-sanación: si el modelo trunca la
     * respuesta ({@code finish_reason=length}) la pieza se re-divide a la
     * mitad y se reintenta recursivamente — cada nivel garantiza piezas
     * estrictamente más pequeñas, con piso en
     * {@link #MIN_SELF_HEAL_PIECE_CHARS}: bajo el piso el fallo sube sin
     * más reintentos (el chunk queda FAILED visiblemente en vez de
     * persistir una traducción corrupta).
     *
     * <p>El MISMO auto-sanado aplica cuando la generación no cabe en el
     * techo de tiempo del servidor ({@link PieceGenerationTooSlowException}):
     * las mitades (y sus mitades, recursivamente) tardan proporcionalmente
     * menos y convergen bajo el techo, en vez de quemar el pool completo
     * con intentos de ~4 min condenados al mismo corte.</p>
     */
    private String translateWithBudget(String text, String sourceLang, String targetLang,
                                        List<com.glez.frontendservice.model.TranslationTerm> glossary) {
        String prompt = buildTranslationPrompt(sourceLang, targetLang, text, glossary);
        try {
            return callModelWithRotation(prompt);
        } catch (TranslationTruncatedException e) {
            if (text.length() <= MIN_SELF_HEAL_PIECE_CHARS) {
                throw e;
            }
            log.warn("[traducción] Respuesta truncada por el límite de salida en una pieza de {} caracteres: "
                    + "re-dividiendo a la mitad y reintentando", text.length());
            return translateHalves(text, sourceLang, targetLang, glossary);
        } catch (PieceGenerationTooSlowException e) {
            if (text.length() <= MIN_SELF_HEAL_PIECE_CHARS) {
                throw e;
            }
            log.warn("[traducción] La pieza de {} caracteres no cupo en el techo de tiempo del servidor "
                            + "(~{} s) en los modelos probados: re-dividiendo a la mitad (auto-sanado por tiempo)",
                    text.length(), serverCeilingSeconds);
            return translateHalves(text, sourceLang, targetLang, glossary);
        }
    }

    /**
     * Auto-sanado compartido: re-divide la pieza a la mitad (siempre en
     * fronteras de párrafo — el caso degenerado corta por oración) y
     * traduce cada parte con el MISMO glosario, re-ensamblando con los
     * separadores originales. Cada mitad abre su propia rotación con
     * presupuesto de reintentos fresco.
     */
    private String translateHalves(String text, String sourceLang, String targetLang,
                                   List<com.glez.frontendservice.model.TranslationTerm> glossary) {
        List<Piece> halves = splitForOutputBudget(text, Math.max(1, text.length() / 2));
        StringBuilder joined = new StringBuilder(Math.max(text.length() * 2, 64));
        for (int i = 0; i < halves.size(); i++) {
            Piece half = halves.get(i);
            String halfResult = translateWithBudget(half.text(), sourceLang, targetLang, glossary);
            if (i > 0) {
                joined.append(half.joinWithSpace() ? ' ' : "\n\n");
            }
            joined.append(halfResult.strip());
        }
        return joined.toString();
    }

    /**
     * Tamaño máximo de pieza en CARACTERES para el presupuesto de salida:
     * {@code (max-output-tokens - reasoning-reserve) * chars-per-token /
     * expansion-factor}. Heurística deliberadamente conservadora — una
     * sobre-división es inofensiva (piezas secuenciales con el mismo
     * glosario); una sub-división corrompe la respuesta.
     */
    int estimateMaxPieceChars() {
        int safeOutputTokens = Math.max(1, maxOutputTokens - reasoningReserveTokens);
        return (int) Math.max(1, (safeOutputTokens * charsPerToken) / expansionFactor);
    }

    /** Tokens de respuesta proyectados para un texto de los caracteres dados. */
    int estimateProjectedOutputTokens(int textChars) {
        return (int) Math.ceil((textChars / charsPerToken) * expansionFactor);
    }

    /**
     * Divide el texto en piezas de a lo sumo {@code maxPieceChars}
     * caracteres, cortando SIEMPRE en fronteras de párrafo: cada pieza
     * normal ({@code joinWithSpace=false}) re-ensambla con "\n\n". El caso
     * degenerado de un párrafo gigante (sin "\n\n") se corta en fronteras
     * de oración ('.', '!', '?', salto de línea) y sus sub-piezas
     * ({@code joinWithSpace=true}) re-ensamblan con " " para no inventar
     * saltos de párrafo que el autor no escribió; el corte duro a
     * {@code maxPieceChars} es el último recurso.
     */
    List<Piece> splitForOutputBudget(String text, int maxPieceChars) {
        List<Piece> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\n\n")) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > maxPieceChars) {
                flushCurrentPiece(current, pieces);
                pieces.addAll(splitOversizedParagraph(trimmed, maxPieceChars));
                continue;
            }
            if (current.length() > 0 && current.length() + 2 + trimmed.length() > maxPieceChars) {
                flushCurrentPiece(current, pieces);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        flushCurrentPiece(current, pieces);
        if (pieces.isEmpty()) {
            // Texto vacío o solo espacios: el flujo lo valida aguas
            // arriba, pero la estrategia no debe reventar por una pieza nula.
            pieces.add(new Piece(text, false));
        }
        return pieces;
    }

    private void flushCurrentPiece(StringBuilder current, List<Piece> pieces) {
        if (current.length() > 0) {
            pieces.add(new Piece(current.toString(), false));
            current.setLength(0);
        }
    }

    /** Párrafo gigante: cortes en fronteras de oración; duro como último recurso. */
    private List<Piece> splitOversizedParagraph(String paragraph, int maxPieceChars) {
        List<Piece> pieces = new ArrayList<>();
        int start = 0;
        while (start < paragraph.length()) {
            if (paragraph.length() - start <= maxPieceChars) {
                pieces.add(new Piece(paragraph.substring(start).strip(), true));
                break;
            }
            int windowEnd = start + maxPieceChars;
            int cut = lastSentenceBoundary(paragraph, start, windowEnd);
            if (cut <= start) {
                cut = windowEnd;
            }
            pieces.add(new Piece(paragraph.substring(start, cut).strip(), true));
            start = cut;
        }
        return pieces;
    }

    /** Última frontera de oración ('.', '!', '?' o salto) dentro de la ventana. */
    private static int lastSentenceBoundary(String text, int start, int windowEnd) {
        for (int i = windowEnd - 1; i > start + 1; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Pieza de texto con su separador de re-ensamblado: frontera real de
     * párrafo ({@code joinWithSpace=false}, se une con "\n\n") o corte
     * forzado dentro de un párrafo ({@code joinWithSpace=true}, se une
     * con " ").
     */
    /**
     * Llama a un modelo LOCAL de Ollama ({@code POST /api/chat} en el
     * servidor local, sin depender de la red ni de la API de NVIDIA) —
     * es el ÚLTIMO RECURSO de la rotación: solo se alcanza cuando el
     * proveedor NVIDIA está completamente offline.
     *
     * <p>Ollama reporta el truncamiento en {@code done_reason="length"} (el
     * equivalente del {@code finish_reason=length} de la API de NVIDIA):
     * se traduce a la misma {@link TranslationTruncatedException} para que
     * el auto-sanado re-divida la pieza.</p>
     *
     * <p><b>Sin timeout de request</b> (deliberado): la inferencia local
     * depende del hardware y una pieza grande de ~8K tokens de salida (más
     * la carga inicial del modelo desde disco) puede tardar muchos minutos.
     * El {@code connectTimeout} de 3 s del cliente sigue fallando rápido si
     * el servidor está caído, y {@code num_predict} acota la generación, así
     * que la espera no puede desbocarse eternamente.</p>
     */
    String callOllamaModel(String modelName, String prompt, int numPredict) {
        try {
            String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of(
                            "model", modelName,
                            "messages", java.util.List.of(java.util.Map.of(
                                    "role", "user",
                                    "content", prompt)),
                            "stream", false,
                            "options", java.util.Map.of(
                                    "temperature", 0.2,
                                    "top_p", 0.95,
                                    "num_predict", numPredict)));
            // Sin .timeout(): request SIN límite de tiempo (infinito). La
            // generación local de hasta ~8K tokens puede superar con creces
            // los 2 minutos según el hardware; el connectTimeout de 3 s ya
            // cubre el caso de servidor caído.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response;
            try {
                response = ollamaHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                // Caída/reset de la conexión (p. ej. el servidor muere a
                // mitad de la generación): TRANSITORIA — el modelo local es
                // gratis y un reintento casi siempre completa la pieza.
                throw new OllamaTransientException(
                        "Ollama connection error for model " + modelName + ": " + e.getMessage());
            }
            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                // 5xx/429 del servidor local: TRANSITORIOS. Con decenas de
                // chunks encolados (OLLAMA_NUM_PARALLEL=1) el servidor puede
                // cancelar tareas sanas y responder 500; la rotación debe
                // reintentar la pieza, no matar el chunk.
                throw new OllamaTransientException(
                        "Ollama HTTP " + status + " for model " + modelName);
            }
            if (status < 200 || status >= 300) {
                // 404 = modelo no descargado, resto de 4xx = error de la
                // petición: PERMANENTES, reintentar no ayuda.
                throw new IllegalStateException(
                        "Ollama HTTP " + status + " for model " + modelName);
            }
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
            String text = root.path("message").path("content").asText(null);
            String doneReason = root.path("done_reason").asText(null);
            // done_reason=length ANTES del chequeo de texto vacío: un modelo
            // de razonamiento (gpt-oss) puede gastar TODO el presupuesto de
            // salida en thinking y devolver content="" con done_reason=length;
            // eso debe activar la re-división auto-sanadora (truncado), no
            // morir como "respuesta vacía" no-reintentable.
            if (doneReason != null && doneReason.contains("length")) {
                throw new TranslationTruncatedException(
                        "Ollama response truncated by the output token limit (done_reason="
                                + doneReason + ") for model " + modelName);
            }
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("Empty Ollama response for model " + modelName);
            }
            // Envuelve en un ChatResponse del mismo tipo para que el
            // logging del ciclo de vida del llamador no cambie.
            return text;
        } catch (TranslationTruncatedException | OllamaTransientException e) {
            // Se re-lanzan SIN el wrap genérico para no perder el tipo: la
            // rotación clasifica ambas por TIPO (truncado → re-división de
            // la pieza; transitorio → reintentar), no por substrings.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error calling Ollama model " + modelName + ": " + e.getMessage(), e);
        }
    }

    /** URL base del servidor Ollama (localhost, sin depender del DNS). */
    private final String ollamaBaseUrl =
            java.net.URI.create("http://localhost:11434").toString();

    /** Cliente HTTP para Ollama: localhost, sin timeout de conexión largo. */
    private final HttpClient ollamaHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    record Piece(String text, boolean joinWithSpace) {
    }

    /**
     * La respuesta del modelo se cortó por el tope de salida
     * ({@code finish_reason=length}). NO es reintentable por rotación:
     * reintentar el mismo prompt sobredimensionado solo quemaría cuota —
     * el llamador debe re-dividir la pieza.
     */
    static class TranslationTruncatedException extends RuntimeException {
        TranslationTruncatedException(String message) {
            super(message);
        }
    }

    /**
     * La generación de la pieza superó el techo de tiempo del servidor
     * (~240 s en NVIDIA: los fallos llegan como "Request failed" a ~243 s)
     * en los modelos probados. La llamada es sana pero la pieza no cabe en
     * el techo: NO es reintentable por rotación — el llamador re-divide la
     * pieza a la mitad (auto-sanado por tiempo, el mismo mecanismo que el
     * truncado) en vez de quemar el pool completo con intentos condenados
     * al mismo corte.
     */
    static class PieceGenerationTooSlowException extends RuntimeException {
        PieceGenerationTooSlowException(String message) {
            super(message);
        }
    }

    /**
     * Fallo TRANSITORIO de la llamada HTTP al modelo local de Ollama:
     * 5xx/429 del servidor o caída/reset de la conexión a mitad de la
     * generación (con decenas de chunks encolados y
     * {@code OLLAMA_NUM_PARALLEL=1}, el servidor cancela tareas sanas y
     * responde 500). REINTENTABLE por rotación: el modelo local es gratis
     * y un reintento casi siempre completa la pieza. Los fallos
     * PERMANENTES (404: modelo no descargado) NO usan este tipo —
     * reintentarlos solo encolaría horas.
     */
    static class OllamaTransientException extends RuntimeException {
        OllamaTransientException(String message) {
            super(message);
        }
    }

    public String translateBook(String fullText, String sourceLang, String targetLang, int chunkSize) {
        String prompt = buildTranslationPrompt(sourceLang, targetLang, fullText, List.of());
        return callModelWithRotation(prompt);
    }

    /**
     * Protocolo de traducción literaria multi-paso: el modelo analiza el
     * texto de origen (corrigiendo errores de extracción), poda los restos
     * editoriales no narrativos, traduce con fidelidad de estilo/tono y
     * refina el resultado. Los idiomas son parámetros ({@code sourceLang} a
     * {@code targetLang}), de modo que el mismo protocolo sirve para
     * cualquier par de idiomas; el glosario de la serie/libro se inyecta en
     * el paso 3 como terminología establecida que no debe re-traducirse.
     *
     * <p>Regla crítica del pipeline: los saltos de párrafo del chunk se
     * preservan EXACTAMENTE — la vista paralela, el troceado TTS por bloque
     * (~1000 caracteres) y el manuscrito final dependen de ellos; solo se
     * eliminan saltos superfluos. El marcador de texto corrupto
     * ({@code [corrupted text, best guess: ...]}) puede llegar hasta el
     * audio: es un trade-off deliberado de QA — es preferible un segmento
     * marcado y visible en la vista paralela a una invención silenciosa del
     * modelo.</p>
     */
    private String buildTranslationPrompt(String sourceLang, String targetLang, String text,
                                          List<com.glez.frontendservice.model.TranslationTerm> glossary) {
        StringBuilder glossaryBlock = new StringBuilder();
        if (glossary != null && !glossary.isEmpty()) {
            glossaryBlock.append("\n**Established series glossary — use EXACTLY these ")
                    .append("translations, adapting them naturally to number, gender and ")
                    .append("conjugation (never re-translate them literally):**\n");
            for (com.glez.frontendservice.model.TranslationTerm term : glossary) {
                glossaryBlock.append("- \"").append(term.getSourceTerm())
                        .append("\" -> \"").append(term.getTargetTerm()).append("\"\n");
            }
            glossaryBlock.append('\n');
        }

        // %1$s idioma origen / %2$s idioma destino / %3$s glosario (vacío si
        // no hay términos) / %4$s el chunk SIEMPRE al final: el modelo lee
        // primero todas las instrucciones y después el texto.
        return String.format("""
                ### Persona ###
                You are a WORLD-CLASS TRANSLATOR and a hyper-meticulous LITERARY EDITOR. Your specialization is bringing foreign literary works to a new audience, perfectly preserving the author's original voice and intent. You have a deep understanding of genres ranging from Japanese, Chinese, and Korean web novels to classic Western literature. You are not just a translator; you are a cultural bridge. Your final output is always a clean, polished, and ready-to-publish manuscript.

                ### Primary Objective ###
                Translate the provided text from **%1$s** to **%2$s**. The translation must be of the highest fidelity, capturing the original style, tone, nuance, and authorial intent with absolute precision. After translation, the text must be rigorously cleaned of all non-narrative elements.

                ### Rigorous Processing Protocol (Execute these steps sequentially and without fail) ###

                **Step 1: Deep Content Analysis and Pre-Correction (%1$s)**
                *   Scan the text for severe incoherence or corruption from extraction errors.
                *   Infer and correct obvious typographical errors or punctuation mistakes in the %1$s text that disrupt meaning.
                *   Logically complete sentences fragmented by extraction errors only if the intended meaning is unambiguously clear from the context.
                *   **CRITICAL CONSTRAINT:** Never invent information or rewrite the author's style. If a segment is hopelessly corrupt, provide the most plausible translation and mark it with a concise, bracketed note (e.g., `[corrupted text, best guess: ...]`). Use this only as a last resort.

                **Step 2: Aggressive Structural Cleaning and Pruning (Pre-Translation)**
                *   Your primary goal here is to isolate the core narrative. Identify and **COMPLETELY REMOVE** all extraneous text elements from the source text. This includes, but is not limited to:
                    *   **Page Numbers & Running Heads:** Eliminate any and all page numbers or repetitive headers/footers, whether they are in the margins or embedded in the text flow.
                    *   **Publisher/Book Information:** Remove ISBN numbers, publisher names, publication dates, and any related metadata.
                    *   **Author & Credits:** Remove author names, dedications, and acknowledgments sections.
                    *   **Ancillary Content:** Excise tables of contents, indices, glossaries, bibliographies, reference lists, and any promotional material or advertisements.
                    *   **Extraction Artifacts:** Remove repetitive headers/footers (e.g., book/chapter titles appearing on every page).
                *   **DO NOT REMOVE:** Integral structural elements like chapter titles/numbers or section headings that appear once at the beginning of their respective sections.

                **Step 3: High-Fidelity Translation (%1$s to %2$s)**
                *   Execute a precise, nuanced, and culturally-aware translation.
                *   **Style Replication:** Meticulously mirror the author's original voice, narrative style (formal, informal, poetic), and emotional tone.%3$s
                *   **Cultural Nuances:** Translate idioms, metaphors, and cultural references naturally for a %2$s reader. Use contextually fitting adaptations over awkward literal translations.
                *   **Specialized Terminology (e.g., for J/C/K Novels):** Handle honorifics and cultural terms with consistency. Retain them if they are common in the target lexicon (e.g., 'senpai') or adapt them thoughtfully.
                *   **Proper Nouns:** Retain original names and terms unless a standard, widely-accepted translation exists. Maintain absolute consistency.
                *   **Tag Integrity:** Any technical tags (e.g., `<!-- image -->`) must be preserved exactly as they appear.

                **Step 4: Post-Translation Refinement and Validation (%2$s)**
                *   Thoroughly review the %2$s translation for grammatical perfection, correct spelling, and natural punctuation.
                *   Ensure all sentences are fluent and idiomatic in %2$s.
                *   **Paragraph Preservation:** Keep the original paragraph breaks EXACTLY as they appear — they feed downstream rendering and audio narration. Remove only superfluous line breaks that make the text choppy (repeated blank lines, mid-sentence splits).

                ### Final Output Formatting (Strict Adherence Required) ###
                *   You MUST return **ONLY** the fully translated, cleaned, and refined narrative text.
                *   **ABSOLUTELY NO** additional comments, greetings, preambles, apologies, or any form of self-reflection from you, the AI, are permitted in the output.
                *   Preserve the paragraph structure of the source in your output. The result must be ready for immediate use.

                ---
                **Source Language:** %1$s
                **Target Language:** %2$s

                ### Text for Processing ###
                \"\"\"
                %4$s
                \"\"\"
                """, sourceLang, targetLang, glossaryBlock.toString(), text);
    }
}