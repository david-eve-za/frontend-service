package com.glez.frontendservice.services;

import com.openai.errors.OpenAIIoException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class NvidiaAiService {

    private final OpenAiChatModel chatModel;
    private final List<String> modelNames;
    private final AtomicInteger currentModelIndex = new AtomicInteger(0);
    private final int maxRetries;
    private final NvidiaRateLimiter rateLimiter;

    public NvidiaAiService(
            OpenAiChatModel chatModel,
            @Value("${app.nvidia.models}") String models,
            @Value("${app.nvidia.max-retries:3}") int maxRetries,
            NvidiaRateLimiter rateLimiter) {
        this.chatModel = chatModel;
        this.modelNames = Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        this.maxRetries = maxRetries;
        this.rateLimiter = rateLimiter;
    }

    public String callModelWithRotation(String prompt) {
        int attempts = 0;
        int totalModels = modelNames.size();

        while (attempts < totalModels * maxRetries) {
            String currentModel = modelNames.get(currentModelIndex.get());
            // Cada intento es una solicitud a la API: respeta la cuota
            // global (RPM) antes de disparar la llamada.
            rateLimiter.acquire();
            try {
                ChatClient client = ChatClient.create(chatModel);
                ChatResponse response = client.prompt()
                        .user(prompt)
                        .options(OpenAiChatOptions.builder()
                                .model(currentModel)
                                .temperature(0.2)
                                .topP(0.95))
                        .call()
                        .chatResponse();

                if (response != null && response.getResult() != null
                        && response.getResult().getOutput() != null) {
                    return response.getResult().getOutput().getText();
                }
                throw new IllegalStateException("Empty response from model: " + currentModel);

            } catch (Exception e) {
                if (isRetryableError(e)) {
                    rotateModel();
                    attempts++;
                } else {
                    throw new RuntimeException("Non-retryable error calling NVIDIA model: " + currentModel, e);
                }
            }
        }
        throw new RuntimeException("All NVIDIA models exhausted after " + attempts + " attempts");
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
     */
    public String translateChunk(String text, String sourceLang, String targetLang,
                                  List<com.glez.frontendservice.model.TranslationTerm> glossary) {
        String prompt = buildTranslationPrompt(sourceLang, targetLang, text, glossary);
        return callModelWithRotation(prompt);
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