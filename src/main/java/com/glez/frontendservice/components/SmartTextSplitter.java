package com.glez.frontendservice.components;

import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SmartTextSplitter {

    private final Map<String, SentenceDetectorME> sentenceDetectors = new ConcurrentHashMap<>();
    private TokenTextSplitter tokenSplitter;

    @Value("${app.nlp.model-path:classpath:/model}")
    private String modelPath;

    @Value("${app.nlp.models.en:opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin}")
    private String enModel;

    @Value("${app.nlp.models.es:opennlp-es-ud-gsd-sentence-1.3-2.5.4.bin}")
    private String esModel;

    @Value("${app.processing.chunk-size:5000}")
    private int chunkSize;

    @Value("${app.processing.chunk-overlap:100}")
    private int chunkOverlap;

    public SmartTextSplitter() {
        // Constructor - tokenSplitter will be initialized in @PostConstruct
    }

    @PostConstruct
    public void init() {
        List<Character> punctuationMarks = List.of('.', '?', '!', '\n', '¿', '¡', ';', ':');
        this.tokenSplitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(10)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .withPunctuationMarks(punctuationMarks)
                .build();
    }

    private SentenceDetectorME getDetector(String lang) {
        return sentenceDetectors.computeIfAbsent(lang, this::loadModel);
    }

    private SentenceDetectorME loadModel(String lang) {
        String modelFile = "es".equals(lang) ? esModel : enModel;
        String fullPath = modelPath + "/" + modelFile;
        try (InputStream modelIn = getClass().getResourceAsStream(fullPath)) {
            if (modelIn == null) {
                return createFallbackDetector();
            }
            SentenceModel model = new SentenceModel(modelIn);
            return new SentenceDetectorME(model);
        } catch (IOException e) {
            return createFallbackDetector();
        }
    }

    private SentenceDetectorME createFallbackDetector() {
        try (InputStream modelIn = getClass().getResourceAsStream(modelPath + "/" + enModel)) {
            if (modelIn != null) {
                return new SentenceDetectorME(new SentenceModel(modelIn));
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    public List<String> split(String text, String language, int chunkSize) {
        Document doc = new Document(text);
        List<Document> splitDocs = tokenSplitter.apply(List.of(doc));

        List<String> chunks = new ArrayList<>();
        for (Document d : splitDocs) {
            chunks.add(d.getText());
        }
        return chunks;
    }

    public List<String> split(String text) {
        return split(text, "en", chunkSize);
    }

    public List<String> splitBySentences(String text, String language, int chunkSize) {
        SentenceDetectorME detector = getDetector(language);
        if (detector == null) {
            return split(text, language, chunkSize);
        }
        String[] sentences = detector.sentDetect(text);

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (estimateTokenCount(currentChunk.toString() + sentence) > chunkSize) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder(sentence);
            } else {
                if (!currentChunk.isEmpty()) {
                    currentChunk.append(" ");
                }
                currentChunk.append(sentence);
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private int estimateTokenCount(String text) {
        return text.length() / 4;
    }
}