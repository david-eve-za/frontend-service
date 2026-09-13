package com.glez.frontendservice.services;

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

    public NvidiaAiService(
            OpenAiChatModel chatModel,
            @Value("${app.nvidia.models}") String models,
            @Value("${app.nvidia.max-retries:3}") int maxRetries) {
        this.chatModel = chatModel;
        this.modelNames = Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        this.maxRetries = maxRetries;
    }

    public String callModelWithRotation(String prompt) {
        int attempts = 0;
        int totalModels = modelNames.size();

        while (attempts < totalModels * maxRetries) {
            String currentModel = modelNames.get(currentModelIndex.get());
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
        String prompt = buildTranslationPrompt(sourceLang, targetLang, text);
        return callModelWithRotation(prompt);
    }

    public String translateBook(String fullText, String sourceLang, String targetLang, int chunkSize) {
        String prompt = buildTranslationPrompt(sourceLang, targetLang, fullText);
        return callModelWithRotation(prompt);
    }

    private String buildTranslationPrompt(String sourceLang, String targetLang, String text) {
        return String.format("""
                Translate the following text from %s to %s.
                Maintain the original formatting, structure, and meaning.
                Do not add any explanations or notes.
                
                Text to translate:
                %s
                """, sourceLang, targetLang, text);
    }
}