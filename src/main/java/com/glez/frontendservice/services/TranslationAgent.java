package com.glez.frontendservice.services;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TranslationAgent {

    private final GeminiAiService geminiAiService;
    private final TranslationPromptService translationPromptService;

    public TranslationAgent(GeminiAiService geminiAiService, TranslationPromptService translationPromptService) {
        this.geminiAiService = geminiAiService;
        this.translationPromptService = translationPromptService;
    }

    public String translateBook(String fullText, String sourceLang, String targetLang) {
        List<Document> chunks = geminiAiService.splitText(fullText, 5000, 0);

        StringBuilder translatedFullText = new StringBuilder();

        for (Document chunk : chunks) {
            String translatedChunk = translateChunk(chunk.getText(), sourceLang, targetLang);
            translatedFullText.append(translatedChunk).append("\n\n");
        }

        return translatedFullText.toString().trim();
    }

    public String translateChunk(String text, String sourceLang, String targetLang) {
        String prompt = translationPromptService.generatePrompt(sourceLang, targetLang, text);

        try {
            return geminiAiService.callModelWithRotation(prompt);
        } catch (Exception e) {
            return "[ERROR EN TRADUCCIÓN DE FRAGMENTO]";
        }
    }
}
