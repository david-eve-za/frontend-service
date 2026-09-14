package com.glez.frontendservice.services;

import com.glez.frontendservice.model.RegexPattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TextExtractorService {

    private final RegexPatternService regexPatternService;

    // Cache for compiled patterns to avoid recompilation
    private List<RegexPattern> cachedPatterns = null;
    private long lastCacheRefresh = 0;
    private static final long CACHE_TTL_MS = 60000; // 1 minute cache

    /**
     * Extrae texto de un archivo (PDF u otros formatos soportados por Tika) y lo limpia.
     */
    public String extractAndCleanText(InputStream inputStream, String filename) throws IOException {
        Resource resource = new InputStreamResource(inputStream);
        List<Document> documents;

        if (filename.toLowerCase().endsWith(".pdf")) {
            PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource);
            documents = pdfReader.get();
        } else {
            TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
            documents = tikaReader.get();
        }

        String rawText = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        return cleanText(rawText);
    }

    /**
     * Limpia el texto aplicando los patrones configurados desde la base de datos.
     */
    public String cleanText(String text) {
        if (text == null) return "";

        String cleaned = text;
        List<RegexPattern> patterns = getCachedPatterns();

        for (RegexPattern regexPattern : patterns) {
            if (!regexPattern.getEnabled()) continue;

            try {
                Pattern pattern = regexPattern.toPattern();
                String replacement = regexPattern.getReplacement() != null ? regexPattern.getReplacement() : "";
                cleaned = pattern.matcher(cleaned).replaceAll(replacement);
            } catch (Exception e) {
                log.warn("Error applying regex pattern '{}': {}", regexPattern.getName(), e.getMessage());
            }
        }

        // Post-processing: normalize multiple newlines (always applied)
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");

        return cleaned.trim();
    }

    /**
     * Obtiene los patrones cacheados, refrescando si es necesario.
     */
    private List<RegexPattern> getCachedPatterns() {
        long now = System.currentTimeMillis();
        if (cachedPatterns == null || (now - lastCacheRefresh) > CACHE_TTL_MS) {
            cachedPatterns = regexPatternService.getPatternsForTextCleaning();
            lastCacheRefresh = now;
            log.debug("Refreshed regex pattern cache, loaded {} patterns", cachedPatterns.size());
        }
        return cachedPatterns;
    }

    /**
     * Fuerza la recarga de la caché de patrones.
     */
    public void refreshPatternCache() {
        cachedPatterns = null;
        lastCacheRefresh = 0;
        getCachedPatterns();
    }
}