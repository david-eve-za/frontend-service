package com.glez.frontendservice.services;

import com.glez.frontendservice.model.ChunkStatus;
import com.glez.frontendservice.model.ProcessingStatus;
import com.glez.frontendservice.repository.BookRepository;
import com.glez.frontendservice.repository.ChunksRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * On application startup, automatically resumes the pipeline of every book
 * that was left mid-processing by a crash or restart (status PROCESSING)
 * and every book that previously failed (status FAILED), bounded by the
 * configured max attempts per step.
 *
 * <p>Antes de reanudar nada, cura los chunks huérfanos: al arrancar NINGÚN
 * hilo de traducción está vivo, así que toda fila en PROCESSING es el
 * estado congelado de una llamada que murió con la instancia anterior
 * (crash, reinicio de devtools o apagado a mitad de una traducción). Sin
 * la cura, el UI mostraría "Traduciendo" para siempre — sin progreso en
 * logs, porque no hay nada ejecutándose — y el botón del chunk quedaría
 * deshabilitado. Los huérfanos vuelven a AWAITING con un solo bulk update
 * global; la cura corre SIEMPRE (también con auto-resume desactivado),
 * porque es higiene de estado, no una reanudación de corrida.</p>
 */
@Component
public class ProcessingStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(ProcessingStartupRecovery.class);

    private final BookRepository bookRepository;
    private final BookPipelineOrchestrator orchestrator;
    private final ChunksRepository chunksRepository;

    @Value("${app.processing.auto-resume:true}")
    private boolean autoResume;

    public ProcessingStartupRecovery(BookRepository bookRepository,
                                     BookPipelineOrchestrator orchestrator,
                                     ChunksRepository chunksRepository) {
        this.bookRepository = bookRepository;
        this.orchestrator = orchestrator;
        this.chunksRepository = chunksRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedBooks() {
        healOrphanProcessingChunks();
        if (!autoResume) {
            log.info("Auto-resume disabled (app.processing.auto-resume=false)");
            return;
        }
        // Id projection: resuming must not load every failed/interrupted
        // book entity with its multi-megabyte full_text CLOB.
        List<UUID> recoverable = bookRepository.findIdsByStatusIn(
                List.of(ProcessingStatus.PROCESSING, ProcessingStatus.FAILED));
        if (recoverable.isEmpty()) {
            log.info("No books to resume on startup");
            return;
        }
        log.info("Resuming {} book(s) interrupted by a previous run", recoverable.size());
        for (UUID bookId : recoverable) {
            try {
                orchestrator.processBook(bookId);
            } catch (Exception e) {
                log.error("Could not resume book {}", bookId, e);
            }
        }
    }

    /**
     * Devuelve a AWAITING toda fila huérfana en PROCESSING: sus hilos
     * murieron con la instancia anterior y el estado miente ("Traduciendo"
     * sin ningún trabajo en curso). La traducción ya completada NO se
     * pierde: solo cambia el estado; el translatedText permanece y una
     * nueva traducción lo sobrescribirá cuando termine.
     */
    void healOrphanProcessingChunks() {
        try {
            int healed = chunksRepository.requeueAllStaleProcessing(
                    ChunkStatus.PROCESSING, ChunkStatus.AWAITING);
            if (healed > 0) {
                log.warn("Recuperación de arranque: {} chunk(s) huérfanos en PROCESSING devueltos a AWAITING "
                        + "(sus hilos de traducción murieron con la instancia anterior)", healed);
            }
        } catch (Exception e) {
            log.error("No se pudieron curar los chunks huérfanos en PROCESSING", e);
        }
    }
}
