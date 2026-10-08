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
     *
     * <p>La caché de patrones vive ahora en RegexPatternService (Caffeine,
     * evictada en cada CRUD de patrones). El caché artesanal anterior era un
     * campo mutable compartido entre virtual threads (condición de carrera)
     * y servía patrones obsoletos hasta 60 s después de editarlos desde la
     * UI; con la evict por escritura los cambios son visibles al instante.</p>
     */
    public String cleanText(String text) {
        if (text == null) return "";

        String cleaned = text;
        List<RegexPattern> patterns = regexPatternService.getPatternsForTextCleaning();

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
}
