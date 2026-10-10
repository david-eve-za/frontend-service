package com.glez.frontendservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glez.frontendservice.model.Book;
import com.glez.frontendservice.model.NovelVolume;
import com.glez.frontendservice.model.TranslationTerm;
import com.glez.frontendservice.repository.BlockRepository;
import com.glez.frontendservice.repository.NovelVolumeRepository;
import com.glez.frontendservice.repository.TranslationTermRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Glosario de traducción de una obra/saga: los términos (nombres, títulos,
 * lugares, facciones) con su traducción establecida, para que todos los
 * chunks de un libro y todos los volúmenes de la misma serie se traduzcan
 * de forma consistente.
 *
 * <p>El glosario se genera automáticamente con el LLM la primera vez que
 * se traduce un libro de la serie (títulos de bloques + muestra del
 * texto) y se reutiliza en adelante; los términos también pueden curarse
 * a mano vía la API del GlossaryController.
 */
@Service
public class GlossaryService {

    private static final Logger log = LoggerFactory.getLogger(GlossaryService.class);

    /** Tamaño máximo de la muestra de texto para extraer términos. */
    static final int EXTRACTION_SAMPLE_CHARS = 8000;

    /** Límite de términos extraídos (mejor pocas y esenciales). */
    static final int MAX_TERMS = 40;

    private final TranslationTermRepository termRepository;
    private final BlockRepository blockRepository;
    private final NovelVolumeRepository volumeRepository;
    /** Rotación/reintentos del pool de modelos (app.nvidia.models). */
    private final NvidiaAiService nvidiaAiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GlossaryService(TranslationTermRepository termRepository,
                           BlockRepository blockRepository,
                           NovelVolumeRepository volumeRepository,
                           NvidiaAiService nvidiaAiService) {
        this.termRepository = termRepository;
        this.blockRepository = blockRepository;
        this.volumeRepository = volumeRepository;
        this.nvidiaAiService = nvidiaAiService;
    }

    /** Par de términos devuelto por el LLM en la extracción. */
    public record TermPair(String source, String target) {
    }

    /**
     * Devuelve el glosario aplicable al libro (globales + serie + libro).
     * Si su alcance aún no tiene términos, lo extrae primero con el LLM
     * desde los títulos de bloques y una muestra del texto. Un fallo de
     * extracción NO bloquea la traducción: se registra y se devuelve lo
     * que haya.
     */
    public List<TranslationTerm> getOrCreateGlossary(Book book) {
        UUID bookId = book.getId();
        com.glez.frontendservice.model.NovelVolume volume =
                volumeRepository.findByBook(book).orElse(null);
        UUID novelId = volume != null ? volume.getNovel().getId() : null;

        List<TranslationTerm> terms = termRepository.findApplicable(novelId, bookId);
        if (termRepository.existsForScope(novelId, bookId)) {
            return terms;
        }

        try {
            List<TermPair> extracted = extractTerms(book);
            Instant now = Instant.now();
            List<TranslationTerm> toSave = new ArrayList<>(extracted.size());
            for (TermPair pair : extracted) {
                if (pair == null || isBlank(pair.source()) || isBlank(pair.target())) {
                    continue;
                }
                TranslationTerm term = new TranslationTerm();
                if (volume != null) {
                    term.setNovel(volume.getNovel());
                } else {
                    term.setBook(book);
                }
                term.setSourceTerm(pair.source().trim());
                term.setTargetTerm(pair.target().trim());
                term.setCreatedAt(now);
                term.setUpdatedAt(now);
                toSave.add(term);
            }
            // One saveAll: single transaction + JDBC-batched INSERTs
            // (before: one round-trip per term).
            termRepository.saveAll(toSave);
            log.info("Extracted {} glossary term(s) for book {}", extracted.size(), bookId);
            return termRepository.findApplicable(novelId, bookId);
        } catch (Exception e) {
            log.warn("Glossary extraction failed for book {}; translating without glossary: {}",
                    bookId, e.getMessage());
            return terms;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Extrae pares término→traducción con el LLM a partir de los títulos
     * de bloques (densos en terminología) y una muestra del texto.
     */
    private List<TermPair> extractTerms(Book book) {
        String input = buildExtractionInput(book);
        if (input.isBlank()) {
            return List.of();
        }

        String prompt = """
                You are building a translation glossary for a fantasy book series.
                From the chapter titles and the excerpt below, extract the recurring
                proper nouns and series-specific terms (character names, titles,
                places, factions, abilities, honorifics) together with their best
                Spanish translation.

                Rules:
                - Prefer the established official or fan translation when recognizable.
                - Choose the most NATURAL Spanish rendering, never word-for-word
                  literal (e.g. the title "the Angel Lord" translates as
                  "el Señor de los Ángeles", not "el Señor Ángel").
                - Skip generic words, verbs and common nouns that need no fixed
                  translation.
                - At most %d terms, ordered by importance.

                Respond ONLY with JSON: [{"source": "...", "target": "..."}]

                Chapter titles and excerpt:
                %s
                """.formatted(MAX_TERMS, input);

        // La extracción pasa por la MISMA rotación de modelos que la
        // traducción (reintentos, tope de salida, detección de truncado y
        // cuota RPM incluidos): un modelo caído no tumba la extracción —
        // se rota al siguiente del pool (app.nvidia.models).
        String raw = nvidiaAiService.callModelWithRotation(prompt);
        return parseTermPairs(raw);
    }

    /**
     * Parsea la respuesta del LLM: los modelos envuelven el JSON en
     * vallas de código con frecuencia, así que se extrae del primer '[' al
     * último ']' antes de deserializar. Un formato incomprensible sube
     * como excepción y el llamador degrada (traducción sin glosario).
     */
    private List<TermPair> parseTermPairs(String raw) {
        try {
            int start = raw.indexOf('[');
            int end = raw.lastIndexOf(']');
            if (start < 0 || end <= start) {
                throw new IllegalStateException("Glossary extraction response is not a JSON array");
            }
            return objectMapper.readValue(
                    raw.substring(start, end + 1),
                    new TypeReference<List<TermPair>>() {
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not parse the glossary extraction response", e);
        }
    }

    /** Títulos de bloques + muestra inicial del texto del libro. */
    private String buildExtractionInput(Book book) {
        StringBuilder input = new StringBuilder();
        for (var block : blockRepository.findByBookIdOrderByPositionAsc(book.getId())) {
            input.append(block.getBlockNumber() != null
                    ? block.getBlockType() + " " + block.getBlockNumber()
                    : block.getBlockType().name())
                    .append(": ")
                    .append(block.getContent() == null ? "" : firstLine(block.getContent()))
                    .append('\n');
        }
        String fullText = book.getFullText() != null ? book.getFullText() : "";
        if (fullText.length() > EXTRACTION_SAMPLE_CHARS) {
            fullText = fullText.substring(0, EXTRACTION_SAMPLE_CHARS);
        }
        input.append("\nExcerpt:\n").append(fullText);
        return input.toString();
    }

    private static String firstLine(String content) {
        int nl = content.indexOf('\n');
        String line = nl > 0 ? content.substring(0, nl) : content;
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
