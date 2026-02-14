package com.glez.frontendservice.services;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GeminiAiService {

    private final GoogleGenAiChatModel chatModel;
    private final List<String> modelNames;
    private int currentModelIndex = 0;

    public GeminiAiService(
            GoogleGenAiChatModel chatModel,
            @Value("${app.gemini.models:gemma-3-27b-it}") List<String> modelNames) {
        this.chatModel = chatModel;
        this.modelNames = modelNames;
    }

    public String callModelWithRotation(String prompt) {
        int attempts = 0;
        while (attempts < modelNames.size()) {
            String currentModel = modelNames.get(currentModelIndex);
            try {
                // Configuramos las opciones dinámicamente para el modelo actual
                var options = GoogleGenAiChatOptions.builder()
                        .model(currentModel)
                        .temperature(0.2)
                        .topP(0.95)
                        .topK(45)
                        .build();

                ChatResponse response = ChatClient.create(chatModel)
                        .prompt(prompt)
                        .options(options)
                        .call()
                        .chatResponse();

                assert response != null;
                return response.getResult().getOutput().getText();

            } catch (Exception e) {
                // Si hay error de cuota (429), rotamos al siguiente modelo
                if (e.getMessage().contains("429") || e.getMessage().contains("exhausted")) {
                    rotateModel();
                    attempts++;
                } else {
                    throw e; // Error no recuperable
                }
            }
        }
        throw new RuntimeException("Todos los modelos de Gemini están agotados.");
    }

    private void rotateModel() {
        currentModelIndex = (currentModelIndex + 1) % modelNames.size();
    }

    /**
     * Migración de split_into_limit: Divide el texto según tokens
     */
    public List<Document> splitText(String text, int chunkSize, int overlapSize) {
        // Spring AI incluye un TokenTextSplitter muy eficiente
        TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, overlapSize, 5, 1000, true);
        Document doc = new Document(text);
        return splitter.split(doc);
    }
}