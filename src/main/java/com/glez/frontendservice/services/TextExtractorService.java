package com.glez.frontendservice.services;

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

@Service
public class TextExtractorService {

    // Patrones de limpieza basados en tu código Python
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+|\\b(?:www\\.)?[\\w.-]+\\.(?:com|org|net|gov|edu|io|co|ai|app|blog|info|biz|dev|me|xyz)(?:/\\S*)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOCIAL_PATTERN = Pattern.compile("@\\w+|#\\w+");
    private static final Pattern ISBN_PATTERN = Pattern.compile("ISBN(?:\\s*-?\\s*\\d{1,5}){2,5}[xX]?|\\bISBNs\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_NUM_PATTERN = Pattern.compile("(?i)(?:^|\\s)(?:page|p\\.)\\s*\\d+\\s*(?:de\\s*\\d+)?(?:/|\\s|$)|(?i)(?:^|\\s)page\\|\\s*\\d+");
    private static final Pattern EMPTY_LINES = Pattern.compile("(\\n\\s*)+\\n");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[ \\t]+");

    /**
     * Extrae texto de un archivo (PDF o EPUB) y lo limpia.
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

    private String cleanText(String text) {
        if (text == null) return "";

        String cleaned = URL_PATTERN.matcher(text).replaceAll("");
        cleaned = SOCIAL_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = ISBN_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = PAGE_NUM_PATTERN.matcher(cleaned).replaceAll("");

        // Limpiar espacios en blanco múltiples
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ");

        // Limpiar saltos de línea múltiples (equivalente a \n{3,})
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        cleaned = EMPTY_LINES.matcher(cleaned).replaceAll("\n\n");

        return cleaned.trim();
    }
}