package com.glez.frontendservice.services;

import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.Chunks;
import com.glez.frontendservice.model.TranslationTerm;
import com.glez.frontendservice.repository.ChunksRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.UUID;

/**
 * Traducción de UN chunk como bean propio: al llamarse a través del proxy
 * (nunca por auto-invocación) cada chunk corre en su propio virtual thread
 * del bookProcessingExecutor, de modo que los chunks de un libro se
 * traducen en paralelo — con la cuota global (RPM) de la API de NVIDIA
 * gobernando el ritmo desde {@link NvidiaRateLimiter}.
 */
@Service
public class ChunkTranslationService {

    private static final Logger log = LoggerFactory.getLogger(ChunkTranslationService.class);

    private final ChunksRepository chunksRepository;
    private final NvidiaAiService nvidiaAiService;

    public ChunkTranslationService(ChunksRepository chunksRepository,
                                   NvidiaAiService nvidiaAiService) {
        this.chunksRepository = chunksRepository;
        this.nvidiaAiService = nvidiaAiService;
    }

    /**
     * Traduce un chunk inyectando el glosario de la serie/libro en el
     * prompt. Falla con RuntimeException para que el join de la corrida la
     * detecte; el estado FAILED se persiste por chunk.
     */
    @Async("bookProcessingExecutor")
    @Transactional
    public CompletableFuture<Void> translateChunk(UUID chunkId, List<TranslationTerm> glossary) {
        Chunks chunk = chunksRepository.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("Chunk with ID " + chunkId + " not found."));

        if (chunk.getOriginalText() == null || chunk.getOriginalText().isEmpty()) {
            throw new IllegalArgumentException("Chunk with ID " + chunkId + " has no original text to translate.");
        }

        chunk.setStatus(ChunkStatus.PROCESSING);
        chunksRepository.save(chunk);

        try {
            String translated = nvidiaAiService.translateChunk(chunk.getOriginalText(), "en", "es", glossary);
            chunk.setTranslatedText(translated);
            chunk.setStatus(ChunkStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Error translating chunk {}: {}", chunkId, e.getMessage());
            chunk.setStatus(ChunkStatus.FAILED);
            throw new RuntimeException("Translation failed for chunk " + chunkId, e);
        } finally {
            chunksRepository.save(chunk);
        }
        return CompletableFuture.completedFuture(null);
    }
}
