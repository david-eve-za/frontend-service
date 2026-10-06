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

    private String buildTranslationPrompt(String sourceLang, String targetLang, String text,
                                          List<com.glez.frontendservice.model.TranslationTerm> glossary) {
        StringBuilder glossaryBlock = new StringBuilder();
        if (glossary != null && !glossary.isEmpty()) {
            glossaryBlock.append("\nUse EXACTLY these established translations for the given terms, ")
                    .append("adapting them naturally to number, gender and conjugation ")
                    .append("(never re-translate them literally):\n");
            for (com.glez.frontendservice.model.TranslationTerm term : glossary) {
                glossaryBlock.append("- \"").append(term.getSourceTerm())
                        .append("\" -> \"").append(term.getTargetTerm()).append("\"\n");
            }
            glossaryBlock.append('\n');
        }

        return String.format("""
                You are an expert literary translator specializing in fantasy light novels.
                Translate the following text from %s to %s.

                Style: prioritize natural, idiomatic Spanish that reads like a
                professionally published novel. Restructure sentences whenever
                the literal order would sound unnatural in Spanish%s
                Maintain the original formatting, structure, and meaning.
                Do not add any explanations or notes.

                Text to translate:
                %s
                """, sourceLang, targetLang, glossaryBlock.toString(), text);
    }
}