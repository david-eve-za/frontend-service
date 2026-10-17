package com.glez.frontendservice.services;

import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.Chunks;
import com.glez.frontendservice.model.TranslationTerm;
import com.glez.frontendservice.repository.ChunksRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
     *
     * <p>SIN {@code @Transactional}: cada guardado corre en su propia
     * transacción corta (la del repositorio), de modo que la conexión del
     * pool se libera ANTES de la llamada a la API de NVIDIA — que puede
     * tardar segundos o minutos con la cuota de 30 RPM. Mantener la
     * transacción abierta durante el LLM pinneaba una conexión de Hikari
     * por chunk paralelo y agotaba el pool.</p>
     *
     * <p>Observabilidad: cada chunk corre en su propio hilo del executor,
     * así que el MDC del pipeline NUNCA llega aquí — se rellena aquí mismo
     * (bookId / traceId=chunkId / step=TRANSLATE) para que el visor de
     * logs en vivo pueda filtrar y mostrar los chips de traza de cada
     * traducción, tanto manual (endpoint de chunk individual) como
     * dentro de una corrida completa. El traceId sintético correlaciona
     * por chunk; los eventos de corrida del orquestador conservan el
     * traceId real del pipeline.</p>
     */
    @Async("bookProcessingExecutor")
    public CompletableFuture<Void> translateChunk(UUID chunkId, List<TranslationTerm> glossary) {
        Chunks chunk = chunksRepository.findById(chunkId)
                .orElseThrow(() -> new IllegalArgumentException("Chunk with ID " + chunkId + " not found."));

        if (chunk.getOriginalText() == null || chunk.getOriginalText().isEmpty()) {
            throw new IllegalArgumentException("Chunk with ID " + chunkId + " has no original text to translate.");
        }

        UUID bookId = chunk.getBook() != null ? chunk.getBook().getId() : null;
        MDC.put("bookId", bookId != null ? bookId.toString() : null);
        MDC.put("traceId", chunkId.toString());
        MDC.put("step", "TRANSLATE");
        int originalLength = chunk.getOriginalText().length();
        long start = System.currentTimeMillis();
        log.info("Traduciendo chunk {} ({} caracteres)", chunkId, originalLength);

        chunk.setStatus(ChunkStatus.PROCESSING);
        // Un reintento limpia la causa del fallo anterior.
        chunk.setErrorMessage(null);
        chunksRepository.save(chunk);

        try {
            String translated = nvidiaAiService.translateChunk(chunk.getOriginalText(), "en", "es", glossary);
            chunk.setTranslatedText(translated);
            chunk.setStatus(ChunkStatus.COMPLETED);
            log.info("Chunk {} traducido ({} → {} caracteres, {} ms)",
                    chunkId, originalLength,
                    translated != null ? translated.length() : 0,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            // La causa persiste en el chunk para que el popup en vivo del
            // FE pueda mostrarla (Visor de Logs: traza = id del chunk).
            chunk.setStatus(ChunkStatus.FAILED);
            chunk.setErrorMessage(conciseCause(e, 2000));
            log.error("Error translating chunk {} tras {} ms: {}",
                    chunkId, System.currentTimeMillis() - start, e.getMessage());
            throw new RuntimeException("Translation failed for chunk " + chunkId, e);
        } finally {
            chunksRepository.save(chunk);
            MDC.clear();
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Causa concisa de un fallo: concatena la cadena de causas (la más
     * profunda suele ser el error real de la IA) y la trunca para la
     * columna error_message.
     */
    private static String conciseCause(Throwable e, int maxChars) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        int hops = 0;
        while (current != null && hops < 6) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" ← ");
                }
                sb.append(current.getMessage().trim());
            }
            current = current.getCause();
            hops++;
        }
        if (sb.length() == 0) {
            sb.append(e.getClass().getSimpleName());
        }
        return sb.length() <= maxChars ? sb.toString() : sb.substring(0, maxChars) + "…";
    }
}
