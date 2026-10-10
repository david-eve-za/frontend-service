package com.glez.frontendservice.services;

import com.openai.errors.OpenAIIoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
    private final AtomicInteger currentModelIndex = new AtomicInteger(0);
    private final int maxRetries;
    private final NvidiaRateLimiter rateLimiter;
    private final int maxOutputTokens;
    private final int reasoningReserveTokens;
    private final double charsPerToken;
    private final double expansionFactor;

    public NvidiaAiService(
            OpenAiChatModel chatModel,
            @Value("${app.nvidia.models}") String models,
            @Value("${app.nvidia.max-retries:3}") int maxRetries,
            NvidiaRateLimiter rateLimiter,
            @Value("${app.nvidia.max-output-tokens:8192}") int maxOutputTokens,
            @Value("${app.nvidia.reasoning-reserve-tokens:1024}") int reasoningReserveTokens,
            @Value("${app.nvidia.chars-per-token:3.5}") double charsPerToken,
            @Value("${app.nvidia.translation-expansion-factor:1.15}") double expansionFactor) {
        this.chatModel = chatModel;
        this.modelNames = Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        this.maxRetries = maxRetries;
        this.rateLimiter = rateLimiter;
        this.maxOutputTokens = maxOutputTokens;
        this.reasoningReserveTokens = reasoningReserveTokens;
        this.charsPerToken = charsPerToken;
        this.expansionFactor = expansionFactor;
    }

    /**
     * Llama al modelo activo de la rotación con reintentos/rotación por
     * fallos transitorios. Ciclo de vida observable en el visor/histórico
     * de logs: DEBUG antes de cada intento, INFO con el resultado (modelo,
     * caracteres de respuesta, duración, tope de salida), WARN en cada
     * fallo reintentable con rotación — y el truncate/length sube como
     * {@link TranslationTruncatedException} para que el llamador re-divida
     * la pieza.
     */
    public String callModelWithRotation(String prompt) {
        int attempts = 0;
        int totalModels = modelNames.size();

        while (attempts < totalModels * maxRetries) {
            String currentModel = modelNames.get(currentModelIndex.get());
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
                ChatClient client = ChatClient.create(chatModel);
                ChatResponse response = client.prompt()
                        .user(prompt)
                        .options(OpenAiChatOptions.builder()
                                .model(currentModel)
                                .temperature(0.2)
                                .topP(0.95)
                                // Tope EXPLÍCITO de salida: el límite del
                                // modelo nunca se descubre por respuesta
                                // cortada a ciegas.
                                .maxTokens(maxOutputTokens))
                        .call()
                        .chatResponse();

                if (response != null && response.getResult() != null
                        && response.getResult().getOutput() != null) {
                    if (wasTruncated(response)) {
                        // NO rota el modelo: reintentar el mismo prompt
                        // sobredimensionado solo quemaría cuota — el
                        // llamador re-divide la pieza (auto-sanación).
                        throw new TranslationTruncatedException(
                                "Response truncated by the model output token limit (finish_reason="
                                        + finishReasonOf(response) + ") for model " + currentModel);
                    }
                    String text = response.getResult().getOutput().getText();
                    log.info("[llm] Respuesta de {}: {} caracteres en {} ms (tope de salida: {} tokens, prompt de {} caracteres)",
                            currentModel, text != null ? text.length() : 0,
                            System.currentTimeMillis() - callStart, maxOutputTokens, prompt.length());
                    return text;
                }
                throw new IllegalStateException("Empty response from model: " + currentModel);

            } catch (TranslationTruncatedException e) {
                throw e;
            } catch (Exception e) {
                if (isRetryableError(e)) {
                    log.warn("[llm] Fallo reintentable con el modelo {} tras {} ms: {}. Rotando al siguiente modelo",
                            currentModel, System.currentTimeMillis() - callStart, e.getMessage());
                    rotateModel();
                    attempts++;
                } else {
                    throw new RuntimeException("Non-retryable error calling NVIDIA model: " + currentModel, e);
                }
            }
        }
        throw new RuntimeException("All NVIDIA models exhausted after " + attempts + " attempts");
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

    private boolean isRetryableError(Exception e) {
        // Network-level failures of the OpenAI SDK ("Request failed": reset
        // connections, DNS hiccups, socket errors mid-response) are
        // transient by nature: rotate the model and retry instead of
        // aborting a whole translation pass on a single blip.
        if (e instanceof OpenAIIoException) {
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
                || message.contains("timeout")
                || message.contains("503");
    }

    private void rotateModel() {
        currentModelIndex.updateAndGet(i -> (i + 1) % modelNames.size());
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