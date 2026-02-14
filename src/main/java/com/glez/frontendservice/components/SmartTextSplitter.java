package com.glez.frontendservice.components;

import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class SmartTextSplitter {

    private final Map<String, SentenceDetectorME> sentenceDetectors = new HashMap<>();
    private final TokenTextSplitter tokenSplitter;

    public SmartTextSplitter() throws Exception {
        // Cargar el modelo de oraciones en inglés
        loadModel("en", "/model/en-sent.bin");
        // Cargar el modelo de oraciones en español
        loadModel("es", "/model/es-sent.bin");

        // Configuramos el splitter de tokens (5000 tokens según tu config de Python)
        this.tokenSplitter = new TokenTextSplitter(5000, 100, 5, 1000, true);
    }

    private void loadModel(String lang, String path) throws IOException {
        try (InputStream modelIn = getClass().getResourceAsStream(path)) {
            if (modelIn == null) {
                throw new IOException("Model file not found: " + path);
            }
            SentenceModel model = new SentenceModel(modelIn);
            sentenceDetectors.put(lang, new SentenceDetectorME(model));
        }
    }

    public List<String> split(String text, String language, int chunkSize) {
        // Seleccionar el detector según el idioma, por defecto inglés
        SentenceDetectorME detector = sentenceDetectors.get(language);
        if (detector == null) {
            detector = sentenceDetectors.get("en");
        }

        // 1. Dividir en oraciones (equivalente a lo que hace NLTK internamente)
        String[] sentences = detector.sentDetect(text);

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            // 2. Si añadir la oración excede el límite, guardamos el chunk actual
            if (estimateTokenCount(currentChunk.toString() + sentence) > chunkSize) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder(sentence);
            } else {
                currentChunk.append(" ").append(sentence);
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    // Sobrecarga para uso por defecto (inglés)
    public List<String> split(String text) {
        return split(text, "en",1000);
    }

    private int estimateTokenCount(String text) {
        // Aproximación rápida o uso del tokenizador de Spring AI
        return text.length() / 4;
    }
}